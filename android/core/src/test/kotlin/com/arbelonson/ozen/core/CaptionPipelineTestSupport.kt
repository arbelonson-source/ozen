package com.arbelonson.ozen.core

import java.lang.ref.WeakReference
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext

/**
 * Checks [condition] until it holds or [within] passes (on the test
 * scheduler's clock), and says whether it held. Every caller waits for
 * something that happens within milliseconds, so a passing test never gets
 * near the ceiling.
 */
suspend fun eventually(within: Duration, condition: () -> Boolean): Boolean =
    withTimeoutOrNull(within) {
        while (!condition()) delay(5.milliseconds)
        true
    } ?: false

suspend fun eventually(condition: () -> Boolean): Boolean = eventually(10.seconds, condition)

class TestError : Exception() {
    override fun toString(): String = "TestError()"
}

class FakeAudioError(private val domain: String, private val code: Int) : Exception() {
    override fun toString(): String = "Error Domain=$domain Code=$code \"(null)\""
}

/**
 * A call into a suspend function of another actor leaves the caller's own
 * context for a moment in Swift, and whatever else is queued there runs
 * meanwhile. The fakes mark those points the same way.
 */
private suspend fun hop() {
    yield()
}

/**
 * Scripted audio layer: the test decides the permission answer, the input
 * list, and pushes audio chunks by hand. Records every call so tests can
 * assert on ordering (e.g. "inputs were listed before the engine loaded").
 */
class FakeAudioCapturer : AudioCapturing {
    override var availableInputs: List<AudioInputDescriptor> = listOf(
        AudioInputDescriptor(uid = "builtin", portName = "iPhone Microphone", portType = AudioPortType.BuiltInMic),
    )
    override var selectedInputUID: String? = null
    override var inputLevel: Float = 0f
    override var onInputsChanged: (() -> Unit)? = null
    override var onCaptureLost: (() -> Unit)? = null

    var permissionAnswer: AudioPermission = AudioPermission.Granted
    var prepareError: Throwable? = null
    var startError: Throwable? = null

    /** Like the real session: capture can't start before one is prepared. */
    var requiresPreparedSession = false
    private var isPrepared = false
    val calls = ArrayList<String>()
    private var channel: Channel<FloatArray>? = null

    override suspend fun requestPermission(): AudioPermission {
        calls.add("requestPermission")
        return permissionAnswer
    }

    /**
     * When set, preparing waits here, like a real session activation
     * that takes a while, until the test resumes it.
     */
    var holdPrepare = false
    private var heldPrepare: CompletableDeferred<Unit>? = null
    val isHoldingPrepare: Boolean get() = heldPrepare != null

    fun releasePrepare() {
        heldPrepare?.complete(Unit)
        heldPrepare = null
    }

    override suspend fun prepareSession(preferredInputUID: String?) {
        calls.add("prepareSession")
        if (holdPrepare) {
            val held = CompletableDeferred<Unit>()
            heldPrepare = held
            held.await()
        }
        prepareError?.let { throw it }
        isPrepared = true
        selectedInputUID = AudioRoutePolicy.resolveSelection(availableInputs, preferredInputUID, selectedInputUID)
    }

    override fun startCapture(): Flow<FloatArray> {
        calls.add("startCapture")
        startError?.let { throw it }
        if (requiresPreparedSession && !isPrepared) throw FakeAudioError("FakeAudio", 2)
        val created = Channel<FloatArray>(Channel.UNLIMITED)
        channel = created
        return created.receiveAsFlow()
    }

    override fun stopCapture() {
        calls.add("stopCapture")
        channel?.close()
        channel = null
    }

    /** Inputs the system would report if asked again right now. */
    var inputsOnRefresh: List<AudioInputDescriptor>? = null

    override fun refreshInputs() {
        calls.add("refreshInputs")
        inputsOnRefresh?.let { availableInputs = it }
    }

    override fun selectInput(uid: String) {
        calls.add("selectInput:$uid")
        if (availableInputs.none { it.uid == uid }) throw FakeAudioError("FakeAudio", 1)
        selectedInputUID = uid
    }

    fun push(samples: FloatArray) {
        channel?.trySend(samples)
    }

    fun simulateRouteChange(inputs: List<AudioInputDescriptor>) {
        availableInputs = inputs
        onInputsChanged?.invoke()
    }
}

/**
 * Holds a [FakeEngine.prepare] call suspended until a test releases it,
 * so an in-flight preparation can be driven from outside with no guessing
 * about scheduling order.
 */
class PrepareGate {
    private val opened = CompletableDeferred<Unit>()

    suspend fun wait() {
        hop()
        opened.await()
    }

    suspend fun open() {
        hop()
        opened.complete(Unit)
    }
}

/**
 * A switch a test turns off and a fake's hook reads: a hook already in
 * flight when the test moves on sees the change, where swapping the hook
 * itself out would not stop it.
 */
class TestSwitch(var isOn: Boolean)

