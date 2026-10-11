package com.arbelonson.ozen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.core.content.pm.PackageInfoCompat
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.CaptionPipeline
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.HomeServerEngine
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.ProblemSnapshot
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.SpeakerEmbedding
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngine
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.WebSocketConnector
import com.arbelonson.ozen.core.WhisperModelCatalog
import com.arbelonson.ozen.whisper.ModelFileLoader
import com.arbelonson.ozen.whisper.SileroVoiceScorer
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CaptionSession(
    private val context: Context,
    private val settings: SettingsHolder,
    private val homeServerCode: HomeServerCodeStore,
    private val cloudKeys: CloudKeyStore,
    val audio: AudioCapturing = AndroidAudioCapture(context),
    historyFolder: File = File(context.filesDir, HISTORY_FOLDER),
    journalFile: File = File(context.filesDir, JOURNAL_FILE),
    problemAudioFolder: File = File(context.filesDir, PROBLEM_AUDIO_FOLDER),
) : DiagnosticsSource {
    private val scope = MainScope()
    private val connector = WebSocketConnector()
    private val voiceScorer by lazy { SileroVoiceScorer.fromAssets(context) }
    private var interruptedBySystem = false

    // Captions from the home computer or the cloud that stopped when the
    // internet went come back as soon as it does, as on the iPhone, not
    // only while their half-minute of retries lasts. A home computer on
    // the same Wi-Fi needs no internet, so any network counts as connected.
    private val network = AndroidNetworkMonitor(context)
    val pipeline = CaptionPipeline(
        scope = scope,
        audio = audio,
        engineFactory = ::engine,
        embedder = NoVoicePrints,
        availableStorageBytes = { context.filesDir.usableSpace },
        network = network,
    )
    private val historyStore = TranscriptHistoryStore(historyFolder)
    private val attention = KeywordAttention()
    private val awayAlerts = AwayAlerts()
    private val notifier = AlertNotifier(context)
    private val savingTrouble = SavingTroubleNotice()

    /**
     * The phone saying something for her, as on the iPhone: captions pause
     * while it talks, so the microphone doesn't caption the phone's own
     * voice, and come back by themselves once it is done, unless she paused
     * or resumed them by hand meanwhile.
     */
    private val replies = SpokenReplies(
        PhoneVoice(context),
        scope,
        object : SpokenReplies.Captions {
            override val isListening get() = pipeline.phase.isListening
            override val isPaused get() = pipeline.phase == PipelinePhase.Paused
            override val duringCall get() = interruptedBySystem

            override fun pause() = pipeline.pause()

            override fun resume() {
                scope.launch { pipeline.resume(settings.current.value) }
            }
        },
    )

    /**
     * What happened to the captions, kept on the phone past the app's
     * end, for the diagnostics report: the pipeline's events, the app
     * starting and leaving the screen, and the problems she marks.
     */
    private val journal = SessionJournal(journalFile)

    /** The last 30 seconds of sound kept with each marked problem; the app has no backups for it to leak into. */
    private val problemAudio = ProblemAudioStore(problemAudioFolder)

    /**
     * Whether this run of the app has started the captions on its own yet.
     * As on the iPhone, the caption screen's first appearance starts them,
     * once: coming back to it, or the screen being rebuilt, does not.
     */
    internal var launchHandled = false

    fun takeFirstAppearance(): Boolean {
        val first = !launchHandled
        launchHandled = true
        return first
    }

    /** Whether the caption screen is in front: set as MainActivity starts and stops. */
    var appIsActive = false
        set(active) {
            if (active != field) note(if (active) "app back on screen" else "app left the screen (phase ${if (pipeline.phase.isListening) "listening" else "not listening"})")
            field = active
        }

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
        problemAudio = problemAudio,
    )
    val savedConversations = object : SavedConversations {
        override fun list() = historyStore.listSummaries()

        override fun search(query: String) = historyStore.search(query)

        override fun load(id: UUID) = historyStore.load(id)

        override fun starredLines() = historyStore.starredLines()

        override fun sizeOnDisk() = historyStore.totalSizeOnDisk()

        override val lastSaveFailure: String? get() = conversations.lastSaveFailure

        // A conversation deleted is gone from the phone, so the copy last
        // shared as a file goes with it, whichever conversation that was.
        override fun delete(id: UUID) {
            conversations.delete(id)
            forgetSharedCopy()
            publish()
        }

        // Her words and the room's sound kept with a marked problem are part
        // of what she asked to delete: the caption lines leave the journal
        // (the rest of it stays) and the clips go.
        override fun deleteAll() {
            conversations.deleteAll()
            forgetSharedCopy()
            deleteMarkedCaptionLines()
            problemAudio.deleteAll()
            publish()
        }

        override fun rename(id: UUID, title: String) {
            conversations.rename(id, title)
            refreshSavingTrouble()
        }

        override fun toggleStar(sessionID: UUID, segmentID: UUID) = conversations.toggleStar(sessionID, segmentID).also { refreshSavingTrouble() }

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
            if (active) replies.callChanged(began = true)
            pipeline.systemInterruptionChanged(active)
            if (!active) replies.callChanged(began = false)
            publish()
        }
        pipeline.onPhaseChange = {
            conversations.listeningChanged()
            replies.captionsCameOn()
            publish()
        }
        pipeline.onCaptionsChanged = ::publish
        pipeline.onInputsChanged = ::publishInputs
        pipeline.onKeywordHits = ::wordsHeard
        replies.onSpeaking = CaptionState::speaking
        pipeline.onEvent = { journal.append(it.description, it.at) }
        val started = settings.current.value
        note("APP STARTED: ${appVersion()} Android ${Build.VERSION.RELEASE} engine ${started.engine.rawValue} model ${started.modelDescription(started.engine) ?: "-"} ${deviceState()}")
        scope.launch { combine(settings.current, settings.saveFailed) { _, _ -> }.collect { refreshSavingTrouble() } }
    }

    fun listen() {
        replies.userTookControl()
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

    fun pause() {
        replies.userTookControl()
        pipeline.pause()
    }

    val isSpeaking: Boolean get() = replies.isSpeaking

    fun canAskToRepeat() = replies.canSay(SpokenReplies.REPEAT_REQUEST)

    fun askToRepeat() {
        replies.askToRepeat(settings.current.value.speechRate)
    }

    fun canSay(text: String) = replies.canSay(text)

    fun speak(text: String) = replies.speak(text, settings.current.value.speechRate)

    fun stopSpeaking() = replies.stop()

    fun addQuickPhrase(phrase: String) {
        settings.change { it.quickPhrases = QuickPhrases.adding(phrase, it.quickPhrases, Localization.language) }
    }

    fun removeQuickPhrase(phrase: String) {
        settings.change { it.quickPhrases = QuickPhrases.removing(phrase, it.quickPhrases, Localization.language) }
    }

    fun moveQuickPhrase(phrase: String, by: Int) {
        settings.change { it.quickPhrases = QuickPhrases.moving(phrase, by, it.quickPhrases, Localization.language) }
    }

    fun restoreQuickPhrases() {
        settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(Localization.language) }
    }

    fun memoryShort(footprintBytes: Long) {
        pipeline.handleMemoryWarning(footprintBytes)
        conversations.memoryShort()
    }

    fun close() {
        pipeline.close()
        network.close()
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
            languageCode = transcribing.languageCode,
        )
        refreshSavingTrouble()
    }

    /**
     * Her choice in the picker, kept for next time even when the phone
     * refuses to switch now; false when it refused.
     */
    fun selectInput(uid: String): Boolean {
        val switched = pipeline.selectInput(uid)
        settings.change { it.preferredInputUID = uid }
        return switched
    }

    /** Asks the phone again which microphones there are: "I just plugged it in". */
    fun refreshInputs() = pipeline.refreshInputs()

    fun dismissMicrophoneDrop() = pipeline.dismissMicrophoneDrop()

    private fun publishInputs() = CaptionState.inputs(pipeline.availableInputs, pipeline.selectedInputUID, pipeline.microphoneDrop.lost)

    /** What the diagnostics screen shows and its report says, as things stand now. */
    override fun diagnostics(): DiagnosticsFacts {
        val power = context.getSystemService(PowerManager::class.java)
        // The sticky broadcast, not BatteryManager's properties: it is what
        // Android itself shows, test overrides included.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return DiagnosticsFacts(
            phase = pipeline.phase,
            activeEngine = pipeline.activeEngineKind,
            settings = settings.current.value.copy(),
            showing = Localization.language,
            homeServerCodeSaved = homeServerCode.read() != null,
            selectedInput = pipeline.availableInputs.firstOrNull { it.uid == pipeline.selectedInputUID },
            availableInputs = pipeline.availableInputs,
            stats = pipeline.stats,
            linesOnScreen = pipeline.segments.size,
            scheduledRetry = pipeline.scheduledRetry,
            interruptedBySystem = interruptedBySystem,
            settingsSaveFailed = settings.saveFailed.value,
            historySaveFailure = conversations.lastSaveFailure,
            events = pipeline.eventLog.reportLines(::utcOffsetAt),
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            appVersion = appVersion(),
            journal = journal.reportLines(::utcOffsetAt),
            hasMarkedCaptionLines = journal.entries().any { ProblemSnapshot.isCaptionLine(it.text) },
            notificationsAllowed = AlertNotifier.allowed(context),
            canVibrate = phoneVibrator(context)?.hasVibrator() == true,
            network = pipeline.networkConditions,
            modelInstalled = modelFile(settings.current.value).exists(),
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null,
            batterySaver = power.isPowerSaveMode,
            batteryPercent = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }?.takeIf { it >= 0 },
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            freeBytes = context.filesDir.usableSpace,
            memoryInUseBytes = Debug.getPss() * 1_024,
            memoryAvailableBytes = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem,
            problemClips = problemAudio.clips(),
        )
    }

    override fun markProblem(): Boolean {
        val now = System.currentTimeMillis() / 1_000.0
        val lines = ProblemSnapshot.lines(
            settings = settings.current.value,
            activeEngine = pipeline.activeEngineKind,
            input = pipeline.availableInputs.firstOrNull { it.uid == pipeline.selectedInputUID },
            stats = pipeline.stats,
            segments = pipeline.segments,
            device = deviceState(),
            utcOffsetSeconds = utcOffsetAt(now),
        )
        lines.forEach { journal.append(it, now) }
        // With saving conversations off, the room's sound isn't kept either.
        val clip = if (settings.current.value.saveHistory) problemAudio.save(pipeline.recentAudioSamples, SAMPLE_RATE, Instant.ofEpochMilli((now * 1_000).toLong())) else null
        clip?.let { journal.append("  sound saved: ${it.name}", now) }
        scope.launch { pipeline.engineDiagnostics()?.let { journal.append("  engine: $it", now) } }
        return clip != null
    }

    override fun deleteMarkedCaptionLines() = journal.removeEntries(ProblemSnapshot::isCaptionLine)

    override fun deleteProblemClip(clip: File) = problemAudio.remove(clip)

    private fun note(text: String) = journal.append(text, System.currentTimeMillis() / 1_000.0)

    private fun appVersion(): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${app.versionName} (${PackageInfoCompat.getLongVersionCode(app)})"
    }

    /** Heat, Battery Saver and the memory the app holds: all slow captions down with nothing on screen saying so. */
    private fun deviceState(): String {
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${power.currentThermalStatus}" else "-"
        return "thermal $thermal low power ${power.isPowerSaveMode} memory ${Debug.getPss() / 1_024}MB"
    }

    private fun utcOffsetAt(at: Double): Int = TimeZone.getDefault().getOffset((at * 1_000).toLong()) / 1_000

    /** Hides the saving-failed banner until saving works and then fails again. */
    fun dismissSavingTrouble() {
        savingTrouble.dismiss()
        refreshSavingTrouble()
    }

    private fun refreshSavingTrouble() {
        savingTrouble.update(
            settingsFailed = settings.saveFailed.value,
            historyFailed = settings.current.value.saveHistory && conversations.lastSaveFailure != null,
        )
        CaptionState.savingTrouble(savingTrouble.shouldShow)
    }

    /** What was said since the last autosave is written first, so a conversation opened from the captions has every line. */
    fun saveBeforeReading() = conversations.save(ended = false)

    /** Her name, typed: it buzzes when said, and goes first on the names list so every engine expects it, as on the iPhone. */
    fun addNameAlert(name: String) {
        addAlert(name)
        addWord(name)
    }

    fun addAlert(phrase: String) = setImportantWords(ImportantWords.adding(phrase, settings.current.value.keywordAlerts))

    fun dismissNameAlertOffer() = settings.change { it.nameAlertOfferDismissed = true }

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
        TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.AppleSpeech -> modelFile(settings).let { model ->
            OnDeviceWhisperEngine(
                modelName = model.name,
                loader = ModelFileLoader(model, context.applicationInfo.nativeLibraryDir),
                voiceScorerFactory = ::voiceScore,
            )
        }
    }

    /** The phone's own model for the spoken language; see `WhisperModelCatalog.phoneModelFile`. */
    private fun modelFile(settings: AppSettings) = File(context.filesDir, WhisperModelCatalog.phoneModelFile(settings.spokenLanguage))

    private fun voiceScore(): ((FloatArray) -> Float?)? {
        val scorer = voiceScorer ?: return null
        scorer.reset()
        return { input -> scorer.score(input) }
    }

    private object NoVoicePrints : SpeakerEmbedding {
        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = null
    }

    companion object {
        val MODEL_FILE = WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew)
        const val HISTORY_FOLDER = "ozen-history"
        const val JOURNAL_FILE = "ozen-journal.log"
        const val PROBLEM_AUDIO_FOLDER = "problem-audio"
        private const val SAMPLE_RATE = 16_000

        /** In the cache: the conversation last shared as a file, kept for the app it went to. */
        const val SHARED_FOLDER = "shared"
    }
}
