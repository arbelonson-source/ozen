package com.arbelonson.ozen

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.FileLinePass
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FixedLinesSoFar
import com.arbelonson.ozen.core.WordFixes
import com.arbelonson.ozen.core.FileTranscriptRecord
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscription.Working.Stage
import com.arbelonson.ozen.core.FileTranscriptionJob
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.PartialFileRun
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.SpeechStretch
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
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
    private val work = FileTranscriptionJob(shown, scope, Dispatchers.Default)

    internal val busyNotice = BusyNotice()
    internal val replacement = ShareReplacement<Uri>()

    private val interrupted = MutableStateFlow<String?>(null)

    /** Set once at launch when a recording was cut short by the app being ended; the screen says so and clears it. */
    val interruptedNote: StateFlow<String?> = interrupted

    fun interruptedNoteShown() {
        interrupted.value = null
    }

    /** The person's settings, for the caption size the screen's lines follow as saved lines do. */
    internal val currentSettings: StateFlow<AppSettings> get() = settings.current

    private var textKept = false
    private var leftForRecovery = false
    @Volatile private var madeLines: List<TranscribedLine> = emptyList()

    init {
        scope.launch { shown.collect { busyNotice.follow(it); replacement.follow(it)?.let(::openShared) } }
        scope.launch(Dispatchers.IO) {
            captions.fileKeeper.recoverInterrupted()?.let { interrupted.value = it.name ?: tr("הקלטה", "Recording") }
        }
    }

    val busy: Boolean get() = shown.value is FileTranscription.Working

    /** A file shared over finished text that nothing else holds is held and asked about first. */
    fun open(uri: Uri): Boolean {
        if (!replacement.arrive(uri, shown.value)) return true
        return openNow(uri)
    }

    fun replaceWithShared() {
        replacement.replace()?.let(::openShared)
    }

    private fun openShared(uri: Uri) {
        if (!openNow(uri)) replacement.declined()
    }

    private fun openNow(uri: Uri): Boolean {
        if (!busyNotice.accept(busy)) return false
        source = uri
        name = displayName(uri)
        language = settings.current.value.spokenLanguage
        chosenModel = null
        duration = null
        scope.launch {
            val probe = withContext(Dispatchers.IO) { AudioFileDecoder.probe(context, uri) }
            if (source != uri) return@launch
            when (probe) {
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
        captions.giveCaptionsBack()
        busyNotice.clear()
        shown.value = FileTranscription.Idle
        source = null
    }

    fun stopCaptions() {
        captions.stopCaptionsForRecording()
        showReady()
    }

    fun cancel() {
        work.cancel()
    }

    fun retry() {
        val failed = shown.value as? FileTranscription.Failed ?: return
        if (failed.problem.canTryAgain) showReady()
    }

    fun start() {
        if (work.running) return
        val ready = shown.value as? FileTranscription.Ready ?: return
        if (captions.captionsActive()) {
            shown.value = ready.copy(captionsRunning = true)
            return
        }
        val uri = source ?: return
        val first = ready.model ?: ready.toDownload
        val opening = working(first, if (File(captions.modelDirectory, first.file).isFile) Stage.Loading else Stage.Downloading)
        work.start(opening, backTo = ::readyPage) {
            textKept = false
            leftForRecovery = false
            try {
                captions.holdCaptionsForRecording()
                captions.waitOutCaptionsLoad()
                run(uri, ready)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (problem: Problem) {
                shown.value = FileTranscription.Failed(name, problem.kind, textKept)
            } catch (failure: Exception) {
                shown.value = FileTranscription.Failed(name, FileTranscriptionProblem.Unexpected, textKept)
            } finally {
                captions.recordingTranscriptionDone()
            }
        }
    }

    private fun showReady() {
        readyPage()?.let { shown.value = it }
    }

    private fun readyPage(): FileTranscription.Ready? {
        if (source == null) return null
        val installed = PhoneModelCatalog.managedFiles.filter { File(captions.modelDirectory, it).isFile }.toSet()
        val downloaded = FileModelChoice.downloaded(language, installed)
        val model = downloaded.firstOrNull { it.file == chosenModel } ?: FileModelChoice.pick(language, installed, totalMemory())
        return FileTranscription.Ready(name, language, model, downloaded, FileModelChoice.toDownload(language), duration, captions.captionsActive())
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
        FileTranscription.Working(name, language, model, stage, fraction, left, lines)

    private suspend fun run(uri: Uri, ready: FileTranscription.Ready) {
        val model = ready.model ?: ready.toDownload
        val file = File(captions.modelDirectory, model.file)
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
            download = downloader, network = { captions.networkNow() }, freeBytes = { captions.freeSpace() }, makeRoom = { captions.makeRoom(it) }, variant = model.variant,
            needsBytes = model.workingBytes, freeMemory = { captions.freeMemory() },
        )
        val loaded = try {
            loader.load(ready.language.languageCode, current.allowCellularModelDownload) { progress -> showPreparing(model, progress) }
        } catch (failure: EngineUnavailability) {
            throw Problem(FileTranscriptionProblem.of(failure))
        }
        var scorer: SileroVoiceScorer? = null
        var kept = false
        captions.fileKeeper.begin(name, ready.durationSeconds, model.variant, ready.language.languageCode)
        madeLines = emptyList()
        val passes = Executors.newSingleThreadExecutor { Thread(it, "ozen-recording-pass") }
        try {
            scorer = SileroVoiceScorer.fromAssets(context) ?: throw Problem(FileTranscriptionProblem.ModelFailed)
            val terms = VocabularyHints.combining(current.vocabulary, current.keywordAlerts)
            val linePass = FileLinePass(loaded.passes, ready.language.languageCode, terms, passes.asCoroutineDispatcher())
            val transcript = transcribe(uri, model, scorer, linePass)
            val lines = withFixesNow(transcript.lines)
            val record = FileTranscriptRecord.make(name, lines, finishedAt = System.currentTimeMillis() / 1_000.0, recordingSeconds = ready.durationSeconds, modelVariant = model.variant, id = captions.fileKeeper.id, languageCode = ready.language.languageCode, cutShort = transcript.endMissing)
            val history = withContext(NonCancellable + Dispatchers.IO) { captions.saveRecording(record) }
            kept = true
            if (history !is HistorySave.Failed) captions.fileKeeper.finished()
            shown.value = FileTranscription.Done(name, language, model, lines, history, transcript.endMissing)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (failure: Exception) {
            val problem = (failure as? Problem)?.kind ?: FileTranscriptionProblem.Unexpected
            val shownPartway = withContext(NonCancellable + Dispatchers.IO) {
                PartialFileRun.result(name, language, model, withFixesNow(madeLines), problem) { lines ->
                    val record = FileTranscriptRecord.make(name, lines, finishedAt = System.currentTimeMillis() / 1_000.0, recordingSeconds = ready.durationSeconds, modelVariant = model.variant, id = captions.fileKeeper.id, languageCode = ready.language.languageCode, cutShort = true)
                    captions.saveRecording(record).also { history ->
                        kept = true
                        if (history !is HistorySave.Failed) captions.fileKeeper.finished()
                    }
                }.also { if (it is FileTranscription.Done) shown.value = it }
            }
            if (shownPartway is FileTranscription.Done) return
            withContext(NonCancellable + Dispatchers.IO) {
                textKept = captions.fileKeeper.keepCutShort()
                leftForRecovery = !textKept && captions.fileKeeper.hasUnfinishedCopy()
            }
            throw failure
        } finally {
            if (!kept && !leftForRecovery) withContext(NonCancellable + Dispatchers.IO) { captions.fileKeeper.discard() }
            scorer?.close()
            loaded.release()
            passes.shutdown()
        }
    }

    /** The lines as heard, with the word fixes the person has at this moment: a fix added mid-run reaches every line. */
    private fun withFixesNow(lines: List<TranscribedLine>): List<TranscribedLine> {
        val fixes = settings.current.value.wordFixes
        return lines.map { it.copy(text = WordFixes.apply(fixes, it.text)) }
    }

    private fun showPreparing(model: PhoneModel, progress: EnginePreparationProgress) {
        val stage = if (progress.stage == EnginePreparationProgress.Stage.DownloadingModel) Stage.Downloading else Stage.Loading
        shown.value = working(model, stage, progress.fraction?.toFloat())
    }

    internal class Problem(val kind: FileTranscriptionProblem) : Exception()

    private class Transcript(val lines: List<TranscribedLine>, val endMissing: Boolean)

    private suspend fun transcribe(uri: Uri, model: PhoneModel, scorer: SileroVoiceScorer, linePass: FileLinePass): Transcript {
        var end = DecodeEnd.Whole
        val clock = TimeSource.Monotonic.markNow()
        val cutter = FileSpeechCutter(linePass.longestStretchSeconds) { chunk -> scorer.frames(chunk) ?: throw Problem(FileTranscriptionProblem.ModelFailed) }
        val lines = ArrayList<TranscribedLine>()
        // The lines with the person's word fixes as they are now, for the
        // screen and the copy kept in case the app is ended. Worked out again
        // only when a line is added (just that line) or the list changes
        // (every line, from the words heard), not on every progress report.
        val shownLines = FixedLinesSoFar()
        var heard = 0L
        val take: suspend (SpeechStretch, FloatArray) -> Unit = { stretch, window ->
            val text = linePass.text(window, voicedChunks = (stretch.voicedFrames + FRAMES_PER_CHUNK - 1) / FRAMES_PER_CHUNK)
            if (text.isNotEmpty()) {
                lines.add(TranscribedLine(stretch.startSeconds, stretch.endSeconds, text))
                madeLines = lines.toList()
                val soFar = shownLines.of(lines, settings.current.value.wordFixes)
                withContext(Dispatchers.IO) { captions.fileKeeper.linesSoFar(soFar) }
            }
        }
        fun report() {
            val done = heard.toDouble() / SAMPLE_RATE
            val fraction = TranscriptionProgress.fraction(done, duration)
            val left = TranscriptionProgress.secondsLeft(clock.elapsedNow().inWholeMilliseconds / 1_000.0, done, duration)
            shown.value = working(model, Stage.Transcribing, fraction ?: 0f, left, shownLines.of(lines, settings.current.value.wordFixes))
        }
        report()
        coroutineScope {
            val pieces = Channel<FloatArray>(PIECES_AHEAD)
            launch(Dispatchers.IO) {
                end = DecodeFeed.run(pieces) { onPiece -> AudioFileDecoder.decode(context, uri, onPiece = onPiece) }
            }
            try {
                for (piece in pieces) {
                    cutter.add(piece, take)
                    heard += piece.size
                    report()
                }
            } finally {
                pieces.cancel()
            }
        }
        cutter.finish(take)
        if (lines.isEmpty()) throw Problem(DecodeFeed.problemWithoutText(end))
        val endsEarly = TranscriptionProgress.endsEarly(heard.toDouble() / SAMPLE_RATE, duration)
        return Transcript(lines, end != DecodeEnd.Whole || endsEarly)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAMES_PER_CHUNK = FileSpeechCutter.CHUNK_SAMPLES / 512
        const val PIECES_AHEAD = 4
    }
}
