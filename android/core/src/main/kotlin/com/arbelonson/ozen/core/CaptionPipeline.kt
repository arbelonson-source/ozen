package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.MathContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The live pipeline: microphone permission, audio session and input list,
 * engine preparation (with progress), capture, tokens fanned into caption
 * segments and speaker clusters. Every step lands in [phase], in order, so
 * the screen always reflects what's actually happening.
 *
 * Why the order matters: the first build prepared the engine *before*
 * touching audio, which meant the mic picker sat empty and the status
 * said "not checked" for the whole multi-minute Whisper download. Here the
 * audio session (cheap) comes first, so microphones are listed within a
 * second of launch, and the slow engine step reports progress the whole
 * time.
 *
 * Portable on purpose: every dependency is an interface, so this entire
 * sequence, including failure paths and engine hot-swapping, is unit
 * tested on the JVM with fakes before it ever meets a real device.
 *
 * Confinement: everything here belongs to one context, the dispatcher of
 * [scope] (the main thread in the app, a test dispatcher in tests). Every
 * public member must be used from it, and the callbacks the audio layer and
 * the network monitor hold must be invoked on it. The jobs this class starts
 * run in [scope]; [stop] and its relatives cancel them as the Swift tasks
 * were cancelled.
 */
class CaptionPipeline(
    private val scope: CoroutineScope,
    private val audio: AudioCapturing,
    private val engineFactory: (AppSettings) -> TranscriptionEngine,
    private val embedder: SpeakerEmbedding,
    private val soundDetector: SoundEventDetecting? = null,
    soundPolicy: SoundEventPolicy = SoundEventPolicy(),
    clusterer: EmbeddingClusterer = EmbeddingClusterer(),
    stabilizer: CaptionStabilizer = CaptionStabilizer(),
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy(),
    audioWatchdog: AudioStallWatchdog = AudioStallWatchdog(),
    private val network: NetworkMonitoring? = null,
    private val availableStorageBytes: (() -> Long?)? = null,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val embedderContext: CoroutineContext = Dispatchers.Default,
) {
    var phase: PipelinePhase = PipelinePhase.Idle
        private set(value) {
            val old = field
            field = value
            // Download progress moves many times a second; only a change
            // of step is news.
            noteStep(old)
            if (old.preparationProgress != null && value.preparationProgress != null) return
            onPhaseChange?.invoke(value)
        }

    var segments: List<TranscriptSegment> = emptyList()
        private set
    var availableInputs: List<AudioInputDescriptor> = emptyList()
        private set
    var selectedInputUID: String? = null
        private set
    var activeEngineKind: TranscriptionEngineKind? = null
        private set
    var speakerClusters: List<SpeakerCluster> = emptyList()
        private set

    private var statsData = PipelineStats()

    val stats: PipelineStats get() = statsData.copy()

    /**
     * Lines finished since launch, as `stats.segmentsCommitted`, but
     * observed on its own: `stats` changes with every chunk of audio, and
     * a caption screen that read it to hear of finished lines was drawn
     * again ten or more times a second, silence included. It also moves
     * when a line finished on the stale-commit guess finishes again (see
     * [noteFinished]), which `segmentsCommitted` counts once.
     */
    var committedLineCount = 0
        private set

    /** When listening last began, as `stats.sessionStartedAt`, observed on its own for the same reason. */
    var listeningStartedAt: Double? = null
        private set

    /** Alert sounds heard too faintly to alert, for the diagnostics report. */
    var soundNearMisses = SoundNearMisses()
        private set

    /** The classifier confidence a sound needs to raise an alert. */
    val soundAlertConfidence: Double get() = soundPolicy.minimumConfidence

    /** Failures, retries and recoveries in order, for the diagnostics report. */
    var eventLog = PipelineEventLog()
        private set

    /** Called with each event as it is kept, for the journal on disk. */
    var onEvent: ((PipelineEvent) -> Unit)? = null
    private var stepBeganAt: Double? = null

    /**
     * Keywords the reader asked to be told about, as they're spotted in
     * captions. Each entry fires once per utterance (partial updates of
     * the same sentence don't re-fire), newest last, capped so a long
     * evening never grows this without bound.
     */
    var keywordHits: List<KeywordHit> = emptyList()
        private set

    /** Segments that contain at least one keyword hit, for highlighting. */
    var keywordHitSegmentIDs: Set<UUID> = emptySet()
        private set

    /** A voice sample for a speaker profile is being recorded. */
    var isRecordingVoice = false
        private set

    /**
     * An external microphone went away mid-conversation and the phone's
     * own took over: said on screen, since captions get worse quietly.
     */
    var microphoneDrop = MicrophoneDropNotice()
        private set
    private var lastSelectedInput: AudioInputDescriptor? = null

    /** Doorbell/siren/kettle alerts that passed [soundPolicy], newest last. */
    var soundAlerts: List<SoundAlert> = emptyList()
        private set

    /**
     * The alert the caption screen shows, buzzes for and reads out: the
     * newest, except that a weaker label from the same classifier reading
     * (a smoke alarm also scored as an alarm clock) doesn't take the
     * stronger one's place. Dismissing a banner leaves it as it is, so the
     * alert before it isn't replayed as if it had just been heard.
     */
    var screenSoundAlert: SoundAlert? = null
        private set
    private var screenSoundAlertRaisedAt: Double? = null

    /**
     * The alert whose banner the screen shows. A lesser sound heard while
     * it lasts is [screenSoundAlert] (it buzzes and is read out) but
     * doesn't take the banner (`SoundAlert.takesBanner`): closing a
     * covering screen handed the banner to the kettle, not the alarm.
     */
    var bannerSoundAlert: SoundAlert? = null
        private set
    private var bannerSoundAlertRaisedAt: Double? = null

    /** Tunable from Settings without a restart. */
    var soundPolicy: SoundEventPolicy = soundPolicy
    private var soundsIgnoredFrom: Double = 0.0
    private var soundsIgnoredUntil: Double = 0.0

    private var settingsInUse: AppSettings? = null

    /**
     * The settings the running (or last-run) session was started with.
     * Engine/model/language changes need a restart; input changes don't.
     */
    val activeSettings: AppSettings? get() = settingsInUse?.copy()

    /**
     * Cloud captions stopped for something only a person can fix (no
     * key, no credit) or no internet, and the phone's own model, already
     * downloaded, took over. Lasts until captions are next started with
     * the chosen settings; the saved choice itself is never changed.
     */
    var isCoveringForCloud = false
        private set

    /**
     * Why the phone's model took over, while it covers: a refused home
     * server pairing code needs someone to re-enter it, an unreachable
     * server doesn't.
     */
    var coverReason: EngineUnavailability.Kind? = null
        private set

    /**
     * While the phone covers for a home computer it couldn't reach, how
     * often to look whether the computer is back. Without it a single
     * dropped connection kept a phone that is never stopped on its own
     * model for good.
     */
    var homeServerRecheckSeconds: Double = 60.0

    /**
     * With no backup on the phone, captions stop when the home computer
     * can't be reached, and once the quick retries run out it is asked
     * this often. Shorter than the switch-back minute: here nothing is
     * being captioned meanwhile, and a computer that woke up just after a
     * check left her without captions for most of a minute more (44 s in
     * a drill with the real server). A check is one connection attempt.
     */
    var homeServerWaitSeconds: Double = 15.0

    /**
     * While the phone covers for the cloud after losing the internet, how
     * often to look whether it can be reached again. Without it, a single
     * dropped connection kept a phone that is never stopped on its own
     * model for good (the weaker, more battery-hungry choice) until
     * someone restarted the app.
     */
    var cloudRecheckSeconds: Double = 60.0

    /** How long a download waits for the system's first word on the network. */
    var networkFirstReportWaitSeconds: Double = 2.0

    /**
     * Only switch back after this long without new words or speech, so a
     * sentence isn't cut in half.
     */
    var homeServerSwitchBackQuietSeconds: Double = 2.0

    /**
     * What the phone's model waits through while it loads, when it is not
     * a first set-up, before the screen says it can take a few minutes:
     * 15 seconds. A test holds it until it has seen the plain "loading",
     * which a busy machine could otherwise sleep straight through.
     */
    var slowLoadWait: suspend () -> Unit = { delay(15.seconds) }

    /**
     * How long a download may send no progress before its time left is
     * taken off the screen: at least this long, and [downloadQuietGaps]
     * times its usual gap between reports, so a slow connection that
     * reports every 20 seconds doesn't make it flicker. At the end of the
     * recommended model's download the files are checked and compiled
     * with no progress for a minute or more, and the last estimate, "less
     * than a minute left", stayed on screen all that time.
     */
    var downloadQuietSeconds: Double = 30.0
    var downloadQuietGaps: Double = 3.0

    /**
     * After this many checks in a row found it back, switch at the next
     * finished line even without a quiet moment: a TV or a lively table
     * may never go quiet for long, and every minute on the phone's own
     * model is a minute of weaker captions.
     */
    var switchBackAfterAnsweredChecks = 3

    /**
     * That switch still waits for a breath this long since the last
     * speech: the phone's model writes nothing for the first second or
     * so of a sentence, and switching then loses its start.
     */
    var switchBackBreathSeconds: Double = 0.4

    /**
     * How long a breath is looked for after a check that allows that
     * switch, before waiting for the next check a minute later.
     */
    var switchBackBreathWaitSeconds: Double = 10.0

    /**
     * A computer that answers the check but drops again soon after
     * captions went back to it (one that hangs on audio stalls for 35 s
     * first) would otherwise be switched to every minute, each time
     * costing her half a minute of captions. Every drop within this long
     * of switching back doubles the wait before the next try, up to 16
     * times; a drop after a good stretch starts over.
     */
    var homeServerFlapWindowSeconds: Double = 300.0
    private var homeServerSwitchedBackAt: TimeMark? = null
    private var homeServerFlaps = 0
    val currentHomeServerRecheckSeconds: Double
        get() = homeServerRecheckSeconds * (1 shl homeServerFlaps).toDouble()

    /**
     * The same for the cloud: a connection that answers the check but
     * drops again soon after switching back doubles the wait, up to 16x.
     */
    var cloudFlapWindowSeconds: Double = 300.0
    private var cloudSwitchedBackAt: TimeMark? = null
    private var cloudFlaps = 0
    val currentCloudRecheckSeconds: Double
        get() = cloudRecheckSeconds * (1 shl cloudFlaps).toDouble()
    private var coveredSettings: AppSettings? = null

    /**
     * The last half-minute of microphone sound, in memory only, so that
     * "mark a problem" can keep what was actually heard. Cleared when
     * captions stop.
     */
    private val recentAudio = RecentAudio(seconds = 30.0, sampleRate = 16_000.0)
    val recentAudioSamples: FloatArray get() = recentAudio.samples()
    private var homeServerRecheck: Job? = null
    private var cloudRecheck: Job? = null

    /**
     * The room the last download refused for want of space needed, so a
     * return to the app only retries once that much is free.
     */
    private var storageNeededMegabytes: Int? = null
    private var nextStartCoversCloud = false

    /** Set while a failure is waiting to be retried automatically. */
    var scheduledRetry: ScheduledRetry? = null
        private set

    /**
     * The failure a model on the phone is getting ready to cover, from
     * the moment the failure is shown until the cover starts or won't.
     */
    private var pendingCover: PipelineFailure? = null

    /**
     * Captions stopped, but a retry or the phone's own model is on its
     * way: no reason to tell her they stopped.
     */
    val isRecoveringByItself: Boolean get() = scheduledRetry != null || pendingCover != null

    /**
     * How long the model download has left at its current pace, while
     * one runs and there's enough to go on (see `DownloadEstimator`).
     */
    var downloadSecondsRemaining: Double? = null
        private set

    /** When the preparation progress on screen was last replaced. */
    private var progressShownAt: Double = Double.NEGATIVE_INFINITY
    private var downloadEstimator = DownloadEstimator()
    private var downloadQuiet: Job? = null

    /**
     * Called for every new sound alert the screen takes up, e.g. to post a
     * notification while the app isn't on screen: not for a weaker label
     * of the same reading, so a smoke alarm also scored as an alarm clock
     * is one notification, not a second naming the clock.
     */
    var onSoundAlert: ((SoundAlert) -> Unit)? = null

    /** Called with the fresh keyword hits in a line, and the line itself. */
    var onKeywordHits: ((List<KeywordHit>, TranscriptSegment) -> Unit)? = null

    /**
     * Called when [phase] moves to another step (not for each bit of
     * download progress). Runs as the phase is set, before the pipeline
     * has finished reacting to it: a failure's retry, for one, is lined up
     * just after.
     */
    var onPhaseChange: ((PipelinePhase) -> Unit)? = null

    /**
     * A caption line was added or changed, or the transcript was cleared.
     * For what follows the captions outside the app's own screen (the
     * lock screen).
     */
    var onCaptionsChanged: (() -> Unit)? = null

    val inputLevel: Float get() = audio.inputLevel

    /** The connection as last reported, for diagnostics; null when unknown. */
    val networkConditions: NetworkConditions? get() = network?.current

    /** The engine of the current run, so vocabulary edits reach it live. */
    private var currentEngine: TranscriptionEngine? = null
    private var keywordMatcher = KeywordAlertMatcher(emptyList())
    private var keywordDeduplicator = KeywordAlertDeduplicator()
    private var clusterer: EmbeddingClusterer = clusterer
    private var stabilizer: CaptionStabilizer = stabilizer
    private val engineCache = LinkedHashMap<String, TranscriptionEngine>()
    private val clearedUtterances = HashMap<UUID, List<String>>()

    private class ClearedTurnCarry(val timestamp: Double, val words: List<String>)

    /**
     * The cloud finishes a sentence as one line per speaker, each after
     * the first under a new id. The cleared words the first line did not
     * use belong to the next speaker's line of the same finished sentence.
     */
    private var clearedTurnCarry: ClearedTurnCarry? = null

    /**
     * Each sentence still being said, as the engine last sent it, before
     * any cleared words were cut from it.
     */
    private val incomingText = HashMap<UUID, String>()
    private var fanOut: FlowAudioFanOut? = null
    private var streamTask: Job? = null
    private var embeddingTask: Job? = null
    private var soundTask: Job? = null

    /**
     * The microphone is on for sound alerts alone, while captions wait to
     * come back (see [listenForSoundsMeanwhile]). The phone's battery runs
     * down then too, so its warnings stay on.
     */
    var isListeningForSoundsOnly = false
        private set
    private var staleCommitTask: Job? = null
    private var utteranceClusterAssignments = HashMap<UUID, Int>()

    /**
     * Decides whether an embedding window holds a voice at all. Silence
     * and background noise must not open phantom speakers or drag a real
     * person's voice profile toward the fridge hum.
     */
    private var embeddingVoiceDetector = EnergyVoiceDetector()

    /**
     * When the microphone last heard speech, words or not: the phone's
     * model writes a sentence's first word a second or two after it
     * began, and a switch back in that gap lost the sentence's start.
     */
    private var lastSpeechAt: Double? = null
    private var silencePhraseGuard = SilencePhraseGuard()

    private class RecentSpeechCluster(val id: Int, val at: Double)

    /**
     * The speaker of the most recent window that held speech while no line
     * was open. A short reply ("ken", yes) is often over before its
     * caption line exists, so a new line with no speaker yet takes this one
     * if it is recent.
     */
    private var recentSpeechCluster: RecentSpeechCluster? = null

    /**
     * Every `start()` gets a fresh run id; async continuations from an
     * earlier run (a progress callback arriving after a restart, say)
     * compare against it and drop themselves instead of clobbering state.
     */
    private var runID: UUID = UUID.randomUUID()

    /**
     * Set for as long as `engine.prepare()` is actually running, even
     * after `stop()`/`restart()` abandons that run: `prepare()` has no way
     * to cancel a model download or load already under way, so it keeps
     * using memory until it finishes on its own. A new `start()` waits for
     * it before beginning its own (two multi-hundred-megabyte models
     * loading at once is exactly the kind of memory pressure iOS kills
     * apps over) and then goes ahead, so a restart still restarts.
     *
     * While it waits the screen says the model is loading (a start that
     * just sat there looked like a tap that did nothing, for as long as a
     * stuck download took), and a stop in the meantime is kept: the
     * waiting start gives up instead of starting captions she stopped.
     */
    private var isPreparingEngine = false

    private class Preparing(val variant: String, val engine: TranscriptionEngine)

    /** The model the phone is preparing, and its engine, while it does. */
    private var preparing: Preparing? = null
    private val preparationWaiters = ArrayList<CompletableDeferred<Unit>>()

    /**
     * The newest start waiting on an abandoned load. Two model switches in
     * a row both waited, and the older one, a model she had already
     * switched away from, went ahead while her last choice was dropped.
     */
    private var newestWaitingStart: UUID? = null

    /**
     * What a start waiting on an abandoned preparation shows: "loading"
     * at first, then that preparation's own progress (see `start`).
     */
    private var waitingShown: EnginePreparationProgress? = null
    private var stopCount = 0
    private var recovery: AutoRecoveryPolicy = recovery.copy()

    /** Notices capture that died while the screen still says "listening". */
    private var audioWatchdog: AudioStallWatchdog = audioWatchdog.copy()
    private var retryTask: Job? = null
    private var retryToken: UUID? = null
    private var listeningSince: Double? = null

    /**
     * A phone call (or another app) holds the audio session. Retrying
     * then would only use up attempts; recovery waits for it to end.
     */
    private var systemInterrupted = false

    /**
     * A retry that came due while a voice sample was recording, with the
     * settings it was asked with; it runs when the recording ends.
     */
    private class HeldRetry(val settings: AppSettings?)

    private var retryAfterRecording: HeldRetry? = null
    private var restartAfterRecording: AppSettings? = null

    /** The person said this session's model may download over cellular. */
    private var cellularDownloadApproved = false
    private var lastNetwork: NetworkConditions? = null
    private var networkRetryTask: Job? = null
    private var microphoneRetryTask: Job? = null

    /**
     * The run whose microphone opened before its model finished loading
     * (a takeover), so a microphone the phone gives up on meanwhile ends
     * it then instead of showing "Listening" on nothing after the load.
     */
    private var earlyCaptureRun: UUID? = null
    private var lastMicrophoneChangeRetryAt: Double? = null

    init {
        lastNetwork = network?.current
        network?.onChange = { conditions -> networkConditionsChanged(conditions) }
    }

    // region Lifecycle

    /**
     * Asks for the microphone up front (the onboarding walkthrough does
     * this on its own page, with an explanation, instead of the system
     * prompt ambushing the user on top of a black screen).
     */
    suspend fun requestMicrophonePermission(): AudioPermission = audio.requestPermission()

    suspend fun start(settings: AppSettings) {
        if (phase.isListening || phase.isTransitioning || isRecordingVoice) return
        // Only a second model loading on the phone is worth waiting for: the
        // home computer, the cloud and Apple's recognizer load nothing here,
        // and waiting kept captions on "loading model" after the home
        // computer's address was entered during a load, until the app was
        // closed.
        if (isPreparingEngine && settings.engine == TranscriptionEngineKind.WhisperKit) {
            val stops = stopCount
            val mine = UUID.randomUUID()
            newestWaitingStart = mine
            val waiting = EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)
            waitingShown = waiting
            phase = PipelinePhase.PreparingEngine(waiting)
            val slowWait = scope.launch { sayWaitIsSlow(mine) }
            // A download of a model no longer chosen held this start up for
            // as long as it had left, minutes on a slow connection, before
            // the new choice even began. A download holds no model in
            // memory, so it is stopped; a model already loading is still
            // waited for.
            val inFlight = preparing
            if (inFlight != null && inFlight.variant != settings.whisperModelVariant) {
                val abandoned = inFlight.engine
                scope.launch { abandoned.cancelDownload() }
            }
            while (isPreparingEngine) {
                val waiter = CompletableDeferred<Unit>()
                preparationWaiters.add(waiter)
                waiter.await()
            }
            slowWait.cancel()
            val shown = waitingShown ?: waiting
            if (newestWaitingStart == mine) waitingShown = null
            if (stopCount != stops || newestWaitingStart != mine || phase != PipelinePhase.PreparingEngine(shown)) return
            phase = PipelinePhase.Idle
        }
        cancelScheduledRetry()
        stopListeningForSounds()
        val run = UUID.randomUUID()
        runID = run
        settingsInUse = settings.copy()
        isCoveringForCloud = nextStartCoversCloud
        nextStartCoversCloud = false
        if (!isCoveringForCloud) {
            coverReason = null
            coveredSettings = null
            homeServerRecheck?.cancel()
            homeServerRecheck = null
            cloudRecheck?.cancel()
            cloudRecheck = null
        }
        storageNeededMegabytes = null
        // The stored threshold is the person's own choice once they've
        // touched it, but at the untouched app default it's specifically
        // calibrated for CAM++; a silent fallback to a different embedder
        // (see SpeakerEmbedding.recommendedSimilarityThreshold) needs its
        // own default instead of inheriting one tuned for a completely
        // different score scale.
        clusterer.similarityThreshold =
            if (settings.speakerSimilarityThreshold == AppSettings.default.speakerSimilarityThreshold) {
                embedder.recommendedSimilarityThreshold
            } else {
                settings.speakerSimilarityThreshold
            }
        keywordMatcher = KeywordAlertMatcher(settings.keywordAlerts)
        soundPolicy.preferences = settings.soundAlerts

        phase = PipelinePhase.RequestingMicrophonePermission
        val permission = audio.requestPermission()
        if (runID != run) return
        if (permission != AudioPermission.Granted) {
            fail(PipelineFailure.Kind.MicrophonePermissionDenied, "AVAudioApplication record permission denied")
            return
        }

        // Before the session, not after: a session refused at launch (a
        // hearing aid still connecting) left nothing to hear the aid
        // arrive, and captions stayed stopped until someone tapped Retry.
        audio.onInputsChanged = { inputsChanged() }
        try {
            audio.prepareSession(settings.preferredInputUID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (runID != run) return
            fail(PipelineFailure.Kind.AudioSessionFailed, describe(error))
            return
        }
        if (runID != run) return
        audio.onCaptureLost = { captureLost(run) }
        syncInputs()
        if (availableInputs.isEmpty()) {
            fail(PipelineFailure.Kind.NoAudioInputs, "AVAudioSession reported no available inputs")
            return
        }

        val engine = cachedEngine(settings)
        activeEngineKind = engine.kind
        phase = PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingSupport))
        // A download that failed earlier and is starting again is timed afresh.
        downloadEstimator.reset()
        downloadQuiet?.cancel()
        downloadSecondsRemaining = null
        val allowCellular = settings.allowCellularModelDownload || cellularDownloadApproved
        val megabytes = engine.pendingDownloadMegabytes()
        if (megabytes != null) {
            if (runID != run) return
            // Right after launch the system may not have said yet whether
            // this is Wi-Fi; hundreds of megabytes are worth a short wait
            // for the answer rather than starting on a phone plan.
            if (network != null && network.current == null && !allowCellular) {
                val deadline = timeSource.markNow() + networkFirstReportWaitSeconds.seconds
                while (network.current == null && deadline.hasNotPassedNow()) {
                    delay(50.milliseconds)
                }
                if (runID != run) return
            }
            when (ModelDownloadGate.decide(network?.current, allowCellular)) {
                ModelDownloadGate.Decision.Proceed -> Unit
                ModelDownloadGate.Decision.WaitForWiFi -> {
                    fail(
                        PipelineFailure.Kind.EngineUnavailable,
                        "$megabytes MB to download, waiting for Wi-Fi",
                        EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "cellular or Low Data Mode", megabytes),
                    )
                    return
                }
                ModelDownloadGate.Decision.Offline -> {
                    fail(
                        PipelineFailure.Kind.EngineUnavailable,
                        "$megabytes MB to download, no internet connection",
                        EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "offline", megabytes),
                    )
                    return
                }
            }
            // The room it needs, not only the download: a model compiled on
            // the phone needs about twice its download for a while, and
            // checking the download alone let one start that couldn't finish.
            val neededMegabytes = engine.pendingInstallMegabytes() ?: megabytes
            if (runID != run) return
            val missing = storageShortfall(neededMegabytes)
            if (missing != null) {
                storageNeededMegabytes = neededMegabytes
                fail(
                    PipelineFailure.Kind.EngineUnavailable,
                    "$megabytes MB to download, $missing MB more free space needed",
                    EngineUnavailability(EngineUnavailability.Kind.NotEnoughStorage, "checked before download", megabytes, missing),
                )
                return
            }
        }
        // Taking over mid-conversation (the home computer or the cloud
        // dropped out), the phone's model can take seconds to load; the
        // microphone listens from now, and what is said meanwhile waits in
        // its stream for the model instead of being lost.
        val earlySource: Flow<FloatArray>? = if (isCoveringForCloud) startCaptureOrNull() else null
        earlyCaptureRun = if (earlySource == null) null else run
        val loadsModelOnPhone = settings.engine == TranscriptionEngineKind.WhisperKit
        if (loadsModelOnPhone) {
            isPreparingEngine = true
            preparing = Preparing(settings.whisperModelVariant, engine)
        }
        val slowLoad = if (loadsModelOnPhone) scope.launch { sayLoadIsSlow(run) } else null
        // Checked above only as the download starts: without this, a
        // download that began on Wi-Fi went on over the phone plan when
        // Wi-Fi dropped between two of the model's files.
        engine.setCellularDownloadAllowed(allowCellular)
        val availability = engine.prepare(settings.languageCode) { progress ->
            scope.launch {
                // Abandoned, with a later start waiting for it to finish:
                // that start shows what is really happening (the rest of a
                // download, a first set-up) instead of "loading" for as
                // long as it takes.
                val waiting = waitingShown
                if (runID != run && waiting != null && phase == PipelinePhase.PreparingEngine(waiting)) {
                    val time = now()
                    if (!progress.isNews(waiting, progressShownAt, time)) return@launch
                    progressShownAt = time
                    waitingShown = progress
                    phase = PipelinePhase.PreparingEngine(progress)
                    trackDownload(progress, time)
                    return@launch
                }
                val current = phase
                if (runID != run || current !is PipelinePhase.PreparingEngine) return@launch
                val time = now()
                if (!progress.isNews(current.progress, progressShownAt, time)) return@launch
                progressShownAt = time
                phase = PipelinePhase.PreparingEngine(progress)
                trackDownload(progress, time)
            }
        }
        slowLoad?.cancel()
        if (loadsModelOnPhone) {
            isPreparingEngine = false
            preparing = null
            val waiting = preparationWaiters.toList()
            preparationWaiters.clear()
            waiting.forEach { it.complete(Unit) }
        }
        if (runID != run) return
        if (availability is EngineAvailability.Unavailable) {
            var why = availability.why
            // Refused because the only connection left is cellular: the
            // same wait for Wi-Fi as before a download, with its "download
            // now anyway", rather than a failed download. Only while there
            // is still something to download (a failure with the model all
            // there, say fetching its tokenizer, keeps its retry timer), and
            // decided after asking, from the connection and the cellular
            // switch as they are now: Wi-Fi back by then, or cellular
            // downloads switched on meanwhile, is no reason to wait.
            if (why.kind == EngineUnavailability.Kind.ModelDownloadFailed) {
                val pending = engine.pendingDownloadMegabytes()
                if (runID != run) return
                val allowCellularNow = (settingsInUse?.allowCellularModelDownload ?: allowCellular) || cellularDownloadApproved
                if (pending != null &&
                    ModelDownloadGate.decide(network?.current, allowCellularNow) == ModelDownloadGate.Decision.WaitForWiFi
                ) {
                    why = EngineUnavailability(
                        EngineUnavailability.Kind.WaitingForWiFi,
                        "cellular or Low Data Mode after: ${why.detail}",
                        pending,
                    )
                }
            }
            fail(PipelineFailure.Kind.EngineUnavailable, why.detail, why)
            return
        }
        engine.setVocabulary(primedVocabulary(settings.vocabulary))
        if (runID != run) return
        currentEngine = engine

        phase = PipelinePhase.StartingAudio
        val source: Flow<FloatArray>
        if (earlySource != null) {
            source = earlySource
        } else {
            try {
                source = audio.startCapture()
            } catch (error: Exception) {
                fail(PipelineFailure.Kind.AudioSessionFailed, describe(error))
                return
            }
        }

        val fan = FlowAudioFanOut(scope, source, count = if (soundDetector == null) 2 else 3) {
            if (runID == run) statsData.glitchedAudioChunks += 1
        }
        fanOut = fan
        val tokens = engine.stream(settings.languageCode, fan.outputs[0])
        val embedderAudio = fan.outputs[1]
        val soundObservations = soundDetector?.observations(fan.outputs[2])

        statsData.sessionStartedAt = now()
        listeningStartedAt = statsData.sessionStartedAt
        phase = PipelinePhase.Listening
        logEvent(PipelineEvent.Kind.Listening)
        listeningSince = now()
        audioWatchdog.reset()

        embeddingTask = scope.launch {
            consumeEmbeddings(embedderAudio, run)
        }

        if (soundObservations != null) {
            statsData.soundDetectionRunning = true
            soundTask = scope.launch {
                soundObservations.takeWhile { runID == run }.collect { observation ->
                    handleSoundObservation(observation)
                }
                // The classifier's flow can end on its own (the request
                // failed); captions carry on, but diagnostics should say
                // sound alerts are off rather than let them look armed.
                if (runID != run || !isActive) return@launch
                statsData.soundDetectionRunning = false
            }
        }

        streamTask = scope.launch {
            var knownFailure: EngineUnavailability? = null
            var stopReason: String? = null
            try {
                tokens.takeWhile { runID == run }.collect { token -> handleToken(token) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudSpeechError) {
                knownFailure = error.unavailability
                stopReason = describe(error)
            } catch (error: EngineUnavailability) {
                knownFailure = error
                stopReason = error.detail
            } catch (error: Exception) {
                stopReason = describe(error)
            }
            // Reaching here while still "listening" means the engine gave up
            // on its own (recognizer error, model crash) while audio is
            // still flowing. That's a failure the user should see and be
            // able to retry, not a silent stop.
            if (runID != run || !phase.isListening) return@launch
            // A mid-stream failure the engine already named (a rejected
            // key, no credit, the home server gone) -- reporting it
            // generically would show the wrong message, let
            // AutoRecoveryPolicy auto-retry a problem only a person can fix,
            // and keep CloudCover from handing over to the phone.
            if (knownFailure != null) {
                fail(PipelineFailure.Kind.EngineUnavailable, stopReason ?: "engine stream ended", knownFailure)
            } else {
                fail(PipelineFailure.Kind.TranscriptionStopped, stopReason ?: "engine stream ended")
            }
        }

        staleCommitTask = scope.launch {
            while (isActive) {
                delay((AudioStallWatchdog.TICK_SECONDS).seconds)
                if (runID != run) return@launch
                commitStaleSegments()
                checkAudioIsArriving()
            }
        }
    }

    fun stop() {
        stopCount += 1
        recentAudio.clear()
        homeServerRecheck?.cancel()
        homeServerRecheck = null
        cloudRecheck?.cancel()
        cloudRecheck = null
        cancelScheduledRetry()
        // Stopped by hand: nothing held for a voice sample's end may start
        // captions again.
        retryAfterRecording = null
        restartAfterRecording = null
        recovery.reset()
        listeningSince = null
        microphoneDrop.dismiss()
        tearDownSession()
        phase = PipelinePhase.Idle
    }

    /**
     * The system is short of memory and ends the biggest apps first; a loaded
     * speech model makes this one of the biggest. The warning goes in the
     * diagnostics timeline, since an app the system ended leaves no other trace.
     * With captions not actually running -- idle, paused waiting to be
     * resumed, or failed with no retry coming -- the engine kept loaded
     * for a quick start or resume is let go too: the next one spends a
     * few seconds loading the model again, where the system ending the app
     * instead would lose the conversation already on screen. Only while
     * captions are running does it stay.
     */
    fun handleMemoryWarning(footprintBytes: Long? = null) {
        logEvent(PipelineEvent.Kind.MemoryWarning(footprintBytes?.let { (it / StorageSpaceGate.BYTES_PER_MEGABYTE).toInt() }))
        val current = phase
        when {
            current == PipelinePhase.Idle || current == PipelinePhase.Paused -> engineCache.clear()
            current is PipelinePhase.Failed && scheduledRetry == null -> engineCache.clear()
            else -> Unit
        }
    }

    fun pause() {
        if (!phase.isListening) return
        listeningSince = null
        tearDownSession()
        phase = PipelinePhase.Paused
    }

    /**
     * [settings] defaults to the snapshot from the last `start(settings)`,
     * but a caller that tracks its own live settings (the app's view
     * model) should pass its current value: any engine, model, keyword
     * alert, sound preference, or speaker threshold change made while
     * paused would otherwise vanish on resume, silently restarting with
     * whatever was in effect before the pause.
     */
    suspend fun resume(settings: AppSettings? = null) {
        if (phase != PipelinePhase.Paused || isRecordingVoice) return
        val effective = (settings ?: settingsInUse)?.copy() ?: return
        // A pause is not a new start: the phone's model that was covering
        // for the cloud or the home computer (the caller's settings still
        // say so) carries on, rather than trying it again with nothing
        // buffered and then reloading the model. The recheck brings it
        // back once it answers.
        keepCovering(effective)
        phase = PipelinePhase.Idle
        start(effective)
    }

    /**
     * Starting again while the phone's model covers (a resume, or a retry
     * after the covering model itself failed) goes on covering: the
     * recheck keeps trying what was chosen. Started from the phone's own
     * settings, as a retry or a plain resume is, the cover was dropped
     * and the home computer or the cloud was never tried again.
     */
    private fun keepCovering(settings: AppSettings) {
        if (!isCoveringForCloud) return
        if (settings.engine == TranscriptionEngineKind.Cloud || settings.engine == TranscriptionEngineKind.HomeServer) {
            settings.engine = TranscriptionEngineKind.WhisperKit
        }
        nextStartCoversCloud = true
    }

    /**
     * Her settings changed while captions were paused (the home computer's
     * address or code, the engine, a model). The phone's model covering
     * for the computer or the cloud is let go, so resuming tries what she
     * chose now: kept, the cover carried on and its recheck kept trying
     * the address from before the change, never switching over.
     */
    fun settingsChangedWhilePaused() {
        if (phase != PipelinePhase.Paused || !isCoveringForCloud) return
        isCoveringForCloud = false
        nextStartCoversCloud = false
        coverReason = null
        coveredSettings = null
        homeServerRecheck?.cancel()
        homeServerRecheck = null
        cloudRecheck?.cancel()
        cloudRecheck = null
    }

    /**
     * Stops and starts again with new settings: the engine, model, or
     * language changed. The transcript is kept; a switch mid-conversation
     * shouldn't wipe what was already read.
     */
    suspend fun restart(settings: AppSettings) {
        // A voice sample holds the microphone: tearing the session down cut
        // it short, and `start` then refused to run, leaving captions idle
        // with nothing to bring them back. The restart waits for it.
        if (isRecordingVoice) {
            restartAfterRecording = settings.copy()
            return
        }
        cancelScheduledRetry()
        recovery.reset()
        tearDownSession()
        phase = PipelinePhase.Idle
        statsData.engineRestarts += 1
        start(settings)
    }

    /**
     * Starts again after a failure. Anything but a failure is left alone:
     * retrying in the middle of a start would begin a second model
     * download or load on the same engine while the first is still going.
     * See [resume]: defaults to the last-started snapshot, but a caller
     * with its own live settings should pass the current value so a change
     * made while failed isn't silently dropped on retry.
     */
    suspend fun retry(settings: AppSettings? = null) {
        if (phase !is PipelinePhase.Failed) return
        val effective = (settings ?: settingsInUse)?.copy() ?: return
        // A voice sample holds the microphone. Tearing the session down cut
        // it short, and `start` then refused to run, so captions stayed
        // stopped with no retry left to bring them back.
        if (isRecordingVoice) {
            retryAfterRecording = HeldRetry(settings?.copy())
            return
        }
        cancelScheduledRetry()
        tearDownSession()
        keepCovering(effective)
        // Straight from the failure to starting, never through .idle, which
        // means stopped on purpose (the "captions came back" announcement
        // forgets the failure there).
        start(effective)
    }

    /**
     * The Retry button. A person asked, so automatic recovery starts over
     * too: the attempts were used up (that is why captions stopped), and
     * a glitch in the first minute after the tap found none left, so
     * captions stopped again until someone noticed.
     */
    suspend fun retryAfterTap(settings: AppSettings? = null) {
        if (phase !is PipelinePhase.Failed) return
        recovery.reset()
        retry(settings)
    }

    fun clearTranscript() {
        segments = emptyList()
        try {
            // The engine keeps sending the sentence being said, each time with
            // all of its words so far; to a fresh stabilizer it looked new, and
            // the words from before "Delete all captions from the screen" came
            // straight back. The words each such line has sent so far are
            // remembered so only what is said after the tap shows (see
            // `handleToken`); a second clear in the same sentence remembers
            // the whole sentence again, which already holds the first one's.
            val stillChanging = stabilizer.stillChangingIDs
            for (segment in stabilizer.segments) {
                if (segment.id !in stillChanging) continue
                val sent = comparableWords(incomingText[segment.id] ?: segment.text)
                val before = clearedUtterances[segment.id]
                if (before == null) {
                    clearedUtterances[segment.id] = sent
                    continue
                }
                // The engine's last version can be shorter than what the screen
                // shows (it took a word back, and that version, all cleared
                // words, was ignored): then the words cleared before and those
                // shown since are what this clear takes away.
                val shown = comparableWords(segment.text)
                clearedUtterances[segment.id] = if (sent.takeLast(shown.size) == shown) sent else before + shown
            }
            incomingText.keys.retainAll(stillChanging)
            stabilizer = CaptionStabilizer(silenceCommitThreshold = stabilizer.silenceCommitThreshold)
            startNewConversation()
            keywordHits = emptyList()
            keywordHitSegmentIDs = emptySet()
            keywordDeduplicator.forgetAll()
        } finally {
            onCaptionsChanged?.invoke()
        }
    }

    // endregion

    // region Alerts

    /**
     * Replaces the keyword list without a restart; the deduplicator is
     * reset so a newly added word can fire on a sentence still pending.
     * Also re-primes the running engine, since a word added, removed or
     * toggled here changes which alert phrases belong in its hint list.
     */
    fun setKeywordAlerts(alerts: List<KeywordAlert>) {
        keywordMatcher = KeywordAlertMatcher(alerts)
        settingsInUse?.keywordAlerts = alerts
        // Switching back from a cover starts again from what was covered:
        // without this, a word added meanwhile stopped firing.
        coveredSettings?.keywordAlerts = alerts
        keywordDeduplicator.forgetAll()
        val userVocabulary = settingsInUse?.vocabulary ?: emptyList()
        scope.launch {
            val terms = primedVocabulary(userVocabulary)
            val engine = currentEngine
            if (engine == null || !(phase.isListening || phase == PipelinePhase.Paused)) return@launch
            engine.setVocabulary(terms)
        }
    }

    fun dismissSoundAlert(id: UUID) {
        soundAlerts = soundAlerts.filter { it.id != id }
        // Tapped away, an alarm no longer holds the banner: kept, a sound
        // heard after it never took the banner, and closing a covering
        // screen handed back nothing.
        if (bannerSoundAlert?.id == id) {
            val latest = if (screenSoundAlert?.id == id) null else screenSoundAlert
            bannerSoundAlert = latest
            bannerSoundAlertRaisedAt = if (latest == null) null else screenSoundAlertRaisedAt
        }
    }

    fun clearSoundAlerts() {
        soundAlerts = emptyList()
        screenSoundAlert = null
        screenSoundAlertRaisedAt = null
        bannerSoundAlert = null
        bannerSoundAlertRaisedAt = null
    }

    /**
     * [screenSoundAlert] while its banner time lasts, null after. The
     * caption screen only sees what changed while the app was away once
     * she comes back to it: an alert heard then was already a notification,
     * and buzzing, flashing or reading it out now would pass it off as new.
     */
    val currentScreenSoundAlert: SoundAlert?
        get() {
            val alert = screenSoundAlert ?: return null
            return if (bannerSecondsLeft(alert) > 0) alert else null
        }

    /** [bannerSoundAlert] while its banner time lasts, null after. */
    val currentBannerSoundAlert: SoundAlert?
        get() {
            val alert = bannerSoundAlert ?: return null
            return if (bannerSecondsLeft(alert) > 0) alert else null
        }

    /**
     * How long [alert]'s banner still has: the rest of its time when it is
     * the screen's alert, its whole time otherwise. A banner shown over a
     * sheet went with the sheet; shown again on the caption screen, a
     * siren's keeps the rest of its time to the next alert, and a banner
     * whose time is up isn't shown again.
     */
    fun bannerSecondsLeft(alert: SoundAlert): Double {
        val raisedAt = when (alert.id) {
            screenSoundAlert?.id -> screenSoundAlertRaisedAt
            bannerSoundAlert?.id -> bannerSoundAlertRaisedAt
            else -> null
        }
        val raised = raisedAt ?: return alert.bannerSeconds
        // A clock set back since would otherwise add the jump to the time left.
        return minOf(alert.bannerSeconds, maxOf(0.0, alert.bannerSeconds - (now() - raised)))
    }

    /**
     * Stops taking a buzz for a sound while the phone vibrates for an
     * alert, and for a moment after, since the classifier reports what it
     * heard a little late. A phone buzzing on a table is, to the
     * classifier, a phone ringing or an alarm clock: without this the
     * vibration raises an alert of its own, which vibrates again. Only
     * `SoundEventCatalog.vibrationLookalikes` are ignored; a siren, a smoke
     * alarm or the doorbell in the same moment still comes through.
     */
    fun ignoreSounds(whileVibrating: AlertVibration) {
        val start = now()
        val end = start + whileVibrating.totalSeconds + SOUND_REPORT_DELAY_SECONDS
        // A buzz still going on carries on; otherwise the window starts now.
        // Kept as a range: a clock set back an hour after a buzz otherwise
        // left phones ringing and knocks ignored until it caught up.
        if (start >= soundsIgnoredFrom && start < soundsIgnoredUntil) {
            soundsIgnoredUntil = maxOf(soundsIgnoredUntil, end)
        } else {
            soundsIgnoredFrom = start
            soundsIgnoredUntil = end
        }
    }

    private fun handleSoundObservation(observation: SoundObservation) {
        // Judged by when the classifier produced the reading, not when it
        // got here, and dropped before the policy, so the phone's own buzz
        // doesn't start a cooldown that would hide a real ring right after.
        if (observation.timestamp >= soundsIgnoredFrom && observation.timestamp < soundsIgnoredUntil &&
            SoundEventCatalog.vibrationLookalikes.contains(observation.identifier)
        ) {
            return
        }
        soundNearMisses.record(observation, soundPolicy.requiredConfidence(observation.identifier))
        val alert = soundPolicy.evaluate(observation) ?: return
        soundAlerts = soundAlerts + alert
        // A reading comes most important first, then surest: a later label
        // of it as urgent as the first is still the less likely one.
        val shownNow = screenSoundAlert
        val weakerInSameReading = shownNow != null &&
            shownNow.timestamp == alert.timestamp && shownNow.event.importance >= alert.event.importance
        if (!weakerInSameReading) {
            if (alert.takesBanner(currentBannerSoundAlert)) {
                bannerSoundAlert = alert
                bannerSoundAlertRaisedAt = now()
            }
            screenSoundAlert = alert
            screenSoundAlertRaisedAt = now()
            onSoundAlert?.invoke(alert)
        }
        if (soundAlerts.size > MAX_SOUND_ALERTS) {
            soundAlerts = soundAlerts.drop(soundAlerts.size - MAX_SOUND_ALERTS)
        }
    }

    private fun scanForKeywords(segment: TranscriptSegment) {
        val matches = if (segment.isCommitted) keywordMatcher.matches(segment.text) else keywordMatcher.matchesInLiveText(segment.text)
        if (matches.isEmpty()) {
            // The finished text can take back a word a live guess had: the
            // line's bell and highlight follow what the line says now.
            keywordHitSegmentIDs = keywordHitSegmentIDs - segment.id
            return
        }
        // Marked whenever it says the word, even when that isn't news: a
        // live guess can drop the word and the finished text bring it back.
        keywordHitSegmentIDs = keywordHitSegmentIDs + segment.id
        val fresh = keywordDeduplicator.newMatches(segment.id, matches)
        if (fresh.isEmpty()) return
        val timestamp = now()
        val hits = fresh.map { KeywordHit(segmentID = segment.id, match = it, timestamp = timestamp) }
        keywordHits = keywordHits + hits
        onKeywordHits?.invoke(hits, segment)
        if (keywordHits.size > MAX_KEYWORD_HITS) {
            keywordHits = keywordHits.drop(keywordHits.size - MAX_KEYWORD_HITS)
        }
    }

    // endregion

    // region Inputs

    /**
     * Switches to the input [uid]. Returns whether that input is the one
     * in use afterwards: the system can refuse it, or settle on another.
     */
    fun selectInput(uid: String): Boolean {
        settingsInUse?.preferredInputUID = uid
        coveredSettings?.preferredInputUID = uid
        try {
            audio.selectInput(uid)
            statsData.inputChanges += 1
            syncInputs()
            // Her own choice in the picker: the microphone she left was
            // not lost, and saying "disconnected" would be untrue.
            if (selectedInputUID == uid) microphoneDrop.dismiss()
        } catch (error: Exception) {
            // A failed switch leaves the previous input active, which is
            // strictly better than dropping a live conversation over a mic
            // the system refused. The caller says so on screen.
        }
        return selectedInputUID == uid
    }

    /**
     * Re-reads the input list from the audio layer. Public so the mic
     * picker can refresh on demand ("I just plugged it in"): asks the
     * system again rather than re-reading the last list, which is empty if
     * captions never got as far as setting up the microphone.
     */
    fun refreshInputs() {
        audio.refreshInputs()
        syncInputs()
    }

    private fun inputsChanged() {
        statsData.inputChanges += 1
        syncInputs()
        retryWhenMicrophonesChange()
    }

    /**
     * Captions stopped for a microphone problem with no retry left (a
     * hearing aid at the edge of its range dropping out a few times in a
     * minute uses them up) stayed stopped after it came back, until
     * someone tapped: a change of microphones is a new chance, as a
     * returning network is for a download. At most once in
     * [MICROPHONE_CHANGE_RETRY_SECONDS], since taking the session down can
     * itself be reported as a change.
     */
    private fun retryWhenMicrophonesChange() {
        if (microphoneRetryTask != null || scheduledRetry != null || systemInterrupted) return
        val kind = phase.failure?.kind ?: return
        if (kind != PipelineFailure.Kind.AudioSessionFailed && kind != PipelineFailure.Kind.NoAudioInputs) return
        if (availableInputs.isEmpty()) return
        val at = now()
        val last = lastMicrophoneChangeRetryAt
        if (last != null && at >= last && at - last < MICROPHONE_CHANGE_RETRY_SECONDS) return
        lastMicrophoneChangeRetryAt = at
        recovery.reset()
        // A voice sample holds the microphone: captions try again when it
        // is done, as a Retry tapped meanwhile would. Dropped here, the
        // change was never answered and captions stayed stopped.
        if (isRecordingVoice) {
            logEvent(PipelineEvent.Kind.Note("the microphones changed during a voice recording, trying captions again after it"))
            if (retryAfterRecording == null) retryAfterRecording = HeldRetry(null)
            return
        }
        logEvent(PipelineEvent.Kind.Note("the microphones changed, trying captions again"))
        microphoneRetryTask = scope.launch {
            retry()
            microphoneRetryTask = null
        }
    }

    private fun syncInputs() {
        availableInputs = audio.availableInputs
        val previous = selectedInputUID
        selectedInputUID = audio.selectedInputUID
        val current = availableInputs.firstOrNull { it.uid == selectedInputUID }
        if (selectedInputUID != previous) {
            if (current != null) {
                journalOnly(PipelineEvent.Kind.Input(current.portName, current.portType))
            }
            // Only stopped captions let it go unsaid: paused, starting or
            // waiting to try again, they come back on whatever microphone
            // is left, with nothing saying it changed.
            microphoneDrop.inputChanged(lastSelectedInput, current, phase != PipelinePhase.Idle)
        }
        // Remembered apart from the list: the one that just went away is
        // no longer in it.
        if (current != null) lastSelectedInput = current
    }

    /** Hides the notice until another microphone drops. */
    fun dismissMicrophoneDrop() {
        microphoneDrop.dismiss()
    }

    /**
     * What the running engine says about its own work; see
     * `TranscriptionEngine.diagnosticsSummary`.
     */
    suspend fun engineDiagnostics(): String? = currentEngine?.diagnosticsSummary()

    private fun logEvent(kind: PipelineEvent.Kind) {
        val count = eventLog.events.size
        val last = eventLog.events.lastOrNull()
        eventLog.record(kind, now())
        val event = eventLog.events.lastOrNull()
        if (event != null && (eventLog.events.size != count || event != last)) {
            onEvent?.invoke(event)
        }
    }

    /**
     * Steps and microphone changes would crowd the short in-memory log out
     * of the failures it is there to tell in order; the journal has room.
     */
    private fun journalOnly(kind: PipelineEvent.Kind) {
        onEvent?.invoke(PipelineEvent(now(), kind))
    }

    /**
     * A line for each step of getting ready, with how long the one before
     * took. Download percentages aren't steps.
     */
    private fun noteStep(old: PipelinePhase) {
        val name = stepName(phase)
        if (name == stepName(old)) return
        val time = now()
        val began = stepBeganAt
        val took = if (began == null) null else time - began
        stepBeganAt = time
        // Listening, failures and pauses have their own, fuller lines.
        if (!phase.isTransitioning) return
        journalOnly(PipelineEvent.Kind.Step(name, if (old.isTransitioning) took else null))
    }

    // endregion

    // region Speakers

    /**
     * What the active embedder's output looks like: what it declares, or
     * else probed on silence (enrollment is rare, not on the hot audio
     * path). Declaring it matters: this runs at launch, on the main
     * thread, and a probe loads the model.
     * A profile saved by a since-replaced embedder (see
     * `EmbeddingClusterer.assign`) is a different length and can never be
     * matched against live speech; seeding it anyway would still count as
     * a real "speaker identified" in diagnostics forever.
     */
    private val expectedEmbeddingLength: Int?
        get() {
            embedder.embeddingLength?.let { return it }
            return embedder.embed(FloatArray((EMBEDDING_WINDOW_SECONDS * SAMPLE_RATE).toInt()), SAMPLE_RATE)?.size
        }

    /**
     * False for a voice print the current embedder can't compare against
     * live speech: that person is never named until recorded again.
     */
    fun canRecognize(profile: SpeakerProfile): Boolean {
        val expected = expectedEmbeddingLength ?: return true
        return profile.embedding.size == expected
    }

    /**
     * Seeds the clusterer with a saved profile so that person is named
     * from their first utterance. Does nothing for a profile whose voice
     * print predates the current embedder; see [expectedEmbeddingLength].
     */
    fun enroll(profile: SpeakerProfile) {
        if (!canRecognize(profile)) return
        profileClusters[profile.id] = clusterer.enroll(profile.name, profile.embedding)
        speakerClusters = clusterer.clusters
    }

    /**
     * Which voice each saved profile was seeded as, so deleting one of
     * several prints under the same name stops that one being listened for.
     */
    private val profileClusters = HashMap<UUID, Int>()

    /**
     * A saved voice print was deleted while the person keeps another one:
     * the deleted print (a recording of the wrong person, say) no longer
     * puts their name on anyone for the rest of this session.
     */
    fun forgetProfile(id: UUID) {
        val clusterID = profileClusters.remove(id) ?: return
        clusterer.forgetName(clusterID)
        speakerClusters = clusterer.clusters
    }

    /**
     * Computes an embedding from an enrollment recording, or null if the
     * recording was too short to say anything about the voice.
     */
    fun embedding(forEnrollmentSamples: FloatArray): FloatArray? =
        averagePrint(speechWindows(forEnrollmentSamples), embedder, SAMPLE_RATE)

    /**
     * The same, with the model run off the main thread. Enrolling can be
     * the first time the speaker model is needed, and its first load can
     * take seconds: the screen stays responsive meanwhile.
     */
    suspend fun embeddingInBackground(forEnrollmentSamples: FloatArray): FloatArray? {
        val windows = speechWindows(forEnrollmentSamples)
        val embedder = this.embedder
        return withContext(embedderContext) {
            averagePrint(windows, embedder, SAMPLE_RATE)
        }
    }

    /**
     * How far past its length an enrollment recording may run before it
     * is given up on.
     */
    internal var enrollmentStallSeconds: Double = 5.0

    /**
     * Records [seconds] of audio for voice enrollment through the *same*
     * capture path live captioning uses: same input, same 16 kHz format
     * the embedder is calibrated for. (The first build used a separate
     * recorder at the hardware's native rate, so enrolled profiles were
     * computed on 48 kHz audio and could never match live 16 kHz
     * embeddings.) Live captioning is paused for the duration and
     * resumed afterwards if it was running.
     */
    suspend fun captureEnrollmentSamples(seconds: Double, onProgress: (Double) -> Unit = {}): FloatArray {
        // Still starting counts as running: torn down for the recording,
        // a start that was under way was left paused for good.
        val wasRunning = phase.isListening || phase.isTransitioning
        if (wasRunning) {
            tearDownSession()
            phase = PipelinePhase.Paused
        }

        val collected = FloatList()
        var wasCancelled = false
        val target = (seconds * SAMPLE_RATE).toInt()
        // A Siri "start captions" meanwhile would take the microphone from
        // the recording; it waits for the recording to end instead.
        isRecordingVoice = true
        try {
            stopListeningForSounds()
            val stream = enrollmentCapture()
            if (stream != null) {
                // A microphone that stops delivering would keep the recording
                // screen up for good, its cancel button disabled. Stopping
                // capture ends the stream, and a short recording is refused
                // like a quiet one.
                val stall = enrollmentStallSeconds
                val deadline = scope.launch {
                    delay((seconds + stall).seconds)
                    if (!isActive) return@launch
                    audio.stopCapture()
                }
                var reached = false
                // A cancelled recording ends like a finished one, with what
                // it has heard, and captions come back (a cancelled Swift
                // task leaves its stream loop and carries on).
                try {
                    stream.transformWhile { chunk ->
                        emit(chunk)
                        !reached
                    }.collect { chunk ->
                        collected.append(AudioFanOut.withoutGlitches(chunk))
                        onProgress(minOf(collected.size.toDouble() / target.toDouble(), 1.0))
                        if (collected.size >= target) reached = true
                    }
                } catch (error: CancellationException) {
                    wasCancelled = true
                }
                deadline.cancel()
                audio.stopCapture()
            }
        } finally {
            isRecordingVoice = false
        }

        val afterwards: suspend () -> Unit = {
            val restartSettings = restartAfterRecording
            val held = retryAfterRecording
            if (restartSettings != null) {
                restartAfterRecording = null
                retryAfterRecording = null
                restart(restartSettings)
            } else if (wasRunning) {
                resume()
            } else if (held != null) {
                retryAfterRecording = null
                retry(held.settings)
            } else {
                val current = phase
                if (current is PipelinePhase.Failed &&
                    (retryToken != null || homeServerRecheck != null || comesBackWithoutTimer(current.reason))
                ) {
                    listenForSoundsMeanwhile(current.reason)
                }
            }
        }
        if (wasCancelled) withContext(NonCancellable) { afterwards() } else afterwards()
        return collected.toFloatArray()
    }

    /**
     * Starts capture for enrollment. Captions may never have run since the
     * app opened (it's done from Settings), and then there is no audio
     * session to capture from: set one up first, instead of recording
     * nothing and blaming a quiet room.
     */
    private suspend fun enrollmentCapture(): Flow<FloatArray>? {
        startCaptureOrNull()?.let { return it }
        if (audio.requestPermission() != AudioPermission.Granted) return null
        val preferredInput = settingsInUse?.preferredInputUID ?: audio.selectedInputUID
        try {
            audio.prepareSession(preferredInput)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return null
        }
        return startCaptureOrNull()
    }

    private fun startCaptureOrNull(): Flow<FloatArray>? = try {
        audio.startCapture()
    } catch (error: Exception) {
        null
    }

    /**
     * Tags an inferred cluster with a real name after the fact, returning
     * the cluster centroid so the caller can persist it as a profile.
     */
    fun nameSpeaker(of: TranscriptSegment, name: String): FloatArray? {
        val clusterID = of.speakerClusterID ?: return null
        clusterer.nameCluster(clusterID, name)
        speakerClusters = clusterer.clusters
        return clusterer.clusters.firstOrNull { it.id == clusterID }?.centroid
    }

    fun displayName(forSegment: TranscriptSegment): String = clusterer.displayName(forSegment.speakerClusterID)

    /**
     * A conversation ended: the next one's unnamed voices are numbered
     * from 1 again (see `EmbeddingClusterer.startNewConversation`), and the
     * record of which finished line belongs to whom is let go.
     */
    fun startNewConversation() {
        clusterer.startNewConversation()
        speakerClusters = clusterer.clusters
        recentSpeechCluster = null
        utteranceClusterAssignments = HashMap()
    }

    /** A saved speaker was renamed; lines already on screen follow. */
    fun renameSpeakers(named: String, to: String) {
        clusterer.renameClusters(named, to)
        speakerClusters = clusterer.clusters
    }

    /** A saved speaker was deleted; lines stop showing the name. */
    fun forgetSpeakerName(name: String) {
        clusterer.forgetName(name)
        speakerClusters = clusterer.clusters
    }

    // A choice made while listening also goes into the active settings, as
    // keyword alerts and vocabulary do: every automatic retry starts from
    // that copy, and it used to put back what was chosen at Start - a
    // doorbell switched on mid-evening went quiet again while Settings
    // still showed it on.
    fun setSpeakerSimilarityThreshold(threshold: Float) {
        clusterer.similarityThreshold = threshold
        settingsInUse?.speakerSimilarityThreshold = threshold
        coveredSettings?.speakerSimilarityThreshold = threshold
    }

    internal val speakerSimilarityThreshold: Float get() = clusterer.similarityThreshold

    /**
     * The phone's model chosen while captions come from the home computer
     * or the cloud. A cover loads the phone's model from these settings:
     * it asked for the one set when captions started, which the backup
     * download may never have fetched, and a model still to download is
     * never started behind her back, so captions stopped instead.
     */
    fun setWhisperModelVariant(variant: String) {
        settingsInUse?.whisperModelVariant = variant
        coveredSettings?.whisperModelVariant = variant
    }

    fun setSoundAlertPreferences(preferences: SoundAlertPreferences) {
        soundPolicy.preferences = preferences
        settingsInUse?.soundAlerts = preferences
        coveredSettings?.soundAlerts = preferences
    }

    // endregion

    // region Tokens

    /**
     * Commits segments the engine never marked final once they've been
     * quiet long enough. Called on a timer while listening; exposed so
     * tests can drive it with a controlled clock.
     */
    fun commitStaleSegments(now: Double? = null) {
        val committed = stabilizer.commitStale(now ?: this.now())
        for (segment in committed) {
            val previously = segments.lastOrNull { it.id == segment.id }
            upsert(segment)
            noteFinished(segment, previously)
        }
        if (committed.isNotEmpty()) {
            statsData.hasOpenLine = stabilizer.hasOpenLine
        }
    }

    /** Commits the line already shown for [id], if any, as it stands. */
    private fun commitWithoutNewWords(id: UUID) {
        val segment = stabilizer.commit(id) ?: return
        val previously = segments.lastOrNull { it.id == segment.id }
        upsert(segment)
        noteFinished(segment, previously)
        statsData.hasOpenLine = stabilizer.hasOpenLine
    }

    private fun handleToken(incoming: TranscriptToken) {
        statsData.tokensReceived += 1
        // ivrit.ai's model starts some lines with an invisible direction
        // mark; kept, it would travel into saved conversations and search.
        var cleaned = HebrewText.removingDirectionMarks(incoming.text)
        if (incoming.isFinal) incomingText.remove(incoming.utteranceID) else incomingText[incoming.utteranceID] = cleaned
        // A sentence cleared from the screen mid-way keeps arriving with all
        // of its words so far. Dropping it whole lost everything said after
        // the tap, on screen and in History; only the words that are the
        // cleared ones again stay gone (see `wordsAfter`).
        val carry = clearedTurnCarry
        if (carry != null && incoming.isFinal && incoming.startsNewSpeakerTurn &&
            incoming.timestamp == carry.timestamp && clearedUtterances[incoming.utteranceID] == null
        ) {
            clearedUtterances[incoming.utteranceID] = carry.words
        }
        clearedTurnCarry = null
        val cleared = clearedUtterances[incoming.utteranceID]
        if (cleared != null) {
            val (rest, clearedWordsUsed) = wordsAfter(cleaned, cleared)
            if (incoming.isFinal) {
                // A first line of only cleared words keeps what it showed
                // after the tap, so the next speaker's line leaves it out too.
                var carried = cleared.drop(clearedWordsUsed)
                if (rest == null) {
                    val shown = stabilizer.segments.lastOrNull { it.id == incoming.utteranceID }
                    if (shown != null) carried = carried + comparableWords(shown.text)
                }
                if (carried.isNotEmpty()) clearedTurnCarry = ClearedTurnCarry(incoming.timestamp, carried)
            }
            if (rest == null) {
                // Only cleared words: a final still finishes what was shown
                // after the tap, instead of leaving it "still settling".
                statsData.lastTokenAt = now()
                if (incoming.isFinal) commitWithoutNewWords(incoming.utteranceID)
                return
            }
            cleaned = rest
        }
        val token = if (cleaned == incoming.text) {
            incoming
        } else {
            TranscriptToken(
                utteranceID = incoming.utteranceID,
                text = cleaned,
                isFinal = incoming.isFinal,
                timestamp = incoming.timestamp,
                speakerClusterID = incoming.speakerClusterID,
                confidence = incoming.confidence,
                startsNewSpeakerTurn = incoming.startsNewSpeakerTurn,
                uncertainWords = incoming.uncertainWords.map { HebrewText.removingDirectionMarks(it) },
            )
        }
        statsData.lastTokenAt = now()
        // A brand-new utterance with nothing to show yet isn't worth an
        // (empty) row on screen; wait for text before creating it.
        // Searched from the end, where the line being written is: a phone
        // left listening for days holds thousands of lines.
        val isKnown = stabilizer.segments.indexOfLast { it.id == token.utteranceID } >= 0
        if (!isKnown && isBlank(token.text)) {
            return
        }
        // "toda. toda. toda." ("thanks") invented window after window on a
        // quiet room: see `SilencePhraseGuard`. Suppressing this token
        // still must not swallow a true final: the words already shown
        // are good, so commit them now instead of leaving the line
        // "still settling" until the stale-commit safety net catches up.
        if (!silencePhraseGuard.admits(token, now())) {
            if (token.isFinal) commitWithoutNewWords(token.utteranceID)
            return
        }
        var enriched = token
        if (enriched.speakerClusterID == null) {
            val assigned = utteranceClusterAssignments[token.utteranceID]
            val recent = recentSpeechCluster
            if (assigned != null) {
                enriched = enriched.copy(speakerClusterID = assigned)
            } else if (!isKnown && !token.startsNewSpeakerTurn && recent != null && now() - recent.at <= RECENT_SPEECH_CLUSTER_SECONDS) {
                enriched = enriched.copy(speakerClusterID = recent.id)
                utteranceClusterAssignments[token.utteranceID] = recent.id
            }
        }
        enriched = enriched.copy(scoredBy = settingsInUse?.let { CaptionConfidence.Scorer(it.engine, it.whisperModelVariant) })
        val previously = stabilizer.segments.lastOrNull { it.id == token.utteranceID }
        val segment = stabilizer.ingest(enriched)
        noteFinished(segment, previously)
        upsert(segment)
        statsData.hasOpenLine = stabilizer.hasOpenLine
        scanForKeywords(segment)
    }

    private class StopLoop : RuntimeException(null, null, false, false)

    private suspend fun consumeEmbeddings(audioStream: Flow<FloatArray>, run: UUID) {
        var buffer = FloatList()
        var speechSamples = 0
        val windowSamples = (EMBEDDING_WINDOW_SECONDS * SAMPLE_RATE).toInt()
        try {
            audioStream.collect { chunk ->
                if (runID != run) throw StopLoop()
                statsData.audioChunksReceived += 1
                statsData.audioSecondsReceived += chunk.size.toDouble() / SAMPLE_RATE
                statsData.lastAudioAt = now()
                recentAudio.append(chunk)

                buffer.append(chunk)
                val isSpeech = embeddingVoiceDetector.isSpeech(chunk)
                statsData.inputLevels.add(embeddingVoiceDetector.lastLevel)
                if (embeddingVoiceDetector.noiseFloor > 0) {
                    statsData.noiseFloorDecibels = (20f * log10(embeddingVoiceDetector.noiseFloor)).toDouble()
                }
                statsData.noiseMarginDecibels = (20f * log10(embeddingVoiceDetector.currentNoiseFloorRatio)).toDouble()
                if (isSpeech) {
                    lastSpeechAt = now()
                    speechSamples += chunk.size
                    statsData.speechChunks += 1
                }
                if (buffer.size < windowSamples) return@collect
                val window = buffer.toFloatArray()
                val speechFraction = speechSamples.toDouble() / window.size.toDouble()
                buffer = FloatList()
                speechSamples = 0
                if (speechFraction < MINIMUM_SPEECH_FRACTION_FOR_EMBEDDING) return@collect

                // A few hundred spectrum frames per window: real work, done off
                // the main thread so the caption screen stays smooth while
                // people talk. Chunks arriving meanwhile wait in the stream.
                val embedder = this.embedder
                // The line being written while this audio was heard, taken now:
                // the first embedding loads the model and can take many seconds,
                // and asking afterwards gave the voice to whichever line had
                // started meanwhile, leaving the speaker's own line unnamed.
                val heardDuring = stabilizer.segments.lastOrNull { !it.isCommitted }?.id
                val computed = withContext(embedderContext) {
                    embedder.embed(window, SAMPLE_RATE)
                }
                if (runID != run) throw StopLoop()
                // A glitched buffer can make NaNs; one NaN centroid would never
                // match anything again and open a new "speaker" every window.
                if (computed == null || !computed.all { it.isFinite() }) return@collect
                val clusterCountBefore = clusterer.clusters.size
                val clusterID = clusterer.assign(computed)
                if (clusterer.clusters.size > clusterCountBefore) {
                    statsData.speakerClustersOpened += 1
                }
                speakerClusters = clusterer.clusters

                if (heardDuring == null) {
                    // Heard before any line was open: the line about to start
                    // takes this voice. A window an open line claimed is that
                    // line's speaker, not the next one's; passing it on named
                    // a short reply by someone else after the person asking.
                    recentSpeechCluster = RecentSpeechCluster(clusterID, now())
                    return@collect
                }
                utteranceClusterAssignments[heardDuring] = clusterID
                // Writing an unchanged value still tells every observer the
                // transcript changed and redraws the caption list, every 1.5 s
                // of speech; only write when the speaker actually changed.
                val index = segments.indexOfLast { it.id == heardDuring }
                if (index >= 0 && segments[index].speakerClusterID != clusterID) {
                    setSegment(index, segments[index].copy(speakerClusterID = clusterID))
                    onCaptionsChanged?.invoke()
                }
            }
        } catch (_: StopLoop) {
            return
        }
    }

    /**
     * Counts a line the first time it finishes. One finished on the guess
     * that the engine went quiet (`CaptionStabilizer.commitStale`) can
     * finish again, by the engine's own late final or after reopening:
     * still one line, but its words may have changed since it was read
     * out, so [committedLineCount] moves for `CaptionAnnouncer` to
     * look at it again. That used to wait for the next line to finish.
     */
    private fun noteFinished(segment: TranscriptSegment, previously: TranscriptSegment?) {
        if (!segment.isCommitted) return
        if (previously != null && previously.isCommitted && (previously.isSettled || !segment.isSettled)) return
        if (previously != null && (previously.isCommitted || previously.isProvisionalCommit)) {
            committedLineCount += 1
        } else {
            statsData.segmentsCommitted += 1
            committedLineCount += 1
        }
    }

    private fun setSegment(index: Int, segment: TranscriptSegment) {
        val updated = segments.toMutableList()
        updated[index] = segment
        segments = updated
    }

    private fun upsert(segment: TranscriptSegment) {
        val index = segments.indexOfLast { it.id == segment.id }
        if (index >= 0) {
            // The voice analysis names the speaker on the shown line only;
            // the stabilizer's copy, committed after a pause, doesn't carry it.
            var merged = segment
            if (merged.speakerClusterID == null) {
                merged = merged.copy(speakerClusterID = segments[index].speakerClusterID)
            }
            setSegment(index, merged)
        } else {
            segments = segments + segment
        }
        onCaptionsChanged?.invoke()
    }

    // endregion

    // region Plumbing

    /**
     * Applies a new hint list to the running engine (and remembers it for
     * the next start) without restarting; a name added mid-conversation
     * should help from the next sentence on.
     */
    suspend fun setVocabulary(terms: List<String>) {
        val cleaned = VocabularyHints.normalized(terms)
        settingsInUse?.vocabulary = cleaned
        coveredSettings?.vocabulary = cleaned
        val engine = currentEngine
        if (engine == null || !(phase.isListening || phase == PipelinePhase.Paused)) return
        engine.setVocabulary(primedVocabulary(cleaned))
    }

    /**
     * The engine hint list actually sent, merging in every enabled
     * keyword alert's phrase (see `VocabularyHints.combining`): the
     * alert list changing (a word added, removed or toggled) also needs
     * this to be resent, not just the plain vocabulary list changing.
     */
    private fun primedVocabulary(userVocabulary: List<String>): List<String> =
        VocabularyHints.combining(userVocabulary, keywordMatcher.alerts)

    private fun trackDownload(progress: EnginePreparationProgress, time: Double) {
        downloadQuiet?.cancel()
        val fraction = progress.fraction
        if (progress.stage != EnginePreparationProgress.Stage.DownloadingModel || fraction == null) {
            downloadEstimator.reset()
            downloadSecondsRemaining = null
            return
        }
        downloadEstimator.record(fraction, time)
        downloadSecondsRemaining = downloadEstimator.secondsRemaining()
        if (downloadSecondsRemaining == null) return
        val quiet = downloadEstimator.quietLimit(downloadQuietSeconds, downloadQuietGaps)
        downloadQuiet = scope.launch {
            delay(quiet.seconds)
            if (!isActive) return@launch
            downloadSecondsRemaining = null
        }
    }

    private fun cachedEngine(settings: AppSettings): TranscriptionEngine {
        val key = engineCacheKey(settings)
        // Only the engine in use is kept. A Whisper engine holds its loaded
        // model, hundreds of megabytes to 3 GB; every model tried once in
        // the model list used to stay loaded for as long as the app ran,
        // until the system ended the app for using too much memory. Going back to
        // an earlier one loads it again, which takes seconds; restarts and
        // retries on the same settings still reuse it.
        val cached = engineCache[key]
        if (cached != null) {
            engineCache.clear()
            engineCache[key] = cached
            return cached
        }
        engineCache.clear()
        val engine = engineFactory(settings)
        engineCache[key] = engine
        return engine
    }

    /**
     * For asking the home computer or the cloud whether it answers again
     * while the phone covers for it. Their engines are light, so one is
     * kept beside the phone's loaded model: going through [cachedEngine]
     * threw that model out at every check, and the next pause or resume
     * loaded it all over again.
     */
    private fun probeEngine(settings: AppSettings): TranscriptionEngine {
        val key = engineCacheKey(settings)
        engineCache[key]?.let { return it }
        val engine = engineFactory(settings)
        engineCache[key] = engine
        return engine
    }

    private fun engineCacheKey(settings: AppSettings): String =
        "${settings.engine.rawValue}|${settings.whisperModelVariant}|${settings.allowServerFallbackForAppleSpeech}|" +
            "${settings.cloudProvider.rawValue}|${settings.chosenCloudModel}|${settings.homeServerAddress}|${settings.homeServerBeam}"

    /**
     * A tap that stopped delivering (see `AudioStallWatchdog`) becomes a
     * visible failure, which automatic recovery answers with a fresh
     * audio engine: the same thing a manual stop and start would do.
     */
    private fun checkAudioIsArriving() {
        if (!phase.isListening) return
        if (!audioWatchdog.tick(statsData.audioChunksReceived, systemInterrupted)) return
        statsData.audioStalls += 1
        logEvent(PipelineEvent.Kind.MicrophoneStalled)
        fail(PipelineFailure.Kind.AudioSessionFailed, "no audio from the microphone for ${audioWatchdog.stallSeconds.toInt()} s")
    }

    /**
     * The phone's model says "loading" once and nothing more until it is
     * ready. A load the system turned into a set-up of minutes (it had thrown the
     * compiled copy away) said "just a moment" for all of them.
     * The same for a start waiting on an earlier load, which says nothing
     * more either (a model changed during a set-up of minutes).
     */
    private suspend fun sayWaitIsSlow(waiter: UUID) {
        slowLoadWait()
        val current = waitingShown
        if (!currentCoroutineContext().isActive || newestWaitingStart != waiter || current == null) return
        if (phase != PipelinePhase.PreparingEngine(current) ||
            current.stage != EnginePreparationProgress.Stage.LoadingModel || current.isFirstTime || current.isTakingLong
        ) {
            return
        }
        val shown = current.copy(isTakingLong = true)
        waitingShown = shown
        phase = PipelinePhase.PreparingEngine(shown)
    }

    private suspend fun sayLoadIsSlow(run: UUID) {
        slowLoadWait()
        val current = phase
        if (!currentCoroutineContext().isActive || runID != run || current !is PipelinePhase.PreparingEngine) return
        val shown = current.progress
        if (shown.stage != EnginePreparationProgress.Stage.LoadingModel || shown.isFirstTime || shown.isTakingLong) return
        phase = PipelinePhase.PreparingEngine(shown.copy(isTakingLong = true))
    }

    private fun captureLost(run: UUID) {
        if (runID != run || !(phase.isListening || earlyCaptureRun == run) || systemInterrupted) return
        statsData.audioStalls += 1
        logEvent(PipelineEvent.Kind.MicrophoneStalled)
        fail(PipelineFailure.Kind.AudioSessionFailed, "the microphone never settled after it changed")
    }

    private fun fail(kind: PipelineFailure.Kind, detail: String, engineUnavailability: EngineUnavailability? = null) {
        tearDownSession()
        val failure = PipelineFailure(kind, detail, engineUnavailability)
        val onPhone = settingsInUse?.let { CloudCover.phoneSettings(it, failure) }
        // Set before the phase: whoever reacts to the failure looks before
        // the cover has had a turn to start.
        pendingCover = if (onPhone == null) null else failure
        phase = PipelinePhase.Failed(failure)
        logEvent(PipelineEvent.Kind.Failed(failure))
        if (onPhone != null) {
            scope.launch { coverForCloud(onPhone, failure) }
            return
        }
        scheduleAutoRecovery(failure)
    }

    /**
     * The cover for [failure] started or won't: if captions are still
     * stopped by it, whoever decided they are coming back looks again.
     */
    private fun coverSettled(failure: PipelineFailure) {
        if (pendingCover != failure) return
        pendingCover = null
        val current = phase
        if (current is PipelinePhase.Failed && current.reason == failure) onPhaseChange?.invoke(current)
    }

    /**
     * See [isCoveringForCloud]. Only a model that is already on the phone
     * takes over: a surprise download of hundreds of megabytes is not a
     * fair way to find out the cloud stopped.
     */
    private suspend fun coverForCloud(settings: AppSettings, failure: PipelineFailure, retryIfNotCovered: Boolean = true) {
        try {
            // Someone may have stopped, retried or restarted captions since
            // the failure; then the engine cache is theirs to fill, not ours.
            val before = phase
            if (before !is PipelinePhase.Failed || before.reason != failure) return
            val engine = cachedEngine(settings)
            val needsDownload = engine.pendingDownloadMegabytes() != null
            val current = phase
            if (current !is PipelinePhase.Failed || current.reason != failure) return
            // Read again after the check: a name added or a microphone picked
            // during it reached the active settings, not the copy made before
            // it. A different model picked meanwhile is not the one checked.
            val onPhone = (settingsInUse ?: settings).copy()
            onPhone.engine = settings.engine
            if (needsDownload || engineCacheKey(onPhone) != engineCacheKey(settings)) {
                if (retryIfNotCovered) {
                    scheduleAutoRecovery(failure)
                } else {
                    waitForHomeServer(failure)
                }
                return
            }
            // A voice sample that began during the check holds the microphone:
            // `start` refused to run, the cover's flags stayed set for the next
            // start, and nothing brought captions back after the recording.
            if (isRecordingVoice) {
                if (retryAfterRecording == null) retryAfterRecording = HeldRetry(null)
                return
            }
            logEvent(PipelineEvent.Kind.Note("cloud unavailable, the phone's own model took over"))
            nextStartCoversCloud = true
            coverReason = failure.engineUnavailability?.kind
            coveredSettings = settingsInUse?.copy()
            start(onPhone)
            if (coverReason == EngineUnavailability.Kind.HomeServerUnreachable &&
                coveredSettings?.engine == TranscriptionEngineKind.HomeServer
            ) {
                val back = homeServerSwitchedBackAt
                if (back != null && back.elapsedNow() < homeServerFlapWindowSeconds.seconds) {
                    homeServerFlaps = minOf(homeServerFlaps + 1, 4)
                } else {
                    homeServerFlaps = 0
                }
                homeServerSwitchedBackAt = null
                recheckHomeServer()
            }
            if ((coverReason == EngineUnavailability.Kind.NoInternet || coverReason == EngineUnavailability.Kind.TemporarilyUnavailable) &&
                coveredSettings?.engine == TranscriptionEngineKind.Cloud
            ) {
                val back = cloudSwitchedBackAt
                if (back != null && back.elapsedNow() < cloudFlapWindowSeconds.seconds) {
                    cloudFlaps = minOf(cloudFlaps + 1, 4)
                } else {
                    cloudFlaps = 0
                }
                cloudSwitchedBackAt = null
                recheckCloud()
            }
        } finally {
            coverSettled(failure)
        }
    }

    /**
     * See [homeServerRecheckSeconds]. Goes back to the chosen settings
     * once the computer answers and nobody is mid-sentence.
     */
    private fun recheckHomeServer() {
        homeServerRecheck?.cancel()
        homeServerRecheck = scope.launch {
            var answered = 0
            while (isActive) {
                delay(currentHomeServerRecheckSeconds.seconds)
                if (!isActive || !isCoveringForCloud) return@launch
                val chosen = coveredSettings
                if (chosen == null || chosen.engine != TranscriptionEngineKind.HomeServer) return@launch
                if (!coverCanBeReplaced) continue
                val server = probeEngine(chosen)
                if (server.checkAvailability(chosen.languageCode) != EngineAvailability.Available ||
                    !isActive || !isCoveringForCloud || !coverCanBeReplaced
                ) {
                    answered = 0
                    continue
                }
                answered += 1
                if (!switchBackMomentCame(answered)) continue
                // Read again after the check and the wait: a name or a
                // microphone chosen meanwhile reached `coveredSettings`, not
                // this copy. Another address or model was not the one asked.
                val latest = coveredSettings
                if (latest == null || engineCacheKey(latest) != engineCacheKey(chosen)) {
                    answered = 0
                    continue
                }
                logEvent(PipelineEvent.Kind.Note("the home computer answers again, switching back to it"))
                homeServerRecheck = null
                homeServerSwitchedBackAt = timeSource.markNow()
                switchBack(latest.copy())
                return@launch
            }
        }
    }

    /**
     * See [cloudRecheckSeconds]. Only for a dropped connection: a key or
     * credit problem needs a person to fix it, not a periodic retry, and
     * `PhasePresentation` already gives them a way to Settings for those.
     * Goes back to the cloud once it can be reached again and nobody is
     * mid-sentence.
     */
    private fun recheckCloud() {
        cloudRecheck?.cancel()
        cloudRecheck = scope.launch {
            var answered = 0
            while (isActive) {
                delay(currentCloudRecheckSeconds.seconds)
                if (!isActive || !isCoveringForCloud) return@launch
                val chosen = coveredSettings
                if (chosen == null || chosen.engine != TranscriptionEngineKind.Cloud) return@launch
                if (!coverCanBeReplaced) continue
                val cloud = probeEngine(chosen)
                if (cloud.checkAvailability(chosen.languageCode) != EngineAvailability.Available ||
                    !isActive || !isCoveringForCloud || !coverCanBeReplaced
                ) {
                    answered = 0
                    continue
                }
                answered += 1
                if (!switchBackMomentCame(answered)) continue
                // Read again after the check and the wait: a name or a
                // microphone chosen meanwhile reached `coveredSettings`, not
                // this copy. Another address or model was not the one asked.
                val latest = coveredSettings
                if (latest == null || engineCacheKey(latest) != engineCacheKey(chosen)) {
                    answered = 0
                    continue
                }
                logEvent(PipelineEvent.Kind.Note("the cloud answers again, switching back to it"))
                cloudRecheck = null
                cloudSwitchedBackAt = timeSource.markNow()
                switchBack(latest.copy())
                return@launch
            }
        }
    }

    /**
     * A cover that is listening, or one whose own model has failed: the
     * phone's model failing for good (a load that runs out of memory)
     * left captions stopped with the computer or cloud healthy, because
     * the checks waited for listening and a retry covers again.
     */
    private val coverCanBeReplaced: Boolean
        get() {
            if (phase is PipelinePhase.Failed) return !isRecordingVoice && !systemInterrupted
            return phase.isListening
        }

    /**
     * From a failure, through `retry` rather than `.idle`, which means
     * stopped on purpose and would skip "captions came back".
     */
    private suspend fun switchBack(chosen: AppSettings) {
        if (phase is PipelinePhase.Failed) {
            isCoveringForCloud = false
            retry(chosen)
        } else {
            restart(chosen)
        }
    }

    private fun canSwitchBack(answeredChecks: Int): Boolean {
        if (isBetweenSentences) return true
        if (answeredChecks < switchBackAfterAnsweredChecks) return false
        val lineOpen = stabilizer.segments.lastOrNull()?.let { !it.isCommitted } ?: false
        val breathing = lastSpeechAt?.let { now() - it >= switchBackBreathSeconds } ?: true
        return !lineOpen && breathing
    }

    /**
     * Now, or at a breath within [switchBackBreathWaitSeconds] once enough
     * checks allow switching without a quiet moment. Counted in steps, not
     * by `now()`, which tests may hold still.
     */
    private suspend fun switchBackMomentCame(answeredChecks: Int): Boolean {
        if (canSwitchBack(answeredChecks)) return true
        if (answeredChecks < switchBackAfterAnsweredChecks) return false
        repeat((switchBackBreathWaitSeconds * 10).toInt()) {
            delay(100.milliseconds)
            if (!currentCoroutineContext().isActive || !isCoveringForCloud || !coverCanBeReplaced) return false
            if (canSwitchBack(answeredChecks)) return true
        }
        return false
    }

    private val isBetweenSentences: Boolean
        get() {
            if (stabilizer.segments.lastOrNull()?.let { !it.isCommitted } == true) return false
            val speechAt = lastSpeechAt
            if (speechAt != null && now() - speechAt < homeServerSwitchBackQuietSeconds) return false
            val lastTokenAt = statsData.lastTokenAt ?: return true
            return now() - lastTokenAt >= homeServerSwitchBackQuietSeconds
        }

    // endregion

    // region Downloads and the network

    /** "Download now anyway": this session's model may use cellular data. */
    suspend fun approveCellularDownload() {
        cellularDownloadApproved = true
        if (!isWaitingForWiFi) return
        retry()
    }

    /** The Settings switch for downloading over cellular changed. */
    suspend fun setAllowCellularModelDownload(allowed: Boolean) {
        settingsInUse?.allowCellularModelDownload = allowed
        coveredSettings?.allowCellularModelDownload = allowed
        if (!allowed || !isWaitingForWiFi) return
        retry()
    }

    /**
     * The app is back on screen. If the model was waiting for room on the
     * phone and there is room now (she freed some up in the Settings
     * app), the download starts without anyone having to tap.
     */
    suspend fun appDidBecomeActive() {
        val why = phase.failure?.engineUnavailability ?: return
        if (why.kind != EngineUnavailability.Kind.NotEnoughStorage || phase.isTransitioning) return
        val megabytes = storageNeededMegabytes ?: why.downloadMegabytes
        if (megabytes != null && storageShortfall(megabytes) != null) {
            return
        }
        retry()
    }

    private fun storageShortfall(megabytes: Int): Int? =
        StorageSpaceGate.shortfallMegabytes(megabytes, availableStorageBytes?.invoke())

    private val isWaitingForWiFi: Boolean
        get() = phase.failure?.engineUnavailability?.kind == EngineUnavailability.Kind.WaitingForWiFi

    /**
     * A download that was waiting for Wi-Fi, or failed for want of a
     * connection, starts as soon as the connection allows it, without
     * waiting out the retry timer. So do captions from the cloud or the
     * home computer that stopped when the internet went: the timer gives
     * up after about half a minute, and a router takes longer to restart.
     * Not during a phone call, which holds the microphone: the call's end
     * starts a fresh set of attempts instead.
     */
    private fun networkConditionsChanged(conditions: NetworkConditions) {
        val allowCellular = allowsCellularDownload
        val previous = lastNetwork
        lastNetwork = conditions
        if (networkRetryTask != null || systemInterrupted) return
        val kind = phase.failure?.engineUnavailability?.kind ?: return
        val returned = when (kind) {
            EngineUnavailability.Kind.WaitingForWiFi, EngineUnavailability.Kind.ModelDownloadFailed -> {
                val wasUsable = previous?.let { ModelDownloadGate.canRetryDownload(it, allowCellular) } ?: false
                !wasUsable && ModelDownloadGate.canRetryDownload(conditions, allowCellular)
            }
            EngineUnavailability.Kind.NoInternet, EngineUnavailability.Kind.HomeServerUnreachable ->
                !(previous?.isConnected ?: false) && conditions.isConnected
            else -> false
        }
        if (!returned) return
        recovery.reset()
        startNetworkRetry()
    }

    private val allowsCellularDownload: Boolean
        get() = (settingsInUse?.allowCellularModelDownload ?: false) || cellularDownloadApproved

    private fun startNetworkRetry() {
        networkRetryTask = scope.launch {
            retry()
            networkRetryTask = null
        }
    }

    // endregion

    // region Automatic recovery

    /**
     * Tells the pipeline a phone call (or another app) took or released
     * the audio session. While it's held no retry runs; when it's
     * released a failed pipeline gets a fresh set of attempts.
     */
    fun systemInterruptionChanged(active: Boolean) {
        if (active != systemInterrupted) {
            logEvent(PipelineEvent.Kind.PhoneCall(active))
        }
        systemInterrupted = active
        if (active) {
            cancelScheduledRetry()
            stopListeningForSounds()
        } else {
            val failure = phase.failure ?: return
            recovery.reset()
            scheduleAutoRecovery(failure)
            // Wi-Fi that came back during the call was passed over, and
            // waiting for Wi-Fi has no timer to try again: captions stayed
            // stopped on a connection that could take the download.
            val connection = lastNetwork
            if (isWaitingForWiFi && networkRetryTask == null && connection != null &&
                ModelDownloadGate.canRetryDownload(connection, allowsCellularDownload)
            ) {
                startNetworkRetry()
            }
        }
    }

    private fun scheduleAutoRecovery(failure: PipelineFailure) {
        val since = listeningSince
        if (since != null && now() - since >= recovery.healthyListeningSeconds) {
            recovery.reset()
        }
        listeningSince = null
        cancelScheduledRetry()
        if (systemInterrupted) return
        val delaySeconds = recovery.nextDelay(failure)
        if (delaySeconds == null) {
            coverOnceRetriesRunOut(failure)
            if (waitForHomeServer(failure) || comesBackWithoutTimer(failure)) {
                listenForSoundsMeanwhile(failure)
            }
            return
        }

        val token = UUID.randomUUID()
        retryToken = token
        scheduledRetry = ScheduledRetry(now() + delaySeconds, recovery.attempts)
        logEvent(PipelineEvent.Kind.RetryScheduled(recovery.attempts, delaySeconds))
        retryTask = scope.launch {
            delay(delaySeconds.seconds)
            if (!isActive || retryToken != token || phase !is PipelinePhase.Failed) return@launch
            // Detach from this task before retrying: `retry` cancels any
            // scheduled retry, and cancelling the task it's running on
            // would cancel the model download it's about to start.
            retryTask = null
            retryToken = null
            scheduledRetry = null
            retry()
        }
        listenForSoundsMeanwhile(failure)
    }

    /**
     * Captions stopped because their engine can't run for now (the home
     * computer asleep, the cloud down, a model download being retried)
     * used to take sound alerts down with them: the microphone stayed off
     * until captions came back, all night with the computer off, and
     * nothing said so. While a retry or the wait for the computer is lined
     * up it stays on for sounds alone. Not for a microphone that failed,
     * nor during a call, which has it.
     */
    private fun listenForSoundsMeanwhile(failure: PipelineFailure) {
        stopListeningForSounds()
        if (failure.kind != PipelineFailure.Kind.EngineUnavailable || systemInterrupted || isRecordingVoice) return
        val detector = soundDetector ?: return
        if (!soundPolicy.preferences.isEnabled) return
        val source = startCaptureOrNull() ?: return
        val run = runID
        val fan = FlowAudioFanOut(scope, source, count = 1)
        fanOut = fan
        isListeningForSoundsOnly = true
        statsData.soundDetectionRunning = true
        val observations = detector.observations(fan.outputs[0])
        soundTask = scope.launch {
            observations.takeWhile { runID == run }.collect { observation ->
                handleSoundObservation(observation)
            }
            // Stopped on purpose, the stop already said so; a task stopped
            // for a voice sample woke late enough to call the next one's
            // sounds off.
            if (runID != run || !isActive) return@launch
            statsData.soundDetectionRunning = false
        }
    }

    /**
     * Waiting for Wi-Fi or for room on the phone has no timer, but
     * captions come back by themselves: on the next change of connection,
     * or when the app is opened again with room. The microphone was off
     * for sounds all that time, and a smoke alarm went unheard.
     */
    private fun comesBackWithoutTimer(failure: PipelineFailure): Boolean {
        val kind = failure.engineUnavailability?.kind ?: return false
        return kind == EngineUnavailability.Kind.WaitingForWiFi || kind == EngineUnavailability.Kind.NotEnoughStorage
    }

    private fun stopListeningForSounds() {
        if (!isListeningForSoundsOnly) return
        isListeningForSoundsOnly = false
        soundTask?.cancel()
        soundTask = null
        fanOut?.cancel()
        fanOut = null
        audio.stopCapture()
        statsData.soundDetectionRunning = false
    }

    /**
     * A cloud that stays busy or broken through every retry is covered
     * like a lost connection: captions stopping for good, with a phone
     * model already downloaded, left her with nothing until someone
     * tapped retry. A single hiccup is still only retried.
     */
    private fun coverOnceRetriesRunOut(failure: PipelineFailure) {
        if (failure.engineUnavailability?.kind != EngineUnavailability.Kind.TemporarilyUnavailable) return
        val chosen = settingsInUse ?: return
        if (chosen.engine != TranscriptionEngineKind.Cloud) return
        val onPhone = chosen.copy()
        onPhone.engine = TranscriptionEngineKind.WhisperKit
        pendingCover = failure
        scope.launch { coverForCloud(onPhone, failure, retryIfNotCovered = false) }
    }

    /**
     * With no model on the phone to take over, an unreachable home
     * computer left captions stopped once the quick retries ran out, even
     * after it woke up: only a tap, or the phone's own network dropping
     * and coming back, started them again. Asks the computer every
     * [homeServerWaitSeconds] and starts captions once it answers. A
     * refused code needs a person, so it isn't asked again.
     */
    private fun waitForHomeServer(failure: PipelineFailure): Boolean {
        if (failure.engineUnavailability?.kind != EngineUnavailability.Kind.HomeServerUnreachable ||
            settingsInUse?.engine != TranscriptionEngineKind.HomeServer
        ) {
            return false
        }
        homeServerRecheck?.cancel()
        // Once, not per check: a diagnostics report went from the last
        // quick retry straight to "answers again" a minute later, and a
        // computer that never answered left no sign the phone was waiting.
        logEvent(PipelineEvent.Kind.Note("waiting for the home computer, asking it every ${formatG(homeServerWaitSeconds)} s"))
        homeServerRecheck = scope.launch {
            while (isActive) {
                delay(homeServerWaitSeconds.seconds)
                val current = phase
                if (!isActive || current !is PipelinePhase.Failed || current.reason != failure) return@launch
                // Read again on every pass: a backup model picked, a name
                // added or a microphone chosen during the wait reached
                // the active settings, and a copy from the start of the wait
                // checked the old model (never taking over) or covered
                // with the old names.
                val chosen = settingsInUse
                if (chosen == null || chosen.engine != TranscriptionEngineKind.HomeServer) return@launch
                // A voice sample recording holds the microphone: the retry
                // would stop its capture and then not start, and this wait
                // would be over. It waits for the recording instead.
                if (systemInterrupted || isRecordingVoice) continue
                val server = cachedEngine(chosen)
                val answer = server.checkAvailability(chosen.languageCode)
                val still = phase
                if (answer != EngineAvailability.Available || !isActive || systemInterrupted || isRecordingVoice ||
                    still !is PipelinePhase.Failed || still.reason != failure
                ) {
                    if (coverWithReadyBackup(failure)) return@launch
                    continue
                }
                logEvent(PipelineEvent.Kind.Note("the home computer answers again, starting captions"))
                homeServerRecheck = null
                recovery.reset()
                retry()
                return@launch
            }
        }
        return true
    }

    /**
     * The phone's backup can finish downloading while captions wait for
     * the computer (Settings, Home computer), and its row then says the
     * phone carries on by itself: it takes over here instead of the wait
     * going on until the computer answers or someone taps Retry.
     */
    private suspend fun coverWithReadyBackup(failure: PipelineFailure): Boolean {
        val chosen = settingsInUse ?: return false
        if (chosen.engine != TranscriptionEngineKind.HomeServer) return false
        val onPhone = CloudCover.phoneSettings(chosen, failure) ?: return false
        if (probeEngine(onPhone).pendingDownloadMegabytes() != null) return false
        val still = phase
        if (!currentCoroutineContext().isActive || systemInterrupted || isRecordingVoice ||
            still !is PipelinePhase.Failed || still.reason != failure
        ) {
            return false
        }
        logEvent(PipelineEvent.Kind.Note("the phone's backup is ready, starting captions on it"))
        homeServerRecheck = null
        recovery.reset()
        coverForCloud(onPhone, failure, retryIfNotCovered = false)
        return true
    }

    private fun cancelScheduledRetry() {
        retryTask?.cancel()
        retryTask = null
        retryToken = null
        scheduledRetry = null
    }

    private fun tearDownSession() {
        runID = UUID.randomUUID()
        statsData.soundDetectionRunning = false
        currentEngine = null
        recentSpeechCluster = null
        streamTask?.cancel()
        streamTask = null
        embeddingTask?.cancel()
        embeddingTask = null
        soundTask?.cancel()
        soundTask = null
        isListeningForSoundsOnly = false
        staleCommitTask?.cancel()
        staleCommitTask = null
        fanOut?.cancel()
        fanOut = null
        audio.stopCapture()
        finishOpenLines()
    }

    /**
     * The words of a line cut off mid-sentence (paused, stopped, the
     * microphone failed) are as final as they will get. Left open, the
     * line stayed dimmed like text still arriving, was saved unfinished,
     * and was never read out to the screen reader.
     */
    private fun finishOpenLines() {
        val finished = stabilizer.commitAll()
        if (finished.isEmpty()) return
        for (segment in finished) {
            val previously = segments.lastOrNull { it.id == segment.id }
            upsert(segment)
            noteFinished(segment, previously)
        }
        statsData.hasOpenLine = false
    }

    // endregion

    internal class FloatList {
        private var data = FloatArray(1024)
        var size = 0
            private set

        fun append(chunk: FloatArray) {
            if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
            System.arraycopy(chunk, 0, data, size, chunk.size)
            size += chunk.size
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val EMBEDDING_WINDOW_SECONDS = 1.5
        private const val SAMPLE_RATE = 16_000.0
        private const val MAX_KEYWORD_HITS = 50
        private const val MAX_SOUND_ALERTS = 30
        private const val MINIMUM_SPEECH_FRACTION_FOR_EMBEDDING = 0.4
        private const val RECENT_SPEECH_CLUSTER_SECONDS = 4.0

        /**
         * How long after a sound the classifier may still be reporting it: its
         * window is about a second long.
         */
        internal const val SOUND_REPORT_DELAY_SECONDS: Double = 1.5

        internal const val MICROPHONE_CHANGE_RETRY_SECONDS: Double = 30.0

        /**
         * Below this likeness to the recording's own average, a window is
         * taken for someone else: the TV, or a relative talking over her.
         */
        internal const val ENROLLMENT_OUTLIER_BELOW: Float = 0.3f

        /**
         * A voice print needs this many windows of speech, about 4.5 seconds
         * of someone talking, to be worth keeping.
         */
        internal const val MINIMUM_ENROLLMENT_WINDOWS = 3

        private fun stepName(phase: PipelinePhase): String = when (phase) {
            PipelinePhase.Idle -> "idle"
            PipelinePhase.RequestingMicrophonePermission -> "asking for the microphone"
            is PipelinePhase.PreparingEngine -> {
                val progress = phase.progress
                val model = progress.detail?.let { " $it" } ?: ""
                "engine: ${progress.stage.rawValue}$model${if (progress.isFirstTime) " (setting up for this phone)" else ""}"
            }
            PipelinePhase.StartingAudio -> "starting audio"
            PipelinePhase.Listening -> "listening"
            PipelinePhase.Paused -> "paused"
            is PipelinePhase.Failed -> "failed"
        }

        private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }

        private fun trimmingPunctuation(word: String): String {
            val scalars = word.codePoints().toArray()
            var from = 0
            var to = scalars.size
            while (from < to && isPunctuation(scalars[from])) from++
            while (to > from && isPunctuation(scalars[to - 1])) to--
            return String(scalars, from, to - from)
        }

        private fun isBlank(text: String): Boolean {
            val scalars = text.codePoints().toArray()
            return scalars.all { Character.isWhitespace(it) || Character.isSpaceChar(it) || it == 0x85 }
        }

        private fun splitWords(text: String): List<String> {
            val words = ArrayList<String>()
            val current = StringBuilder()
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85) {
                    if (current.isNotEmpty()) {
                        words.add(current.toString())
                        current.setLength(0)
                    }
                } else {
                    current.appendCodePoint(codePoint)
                }
            }
            if (current.isNotEmpty()) words.add(current.toString())
            return words
        }

        private fun comparableWords(text: String): List<String> = splitWords(text).map { trimmingPunctuation(it) }

        /**
         * [text] without the words that are the cleared ones said again, or
         * null when nothing else is left.
         *
         * The finished line is often written a little differently from the
         * live one that was cleared (a stronger final pass changes a word's
         * gender or prefix), and cutting only the exactly shared first words
         * brought the rest of the cleared sentence back. The start of [text]
         * is lined up with [cleared] word by word instead, and the cut goes
         * where they match best, as long as no more than a third of the
         * cleared words differ. Rewritten further than that, the line comes
         * back whole: a repeat is better than a loss. Also returns how many of
         * the cleared words [text] accounted for.
         */
        internal fun wordsAfter(text: String, cleared: List<String>): Pair<String?, Int> {
            val words = splitWords(text)
            if (words.isEmpty()) return Pair(null, 0)
            val comparable = words.map { trimmingPunctuation(it) }
            // edits[i][k]: the words to change, add or drop to turn the first
            // i cleared words into the first k words of `text`.
            val edits = ArrayList<IntArray>()
            edits.add(IntArray(comparable.size + 1) { it })
            for ((i, word) in cleared.withIndex()) {
                val row = IntArray(comparable.size + 1)
                row[0] = i + 1
                for (k in 1..comparable.size) {
                    row[k] = minOf(
                        edits[i][k] + 1,
                        row[k - 1] + 1,
                        edits[i][k - 1] + (if (comparable[k - 1] == word) 0 else 1),
                    )
                }
                edits.add(row)
            }
            var bestEdits = -1
            var bestCut = -1
            var bestClearedWords = -1
            fun consider(clearedWords: Int, cut: Int) {
                val cost = edits[clearedWords][cut]
                if (cost > clearedWords / 3) return
                if (bestEdits >= 0 && (bestEdits < cost || (bestEdits == cost && bestCut <= cut))) return
                bestEdits = cost
                bestCut = cut
                bestClearedWords = clearedWords
            }
            // Words after the cut are new only once all of the cleared ones
            // are accounted for; a `text` that is all old may still stop short.
            for (cut in 0 until comparable.size) {
                consider(cleared.size, cut)
            }
            for (clearedWords in 0..cleared.size) {
                consider(clearedWords, comparable.size)
            }
            if (bestEdits < 0) return Pair(words.joinToString(" "), cleared.size)
            if (bestCut >= words.size) return Pair(null, bestClearedWords)
            return Pair(words.subList(bestCut, words.size).joinToString(" "), bestClearedWords)
        }

        private fun averagePrint(windows: List<FloatArray>, embedder: SpeakerEmbedding, sampleRate: Double): FloatArray? {
            // Made the way live speech is matched: 1.5 s windows, only those
            // with enough speech in them, averaged. One print of the whole
            // recording mixed the pauses between sentences into the voice,
            // and a recording nobody spoke in still became a "voice".
            val prints = ArrayList<FloatArray>()
            for (window in windows) {
                // A print with a NaN in it can't be compared, and can't be
                // saved either (JSON has no NaN): one would make every later
                // settings save fail.
                val embedding = embedder.embed(window, sampleRate)
                if (embedding == null || embedding.isEmpty() || !embedding.all { it.isFinite() } ||
                    (prints.isNotEmpty() && embedding.size != prints[0].size)
                ) {
                    continue
                }
                prints.add(embedding)
            }
            if (prints.size < MINIMUM_ENROLLMENT_WINDOWS) return null
            return consistentAverage(prints)
        }

        /**
         * The average voice, leaving out the windows that don't sound like
         * the rest. Without it the TV the enrolling screen invites her to
         * leave on was blended into the saved voice. When most windows would
         * go, the whole average is kept: it was the only answer before.
         */
        internal fun consistentAverage(prints: List<FloatArray>): FloatArray {
            fun mean(list: List<FloatArray>): FloatArray {
                val sum = FloatArray(list[0].size)
                for (print in list) for (index in sum.indices) sum[index] += print[index]
                return FloatArray(sum.size) { sum[it] / list.size.toFloat() }
            }
            val all = mean(prints)
            val alike = prints.filter { cosineSimilarity(it, all) >= ENROLLMENT_OUTLIER_BELOW }
            if (alike.size < MINIMUM_ENROLLMENT_WINDOWS || alike.size * 2 <= prints.size) return all
            return mean(alike)
        }

        /**
         * [samples] cut into embedding windows, keeping those that hold as
         * much speech as live matching asks for. Fed through a voice detector
         * in chunks about the size the microphone delivers.
         */
        internal fun speechWindows(samples: FloatArray): List<FloatArray> {
            val chunkSize = 800
            val windowSamples = (EMBEDDING_WINDOW_SECONDS * SAMPLE_RATE).toInt()
            val detector = EnergyVoiceDetector()
            val windows = ArrayList<FloatArray>()
            var buffer = FloatList()
            var speechSamples = 0
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunkSize, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                buffer.append(chunk)
                if (detector.isSpeech(chunk)) {
                    speechSamples += chunk.size
                }
                if (buffer.size >= windowSamples) {
                    if (speechSamples.toDouble() / buffer.size.toDouble() >= MINIMUM_SPEECH_FRACTION_FOR_EMBEDDING) {
                        windows.add(buffer.toFloatArray())
                    }
                    buffer = FloatList()
                    speechSamples = 0
                }
                offset = end
            }
            return windows
        }

        /** Swift's `String(describing:)` of the errors the engines throw, for the diagnostics text. */
        private fun describe(error: Throwable): String = when (error) {
            CloudSpeechError.KeyMissing -> "keyMissing"
            CloudSpeechError.KeyRejected -> "keyRejected"
            CloudSpeechError.OutOfCredit -> "outOfCredit"
            CloudSpeechError.RateLimited -> "rateLimited"
            CloudSpeechError.Offline -> "offline"
            is CloudSpeechError.ServerTrouble -> "serverTrouble(status: ${error.status})"
            CloudSpeechError.BadReply -> "badReply"
            else -> error.toString()
        }

        /** C's `%g`: six significant digits, no trailing zeros. */
        private fun formatG(value: Double): String {
            if (value == 0.0) return "0"
            if (!value.isFinite()) return value.toString()
            val rounded = BigDecimal(value).round(MathContext(6))
            val exponent = rounded.precision() - rounded.scale() - 1
            if (exponent < -4 || exponent >= 6) {
                val digits = rounded.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
                val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
                val sign = if (value < 0) "-" else ""
                val exponentText = (if (exponent < 0) "-" else "+") + Math.abs(exponent).toString().padStart(2, '0')
                return "$sign${mantissa}e$exponentText"
            }
            return rounded.stripTrailingZeros().toPlainString()
        }
    }
}

/** One keyword spotted in one caption line, as shown in the alert strip. */
data class KeywordHit(
    val id: UUID = UUID.randomUUID(),
    val segmentID: UUID,
    val match: KeywordMatch,
    val timestamp: Double,
)