/**
 * Scripted engine: the test hands it an availability answer, optional
 * progress updates to emit during `prepare`, and a channel to push tokens
 * through. Also records how many times it was constructed via the factory
 * so engine caching across restarts is observable.
 */
class FakeEngine(
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit,
    var availability: EngineAvailability = EngineAvailability.Available,
    val progressUpdates: List<EnginePreparationProgress> = emptyList(),
) : TranscriptionEngine {
    /** Runs in the middle of `prepare`, so a test can inspect pipeline state at that exact moment. */
    var duringPrepare: (() -> Unit)? = null

    /**
     * Parks `prepare` right after it starts, until the test opens it, so a
     * test can hold one preparation genuinely in flight while it drives
     * the pipeline from outside.
     */
    var prepareGate: PrepareGate? = null
    var afterProgressGate: PrepareGate? = null

    /** Waits this long between progress reports, as a real download does. */
    var progressSpacing: Duration? = null

    /** Megabytes `prepare` would still download; null when the model is there. */
    var pendingDownload: Int? = null
    var prepareCount = 0
        private set

    /**
     * Preparations parked at `prepareGate` right now. A test waiting for
     * one to be held waits on this, not on `prepareCount`: a preparation
     * that starts between installing the gate and reading the count is
     * counted and then held, and the count never moves again.
     */
    val heldPrepares: Int get() = held
    private var held = 0
    private var tokenChannel: Channel<TranscriptToken>? = null
    var chunksSeen = 0
        private set
    private val vocabularyHistory = ArrayList<List<String>>()

    /** Every list handed to `setVocabulary`, in order. */
    val vocabularySeen: List<List<String>> get() = vocabularyHistory.toList()

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        hop()
        prepareCount += 1
        val gate = prepareGate
        if (gate != null) {
            held += 1
            try {
                gate.wait()
            } finally {
                held -= 1
            }
        }
        for (update in progressUpdates) {
            progress(update)
            val spacing = progressSpacing
            if (spacing != null) delay(spacing) else yield()
        }
        afterProgressGate?.wait()
        val wasCancelled = cancelPending
        cancelPending = false
        if (wasCancelled) {
            return EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "cancelled")
        }
        duringPrepare?.let { hook ->
            hop()
            hook()
        }
        return availability
    }

    var downloadCancels = 0
        private set
    private var cancelPending = false

    override suspend fun cancelDownload() {
        hop()
        downloadCancels += 1
        cancelPending = true
        afterProgressGate?.open()
    }

    var pendingDownloadChecks = 0
        private set

    /** Free space the download needs at its peak; null means the download. */
    var pendingInstall: Int? = null

    override suspend fun pendingInstallMegabytes(): Int? {
        hop()
        return pendingInstall ?: pendingDownload
    }

    /** Runs each time `pendingDownloadMegabytes` is asked. */
    var duringPendingDownloadCheck: (() -> Unit)? = null

    override suspend fun pendingDownloadMegabytes(): Int? {
        hop()
        duringPendingDownloadCheck?.let { hook ->
            hop()
            hook()
            hop()
        }
        pendingDownloadChecks += 1
        return pendingDownload
    }

    override fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken> {
        val created = Channel<TranscriptToken>(Channel.UNLIMITED)
        tokenChannel = created
        return channelFlow {
            val feeder = launch { audio.collect { chunksSeen += 1 } }
            for (token in created) send(token)
            feeder.cancel()
        }
    }

    override suspend fun setVocabulary(terms: List<String>) {
        hop()
        vocabularyHistory.add(terms)
    }

    private val cellularHistory = ArrayList<Boolean>()

    /** Every answer handed to `setCellularDownloadAllowed`, in order. */
    val cellularAllowedSeen: List<Boolean> get() = cellularHistory.toList()

    override suspend fun setCellularDownloadAllowed(allowed: Boolean) {
        hop()
        cellularHistory.add(allowed)
    }

    fun emit(token: TranscriptToken) {
        tokenChannel?.trySend(token)
    }

    fun endStream(throwing: Throwable? = null) {
        tokenChannel?.close(throwing)
    }
}

class FakeEmbedder : SpeakerEmbedding {
    /**
     * The first sample of a window decides the "voice": windows starting
     * with the same value embed identically, so a test can put two
     * speakers on the line deterministically.
     */
    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
        if (samples.isEmpty()) return null
        return if (samples[0] > 0) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
    }
}

/**
 * Holds every embedding until the test lets it go, like the first one of
 * a session while the model loads. It blocks the thread it runs on, so a
 * pipeline using it must run its embedder on a thread of its own.
 */
class GatedEmbedder : SpeakerEmbedding {
    private val gate = Semaphore(0)
    private val entered = AtomicInteger()
    val callsStarted: Int get() = entered.get()

    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
        entered.incrementAndGet()
        gate.acquire()
        return FakeEmbedder().embed(samples, sampleRate)
    }

    fun release() {
        gate.release()
    }
}

