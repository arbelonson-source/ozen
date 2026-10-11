package com.arbelonson.ozen

import android.content.Context
import android.os.Build
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.CaptionPipeline
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.HomeServerEngine
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SpeakerEmbedding
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngine
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.WebSocketConnector
import com.arbelonson.ozen.whisper.ModelFileLoader
import com.arbelonson.ozen.whisper.SileroVoiceScorer
import java.io.File
import java.util.TimeZone
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CaptionSession(
    private val context: Context,
    private val settings: SettingsHolder,
    private val homeServerCode: HomeServerCodeStore,
    private val cloudKeys: CloudKeyStore,
    val audio: AudioCapturing = AndroidAudioCapture(context),
    private val modelFile: File = File(context.filesDir, MODEL_FILE),
    historyFolder: File = File(context.filesDir, HISTORY_FOLDER),
) {
    private val scope = MainScope()
    private val connector = WebSocketConnector()
    private val voiceScorer by lazy { SileroVoiceScorer.fromAssets(context) }
    private var interruptedBySystem = false
    val pipeline = CaptionPipeline(
        scope = scope,
        audio = audio,
        engineFactory = ::engine,
        embedder = NoVoicePrints,
        availableStorageBytes = { context.filesDir.usableSpace },
    )
    private val historyStore = TranscriptHistoryStore(historyFolder)
    private val attention = KeywordAttention()
    private val awayAlerts = AwayAlerts()
    private val notifier = AlertNotifier(context)

    /** Whether the caption screen is in front: set as MainActivity starts and stops. */
    var appIsActive = false

    private val conversations = ConversationSaver(
        writer = TranscriptHistoryWriter(historyStore),
        lines = { pipeline.segments },
        isListening = { pipeline.phase.isListening },
        settings = { settings.current.value },
        transcribing = { pipeline.activeSettings.takeIf { pipeline.isCoveringForCloud } ?: settings.current.value },
        speakerName = pipeline::displayName,
        inputName = { audio.availableInputs.firstOrNull { it.uid == audio.selectedInputUID }?.portName },
        startNewConversation = pipeline::startNewConversation,
        setSaveHistory = { on -> settings.change { it.saveHistory = on } },
        scope = scope,
    )
    val savedConversations = object : SavedConversations {
        override fun list() = historyStore.listSummaries()

        override fun search(query: String) = historyStore.search(query)

        override fun load(id: UUID) = historyStore.load(id)

        override fun sizeOnDisk() = historyStore.totalSizeOnDisk()

        override val lastSaveFailure: String? get() = conversations.lastSaveFailure

        // A conversation deleted is gone from the phone, so the copy last
        // shared as a file goes with it, whichever conversation that was.
        override fun delete(id: UUID) {
            conversations.delete(id)
            forgetSharedCopy()
            publish()
        }

        override fun deleteAll() {
            conversations.deleteAll()
            forgetSharedCopy()
            publish()
        }

        override fun rename(id: UUID, title: String) = conversations.rename(id, title)

        override fun toggleStar(sessionID: UUID, segmentID: UUID) = conversations.toggleStar(sessionID, segmentID)

        override fun switchSaving(on: Boolean) {
            conversations.switchSaving(on)
            publish()
        }

        override suspend fun keepFor(retention: HistoryRetention) {
            settings.change { it.historyRetention = retention }
            conversations.deleteExpired()
            forgetSharedCopy()
        }

        override fun opened(id: UUID) = conversations.opened(id)

        override fun doneWith(id: UUID) = conversations.doneWith(id)

        override fun isCurrent(id: UUID) = conversations.isCurrent(id)
    }

    private fun forgetSharedCopy() {
        File(context.cacheDir, SHARED_FOLDER).deleteRecursively()
    }

    init {
        (audio as? AndroidAudioCapture)?.onInterruption = { active ->
            interruptedBySystem = active
            pipeline.systemInterruptionChanged(active)
            publish()
        }
        pipeline.onPhaseChange = {
            conversations.listeningChanged()
            publish()
        }
        pipeline.onCaptionsChanged = ::publish
        pipeline.onKeywordHits = ::wordsHeard
    }

    fun listen() {
        scope.launch {
            val current = settings.current.value
            when (pipeline.phase) {
                PipelinePhase.Idle -> pipeline.start(current)
                PipelinePhase.Paused -> pipeline.resume(current)
                is PipelinePhase.Failed -> pipeline.retryAfterTap(current)
                else -> Unit
            }
        }
    }

    fun pause() = pipeline.pause()

    fun memoryShort(footprintBytes: Long) {
        pipeline.handleMemoryWarning(footprintBytes)
        conversations.memoryShort()
    }

    fun close() {
        pipeline.close()
        scope.cancel()
    }

    fun settingsChanged() {
        scope.launch {
            when (pipeline.phase) {
                PipelinePhase.Idle -> Unit
                PipelinePhase.Paused -> pipeline.settingsChangedWhilePaused()
                else -> pipeline.restart(settings.current.value)
            }
        }
    }

    private fun publish() {
        val transcribing = pipeline.activeSettings.takeIf { pipeline.isCoveringForCloud } ?: settings.current.value
        CaptionState.show(
            phase = pipeline.phase,
            engine = pipeline.activeEngineKind,
            interruptedBySystem = interruptedBySystem,
            scheduledRetry = pipeline.scheduledRetry,
            segments = pipeline.segments,
            listeningStartedAt = pipeline.listeningStartedAt,
            scoringEngine = transcribing.engine,
            scoringModel = transcribing.whisperModelVariant,
            starred = conversations::isStarred,
            conversation = conversations::savedConversation,
            keywordHits = pipeline.keywordHitSegmentIDs,
            recentlyHeard = pipeline.keywordHits,
        )
    }

    /** What was said since the last autosave is written first, so a conversation opened from the captions has every line. */
    fun saveBeforeReading() = conversations.save(ended = false)

    /** A word the captions got wrong goes first in the word list. */
    fun addWord(word: String) = setWords(listOf(word) + settings.current.value.vocabulary)

    /** The words that buzz when said; a running engine listens for them from the next line. */
    fun setImportantWords(alerts: List<KeywordAlert>) {
        settings.change { it.keywordAlerts = alerts }
        pipeline.setKeywordAlerts(alerts)
    }

    /** The names and words the engines are given, in order; a running engine has them from the next sentence. */
    fun setWords(words: List<String>) {
        settings.change { it.vocabulary = VocabularyHints.normalized(words) }
        val terms = settings.current.value.vocabulary
        scope.launch { pipeline.setVocabulary(terms) }
    }

    /** Important words just heard: they buzz on screen, or, with the app away, become a phone notification. */
    fun wordsHeard(hits: List<KeywordHit>, line: TranscriptSegment) {
        attention.claim(hits)?.let(CaptionState::heard)
        val now = System.currentTimeMillis() / 1_000.0
        val utcOffsetSeconds = TimeZone.getDefault().getOffset((now * 1_000).toLong()) / 1_000
        awayAlerts.forWords(hits, line.text, appIsActive, settings.current.value, now, utcOffsetSeconds).forEach(notifier::post)
        publish()
    }

    /** Stars a caption line or takes its star away, in its conversation and on screen. */
    fun toggleStar(line: UUID) {
        CaptionState.markStarred(line, conversations.toggleLiveStar(line))
    }

    suspend fun checkHomeComputer(): HomeServerCheck {
        val current = settings.current.value
        val started = TimeSource.Monotonic.markNow()
        val availability = homeComputerEngine(current).checkAvailability(current.languageCode)
        return HomeServerCheck.of(availability, started.elapsedNow().toDouble(DurationUnit.SECONDS))
    }

    private fun homeComputerEngine(settings: AppSettings) = HomeServerEngine(
        address = settings.homeServerAddress,
        token = homeServerCode::read,
        connector = connector,
        client = "Ozen ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}, Android ${Build.VERSION.RELEASE}",
        beam = settings.homeServerBeam,
    )

    private fun engine(settings: AppSettings): TranscriptionEngine = when (settings.engine) {
        TranscriptionEngineKind.HomeServer -> homeComputerEngine(settings)
        TranscriptionEngineKind.Cloud -> settings.cloudProvider.let { provider ->
            provider.engine(
                model = settings.chosenCloudModel,
                connector = connector,
                apiKey = { cloudKeys.read(provider) },
            )
        }
        TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.AppleSpeech -> OnDeviceWhisperEngine(
            modelName = modelFile.name,
            loader = ModelFileLoader(modelFile, context.applicationInfo.nativeLibraryDir),
            voiceScorerFactory = ::voiceScore,
        )
    }

    private fun voiceScore(): ((FloatArray) -> Float?)? {
        val scorer = voiceScorer ?: return null
        scorer.reset()
        return { input -> scorer.score(input) }
    }

    private object NoVoicePrints : SpeakerEmbedding {
        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = null
    }

    companion object {
        const val MODEL_FILE = "ggml-ozen-a3-q5_0.bin"
        const val HISTORY_FOLDER = "ozen-history"

        /** In the cache: the conversation last shared as a file, kept for the app it went to. */
        const val SHARED_FOLDER = "shared"
    }
}
