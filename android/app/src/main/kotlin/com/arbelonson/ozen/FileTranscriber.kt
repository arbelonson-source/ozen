package com.arbelonson.ozen

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.FileLinePass
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FileTranscriptRecord
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscription.Working.Stage
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.SpeechStretch
import com.arbelonson.ozen.core.SpeechStretchPlanner
import com.arbelonson.ozen.core.StorageSpaceGate
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptionProgress
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.whisper.ModelFileLoader
import com.arbelonson.ozen.whisper.PhoneSpeedMeter
import com.arbelonson.ozen.whisper.SileroVoiceScorer
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns a recording into text on the phone, with whatever Whisper model is
 * already there. Nothing is sent anywhere. The audio is read a second at a
 * time, scored for voice, cut into stretches of speech at the pauses, and
 * each stretch gets one careful pass: the same final pass live captions
 * give a finished line. See [FileTranscription] for what the screen shows.
 */
class FileTranscriber(
    private val context: Context,
    private val settings: SettingsHolder,
    private val captions: CaptionSession,
    private val scope: CoroutineScope = MainScope(),
) {
    private val shown = MutableStateFlow<FileTranscription>(FileTranscription.Idle)
    val state: StateFlow<FileTranscription> = shown

    private var source: Uri? = null
    private var name = ""
    private var duration: Double? = null
    private var language = UILanguage.Hebrew
    private var chosenModel: String? = null
    private var job: Job? = null

    val busy: Boolean get() = shown.value is FileTranscription.Working

    fun open(uri: Uri): Boolean {
        if (busy) return false
        source = uri
        name = displayName(uri)
        language = settings.current.value.spokenLanguage
        chosenModel = null
        duration = null
        scope.launch {
            when (val probe = withContext(Dispatchers.IO) { AudioFileDecoder.probe(context, uri) }) {
                is AudioFileDecoder.Probe.Playable -> {
                    duration = probe.durationSeconds
                    showReady()
                }
                AudioFileDecoder.Probe.NoAudio -> shown.value = FileTranscription.Failed(name, FileTranscriptionProblem.NoAudio)
                AudioFileDecoder.Probe.Unreadable -> shown.value = FileTranscription.Failed(name, FileTranscriptionProblem.CouldNotRead)
            }
        }
        return true
    }

    fun chooseLanguage(choice: UILanguage) {
        language = choice
        chosenModel = null
        showReady()
    }

    fun chooseModel(file: String) {
        chosenModel = file
        showReady()
    }

    fun close() {
        if (busy) return
        shown.value = FileTranscription.Idle
        source = null
    }

    fun stopCaptions() {
        captions.stopCaptionsForRecording()
        showReady()
    }

    fun cancel() {
        job?.cancel()
    }

    fun start() {
        val ready = shown.value as? FileTranscription.Ready ?: return
        if (captions.captionsActive()) {
            shown.value = ready.copy(captionsRunning = true)
            return
        }
        val uri = source ?: return
        job = scope.launch(Dispatchers.Default) {
            try {
                run(uri, ready)
            } catch (stopped: CancellationException) {
                showReady()
                throw stopped
            } catch (problem: Problem) {
                shown.value = FileTranscription.Failed(name, problem.kind)
            } catch (failure: Exception) {
                shown.value = FileTranscription.Failed(name, FileTranscriptionProblem.Unexpected)
            }
        }
    }

    private fun showReady() {
        if (source == null) return
        val installed = PhoneModelCatalog.managedFiles.filter { File(captions.modelDirectory, it).isFile }.toSet()
        val downloaded = FileModelChoice.downloaded(language, installed)
        val model = downloaded.firstOrNull { it.file == chosenModel } ?: FileModelChoice.pick(language, installed, totalMemory())
        shown.value = FileTranscription.Ready(name, language, model, downloaded, FileModelChoice.toDownload(language), duration, captions.captionsActive())
    }

    private fun totalMemory(): Long? =
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.totalMem.takeIf { it > 0 }

    private fun displayName(uri: Uri): String {
        val asked = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return asked?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: tr("הקלטה", "Recording")
    }

    private fun working(model: PhoneModel, stage: Stage, fraction: Float? = null, left: Double? = null, lines: List<TranscribedLine> = emptyList()) =
        FileTranscription.Working(name, model, stage, fraction, left, lines)

    private suspend fun run(uri: Uri, ready: FileTranscription.Ready) {
        val model = ready.model ?: ready.toDownload
        val file = File(captions.modelDirectory, model.file)
        captions.releaseModelForRecording()
        val current = settings.current.value
        val downloader = PhoneModelDownloader(file, model.source, captions.modelFetcher)
        downloader.pendingMegabytes()?.let { pending ->
            val problem = when {
                ModelDownloadGate.decide(captions.networkNow(), current.allowCellularModelDownload) == ModelDownloadGate.Decision.Offline -> FileTranscriptionProblem.Offline
                ModelDownloadGate.decide(captions.networkNow(), current.allowCellularModelDownload) == ModelDownloadGate.Decision.WaitForWiFi -> FileTranscriptionProblem.NeedsWiFi
                StorageSpaceGate.shortfallMegabytes(pending, captions.freeSpace()) != null -> FileTranscriptionProblem.NotEnoughStorage
                else -> null
            }
            if (problem != null) throw Problem(problem)
        }
        shown.value = working(model, if (file.isFile) Stage.Loading else Stage.Downloading)
        val loader = ModelFileLoader(
            file, context.applicationInfo.nativeLibraryDir, PhoneSpeedMeter.engineThreads(),
            download = downloader, network = { captions.networkNow() }, freeBytes = { captions.freeSpace() }, variant = model.variant,
        )
        val loaded = try {
            loader.load(ready.language.languageCode, current.allowCellularModelDownload) { progress -> showPreparing(model, progress) }
        } catch (failure: EngineUnavailability) {
            throw Problem(FileTranscriptionProblem.of(failure))
        }
        val scorer = SileroVoiceScorer.fromAssets(context)
        val passes = Executors.newSingleThreadExecutor { Thread(it, "ozen-recording-pass") }
        try {
            if (scorer == null) throw Problem(FileTranscriptionProblem.ModelFailed)
            val terms = VocabularyHints.combining(current.vocabulary, current.keywordAlerts)
            val linePass = FileLinePass(loaded.passes, ready.language.languageCode, terms, passes.asCoroutineDispatcher())
            val startedAt = System.currentTimeMillis() / 1_000.0
            val lines = transcribe(uri, model, scorer, linePass)
            val record = FileTranscriptRecord.make(name, lines, startedAt, endedAt = System.currentTimeMillis() / 1_000.0, modelVariant = model.variant)
            val saved = withContext(Dispatchers.IO) { captions.saveRecording(record) }
            shown.value = FileTranscription.Done(name, model, lines, record.id.takeIf { saved })
        } finally {
            scorer?.close()
            loaded.release()
            passes.shutdown()
        }
    }

    private fun showPreparing(model: PhoneModel, progress: EnginePreparationProgress) {
        val stage = if (progress.stage == EnginePreparationProgress.Stage.DownloadingModel) Stage.Downloading else Stage.Loading
        shown.value = working(model, stage, progress.fraction?.toFloat())
    }

    private class Problem(val kind: FileTranscriptionProblem) : Exception()

    private suspend fun transcribe(uri: Uri, model: PhoneModel, scorer: SileroVoiceScorer, linePass: FileLinePass): List<TranscribedLine> {
        val clock = TimeSource.Monotonic.markNow()
        val planner = SpeechStretchPlanner(maxStretchSeconds = linePass.longestStretchSeconds)
        val lines = ArrayList<TranscribedLine>()
        val audio = RollingAudio()
        var pending = FloatArray(0)
        var heard = 0L
        fun score(chunk: FloatArray) = planner.add(scorer.frames(chunk) ?: throw Problem(FileTranscriptionProblem.ModelFailed))
        suspend fun handle(stretches: List<SpeechStretch>) {
            for (stretch in stretches) {
                val window = audio.between(stretch.startSeconds - PAD_SECONDS, stretch.endSeconds + PAD_SECONDS) ?: continue
                val text = linePass.text(window, voicedChunks = (stretch.voicedFrames + FRAMES_PER_CHUNK - 1) / FRAMES_PER_CHUNK)
                if (text.isNotEmpty()) lines.add(TranscribedLine(stretch.startSeconds, stretch.endSeconds, text))
            }
            audio.keepFrom((planner.openStartSeconds ?: audio.endSeconds) - PAD_SECONDS)
        }
        fun report() {
            val done = heard.toDouble() / SAMPLE_RATE
            val fraction = TranscriptionProgress.fraction(done, duration)
            val left = TranscriptionProgress.secondsLeft(clock.elapsedNow().inWholeMilliseconds / 1_000.0, done, duration)
            shown.value = working(model, Stage.Transcribing, fraction ?: 0f, left, lines.toList())
        }
        report()
        coroutineScope {
            val pieces = Channel<FloatArray>(PIECES_AHEAD)
            launch(Dispatchers.IO) {
                val outcome = AudioFileDecoder.decode(context, uri) { piece -> pieces.trySendBlocking(piece).isSuccess }
                when (outcome) {
                    DecodeOutcome.Unreadable -> pieces.close(Problem(FileTranscriptionProblem.CouldNotRead))
                    DecodeOutcome.NoAudio -> pieces.close(Problem(FileTranscriptionProblem.NoAudio))
                    else -> pieces.close()
                }
            }
            try {
                for (piece in pieces) {
                    audio.append(piece)
                    pending += piece
                    val usable = pending.size / CHUNK_SAMPLES * CHUNK_SAMPLES
                    if (usable > 0) {
                        handle(score(pending.copyOf(usable)))
                        pending = pending.copyOfRange(usable, pending.size)
                    }
                    heard += piece.size
                    report()
                }
            } finally {
                pieces.cancel()
            }
        }
        if (pending.isNotEmpty()) handle(score(pending.copyOf(CHUNK_SAMPLES)))
        handle(planner.finish())
        if (lines.isEmpty()) throw Problem(FileTranscriptionProblem.NoSpeech)
        return lines
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 4_096
        const val FRAMES_PER_CHUNK = CHUNK_SAMPLES / 512
        const val PAD_SECONDS = 0.2
        const val PIECES_AHEAD = 4
    }
}