/** Scripted sound classifier: the test pushes observations by hand. */
class FakeSoundDetector : SoundEventDetecting {
    private var channel: Channel<SoundObservation>? = null
    var chunksSeen = 0
        private set

    override fun observations(audio: Flow<FloatArray>): Flow<SoundObservation> {
        val created = Channel<SoundObservation>(Channel.UNLIMITED)
        channel = created
        return channelFlow {
            val feeder = launch {
                audio.collect { chunksSeen += 1 }
                created.close()
            }
            for (observation in created) send(observation)
            feeder.cancel()
        }
    }

    fun push(observation: SoundObservation) {
        channel?.trySend(observation)
    }

    /** The classifier giving up on its own, mid-session. */
    fun finish() {
        channel?.close()
    }
}

/** Every engine a factory built, held weakly, to see which are still alive. */
class BuiltEngines {
    private val references = ArrayList<WeakReference<FakeEngine>>()

    val count: Int get() = references.size
    val aliveCount: Int
        get() {
            repeat(3) {
                System.gc()
                Thread.sleep(5)
            }
            return references.count { it.get() != null }
        }

    fun add(engine: FakeEngine) {
        references.add(WeakReference(engine))
    }
}

class FactoryLog {
    var calls = 0
}

class FakeNetworkMonitor(override var current: NetworkConditions?) : NetworkMonitoring {
    override var onChange: ((NetworkConditions) -> Unit)? = null

    fun change(to: NetworkConditions) {
        current = to
        onChange?.invoke(to)
    }
}

/** Free space the test can change while the pipeline holds on to it. */
class FakeStorage(megabytes: Long?) {
    @Volatile
    private var bytes: Long? = megabytes?.let { it * 1_000_000 }

    fun set(megabytes: Long?) {
        bytes = megabytes?.let { it * 1_000_000 }
    }

    fun available(): Long? = bytes
}

/**
 * Like [eventually], for a condition that other threads move (a pipeline
 * that embeds on a thread of its own, held by a test): the wait is in real
 * time, with the test scheduler run between looks.
 */
suspend fun eventuallyInRealTime(within: Duration = 10.seconds, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    while (!condition()) {
        if (System.nanoTime() > deadline) return false
        delay(1.milliseconds)
        Thread.sleep(1)
    }
    return true
}

class TestClock {
    @Volatile
    var now: Double = 1_000.0
        private set

    @Synchronized
    fun advance(seconds: Double) {
        now += seconds
    }
}

data class MadePipeline(val pipeline: CaptionPipeline, val audio: FakeAudioCapturer, val log: FactoryLog)

/**
 * A pipeline on the test scheduler's clock and dispatcher. The voice model
 * is run on a second dispatcher of the same scheduler, which leaves the
 * caller's context for a moment as a detached task does.
 */
fun TestScope.captionPipeline(
    audio: AudioCapturing,
    engineFactory: (AppSettings) -> TranscriptionEngine,
    embedder: SpeakerEmbedding = FakeEmbedder(),
    soundDetector: SoundEventDetecting? = null,
    soundPolicy: SoundEventPolicy = SoundEventPolicy(),
    clusterer: EmbeddingClusterer = EmbeddingClusterer(),
    stabilizer: CaptionStabilizer = CaptionStabilizer(),
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy(),
    audioWatchdog: AudioStallWatchdog = AudioStallWatchdog(),
    network: NetworkMonitoring? = null,
    availableStorageBytes: (() -> Long?)? = null,
    now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    embedderContext: CoroutineContext = StandardTestDispatcher(testScheduler),
): CaptionPipeline = CaptionPipeline(
    scope = backgroundScope,
    audio = audio,
    engineFactory = engineFactory,
    embedder = embedder,
    soundDetector = soundDetector,
    soundPolicy = soundPolicy,
    clusterer = clusterer,
    stabilizer = stabilizer,
    recovery = recovery,
    audioWatchdog = audioWatchdog,
    network = network,
    availableStorageBytes = availableStorageBytes,
    now = now,
    timeSource = testScheduler.timeSource,
    embedderContext = embedderContext,
)

fun TestScope.makePipeline(
    audio: FakeAudioCapturer = FakeAudioCapturer(),
    engines: Map<TranscriptionEngineKind, FakeEngine> = mapOf(TranscriptionEngineKind.WhisperKit to FakeEngine()),
    soundDetector: FakeSoundDetector? = null,
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
    audioWatchdog: AudioStallWatchdog = AudioStallWatchdog(),
    now: () -> Double = { 1_000.0 },
): MadePipeline {
    val log = FactoryLog()
    val pipeline = captionPipeline(
        audio = audio,
        engineFactory = { settings ->
            log.calls += 1
            engines[settings.engine] ?: FakeEngine(kind = settings.engine)
        },
        embedder = FakeEmbedder(),
        soundDetector = soundDetector,
        recovery = recovery,
        audioWatchdog = audioWatchdog,
        now = now,
    )
    return MadePipeline(pipeline, audio, log)
}
