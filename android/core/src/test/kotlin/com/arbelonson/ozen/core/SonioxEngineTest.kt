package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val engineFrames: List<String> =
    File(System.getProperty("ozen.fixtures"), "cloud/soniox-two-speakers.jsonl").readText(Charsets.UTF_8)
        .split("\n").filter { it.isNotEmpty() }

private class StreamDialers(sockets: List<StreamSocket>) : CloudSocketConnecting {
    private val lock = Any()
    private val sockets = sockets.toMutableList()

    override suspend fun open(url: URI, headers: Map<String, String>): HomeServerSocket = synchronized(lock) {
        if (sockets.isEmpty()) throw StreamGone()
        sockets.removeAt(0)
    }
}

class SonioxEngineTest {
    private val frames = engineFrames

    private fun TestScope.engine(
        dialer: StreamDialer,
        http: FakeCloudHTTP = FakeCloudHTTP(timeSource = testScheduler.timeSource),
        key: String? = "sx-test",
        pingSeconds: Double = 5.0,
        pongSeconds: Double = 8.0,
    ) = SonioxEngine(
        http = http,
        connector = dialer,
        pingSeconds = pingSeconds,
        pongSeconds = pongSeconds,
        timeSource = testScheduler.timeSource,
        apiKey = { key },
    )

    @Test
    fun `a conversation streams over one connection - the key in its header, the settings first, the microphone as 16-bit samples, an empty message at the end, and a line per turn back`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(onConfig = frames.dropLast(1), afterEnd = listOf(frames.last()))
        val dialer = StreamDialer(socket)
        val heard = listen(engine(dialer), streamAudio(4))
        assertNull(heard.error)
        assertEquals(
            listOf("מה", "מה שלומך", "מה שלומך?", "טוב, תודה.", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"),
            heard.tokens.map { it.text },
        )
        assertEquals(listOf(false, false, true, false, false, true, false, true), heard.tokens.map { it.isFinal })
        assertEquals(listOf(SonioxSpeech.streamURL), dialer.calls.map { it.url })
        assertEquals(mapOf("Authorization" to "Bearer sx-test"), dialer.calls.firstOrNull()?.headers)
        assertEquals(listOf(SonioxSpeech.config("he", emptyList()), SonioxSpeech.END), socket.sentTexts)
        assertEquals(4 * 1_600 * 2, socket.sentBytes)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a stream stopped while a piece of audio is still going out never counts as ended, so the next stream losing its connection is still no internet`() = runTest(timeout = 1.minutes) {
        val first = StreamSocket(holdsAudio = true)
        val second = StreamSocket()
        val soniox = SonioxEngine(connector = StreamDialers(listOf(first, second)), timeSource = testScheduler.timeSource, apiKey = { "sx-test" })
        val stopped = launch { soniox.stream("he", streamAudio(1, ends = false)).collect { } }
        assertTrue(waitUntil { first.heldAudio == 1 })
        stopped.cancel()
        stopped.join()
        assertTrue(waitUntil { first.isClosed })
        var nextError: Throwable? = null
        val next = launch {
            try {
                soniox.stream("he", streamAudio(1, ends = false)).collect { }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                nextError = error
            }
        }
        assertTrue(waitUntil { second.sentTexts.size == 1 })
        first.releaseAudio()
        waitUntil(0.5) { first.attemptedTexts.contains(SonioxSpeech.END) }
        second.drop()
        next.join()
        assertEquals(CloudSpeechError.Offline, nextError)
    }

    @Test
    fun `Chinese captions from a live service are cut into lines too`() = runTest(timeout = 1.minutes) {
        val words = """{"tokens":[{"text":"你好","is_final":true,"speaker":"1","start_ms":0,"end_ms":1400},{"text":"你好","is_final":true,"speaker":"1","start_ms":1500,"end_ms":2900},{"text":"你好","is_final":true,"speaker":"1","start_ms":3000,"end_ms":4400},{"text":"你好","is_final":true,"speaker":"1","start_ms":4500,"end_ms":5900},{"text":"你好","is_final":true,"speaker":"1","start_ms":6000,"end_ms":7400},{"text":"你好","is_final":true,"speaker":"1","start_ms":7500,"end_ms":8900},{"text":"你好","is_final":true,"speaker":"1","start_ms":9000,"end_ms":10400},{"text":"你好","is_final":true,"speaker":"1","start_ms":10500,"end_ms":11900},{"text":"你好","is_final":true,"speaker":"1","start_ms":12000,"end_ms":13400},{"text":"你好","is_final":true,"speaker":"1","start_ms":13500,"end_ms":14900},{"text":"你好","is_final":true,"speaker":"1","start_ms":15000,"end_ms":16400},{"text":"你好","is_final":true,"speaker":"1","start_ms":16500,"end_ms":17900},{"text":"你好","is_final":true,"speaker":"1","start_ms":18000,"end_ms":19400},{"text":"你好","is_final":true,"speaker":"1","start_ms":19500,"end_ms":20900},{"text":"你好","is_final":true,"speaker":"1","start_ms":21000,"end_ms":22400},{"text":"你好","is_final":true,"speaker":"1","start_ms":22500,"end_ms":23900},{"text":"你好","is_final":true,"speaker":"1","start_ms":24000,"end_ms":25400},{"text":"你好","is_final":true,"speaker":"1","start_ms":25500,"end_ms":26900},{"text":"你好","is_final":true,"speaker":"1","start_ms":27000,"end_ms":28400},{"text":"你好","is_final":true,"speaker":"1","start_ms":28500,"end_ms":29900},{"text":"你好","is_final":true,"speaker":"1","start_ms":30000,"end_ms":31400},{"text":"你好","is_final":true,"speaker":"1","start_ms":31500,"end_ms":32900},{"text":"你好","is_final":true,"speaker":"1","start_ms":33000,"end_ms":34400},{"text":"你好","is_final":true,"speaker":"1","start_ms":34500,"end_ms":35900},{"text":"你好","is_final":true,"speaker":"1","start_ms":36000,"end_ms":37400},{"text":"你好","is_final":true,"speaker":"1","start_ms":37500,"end_ms":38900},{"text":"你好","is_final":true,"speaker":"1","start_ms":39000,"end_ms":40400},{"text":"你好","is_final":true,"speaker":"1","start_ms":40500,"end_ms":41900},{"text":"你好","is_final":true,"speaker":"1","start_ms":42000,"end_ms":43400},{"text":"你好","is_final":true,"speaker":"1","start_ms":43500,"end_ms":44900},{"text":"你好","is_final":true,"speaker":"1","start_ms":45000,"end_ms":46400},{"text":"你好","is_final":true,"speaker":"1","start_ms":46500,"end_ms":47900},{"text":"你好","is_final":true,"speaker":"1","start_ms":48000,"end_ms":49400},{"text":"你好","is_final":true,"speaker":"1","start_ms":49500,"end_ms":50900},{"text":"你好","is_final":true,"speaker":"1","start_ms":51000,"end_ms":52400},{"text":"你好","is_final":true,"speaker":"1","start_ms":52500,"end_ms":53900},{"text":"你好","is_final":true,"speaker":"1","start_ms":54000,"end_ms":55400},{"text":"你好","is_final":true,"speaker":"1","start_ms":55500,"end_ms":56900},{"text":"你好","is_final":true,"speaker":"1","start_ms":57000,"end_ms":58400},{"text":"你好","is_final":true,"speaker":"1","start_ms":58500,"end_ms":59900}]}"""
        val socket = StreamSocket(onConfig = listOf(words), afterEnd = listOf("""{"tokens":[],"finished":true}"""))
        val heard = listen(engine(StreamDialer(socket)), streamAudio(1), languageCode = "zh")
        assertEquals(listOf(18, 18, 4).map { "你好".repeat(it) }, heard.tokens.filter { it.isFinal }.map { it.text })
    }

    @Test
    fun `a service refusing to open the connection is read as its HTTP answer - a refused key to fix, no credit, or its own trouble, not no internet`() = runTest(timeout = 1.minutes) {
        for ((status, expected) in listOf(
            401 to CloudSpeechError.KeyRejected,
            402 to CloudSpeechError.OutOfCredit,
            503 to CloudSpeechError.ServerTrouble(503),
        )) {
            val socket = StreamSocket(refusing = status)
            val heard = listen(engine(StreamDialer(socket)), streamAudio(1, ends = false))
            assertEquals(expected, heard.error, "$status")
        }
        val assembly = AssemblyAIEngine(
            connector = StreamDialer(StreamSocket(refusing = 402, endsAudio = { it.contains("Terminate") })),
            timeSource = testScheduler.timeSource,
            apiKey = { "aai-test" },
        )
        val failure = listen(assembly, streamAudio(1, ends = false)).error
        assertEquals(CloudSpeechError.OutOfCredit, failure)
    }

    @Test
    fun `the names list goes with the settings`() = runTest {
        val socket = StreamSocket(afterEnd = listOf(frames.last()))
        val soniox = engine(StreamDialer(socket))
        soniox.setVocabulary(listOf("דנה"))
        listen(soniox, streamAudio(1), languageCode = "ar")
        assertEquals(SonioxSpeech.config("ar", listOf("דנה")), socket.sentTexts.firstOrNull())
    }

    @Test
    fun `credit running out mid-sentence ends the stream with that reason, and the words on screen are marked cut`() = runTest(timeout = 1.minutes) {
        val error = """{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted","error_message":"x"}"""
        val socket = StreamSocket(onConfig = listOf(frames[0], error))
        val heard = listen(engine(StreamDialer(socket)), streamAudio(2, ends = false))
        assertEquals(CloudSpeechError.OutOfCredit, heard.error)
        assertEquals(listOf("מה", CaptionStabilizer.markingCutOff("מה")), heard.tokens.map { it.text })
        assertEquals(listOf(false, true), heard.tokens.map { it.isFinal })
        assertEquals(1, heard.tokens.map { it.utteranceID }.toSet().size)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a connection that drops is no internet, so the phone's model can take over, with the line on screen marked cut`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(onConfig = listOf(frames[0]))
        val heard = listen(engine(StreamDialer(socket)), streamAudio(2, ends = false)) { socket.drop() }
        assertEquals(CloudSpeechError.Offline, heard.error)
        assertEquals(listOf("מה", CaptionStabilizer.markingCutOff("מה")), heard.tokens.map { it.text })
    }

    @Test
    fun `Soniox closing after the last words without saying it has finished still ends captions quietly, the last line as said`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(afterEnd = listOf(frames[5]))
        val heard = listen(engine(StreamDialer(socket)), streamAudio(1))
        assertNull(heard.error)
        assertEquals(listOf("מצוין", "מצוין"), heard.tokens.map { it.text })
        assertEquals(listOf(false, true), heard.tokens.map { it.isFinal })
    }

    @Test
    fun `after a dropped connection, or credit running out, the next check asks Soniox again rather than trusting the last answer`() = runTest(timeout = 1.minutes) {
        val http = FakeCloudHTTP(timeSource = testScheduler.timeSource)
        val dropping = StreamSocket(onConfig = listOf(frames[0]))
        val soniox = engine(StreamDialer(dropping), http = http)
        assertEquals(EngineAvailability.Available, soniox.prepare("he") { })
        listen(soniox, streamAudio(1, ends = false)) { dropping.drop() }
        assertEquals(EngineAvailability.Available, soniox.prepare("he") { })
        assertEquals(2, http.requests.size)

        val broke = StreamSocket(onConfig = listOf("""{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted"}"""))
        val spent = engine(StreamDialer(broke), http = http)
        assertEquals(EngineAvailability.Available, spent.prepare("he") { })
        listen(spent, streamAudio(1, ends = false))
        assertEquals(EngineAvailability.Available, spent.prepare("he") { })
        assertEquals(4, http.requests.size)
    }

    @Test
    fun `a connection that stops answering pings is closed and reported as no internet`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(answersPings = false)
        val started = testScheduler.timeSource.markNow()
        val heard = listen(engine(StreamDialer(socket), pingSeconds = 0.05, pongSeconds = 0.2), streamAudio(1, ends = false))
        assertEquals(CloudSpeechError.Offline, heard.error)
        assertTrue(started.elapsedNow() < 5.seconds)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a connection that answers its pings is pinged again and stays open`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(afterEnd = listOf(frames.last()))
        val microphone = AudioFeed()
        microphone.add(listOf(FloatArray(1_600) { 0.05f }))
        val soniox = engine(StreamDialer(socket), pingSeconds = 0.05, pongSeconds = 5.0)
        var heard: StreamHeard? = null
        val listening = launch { heard = listen(soniox, microphone.flow) }
        // Waited for, not slept for: a busy CI runner got to the first
        // ping only after a 500 ms sleep was over. A second ping is only
        // sent once the first was answered.
        assertTrue(waitUntil { socket.pings >= 2 })
        microphone.finish()
        listening.join()
        assertNull(heard?.error)
    }

    @Test
    fun `no connection at all, or no key, ends the stream with the reason`() = runTest {
        assertEquals(CloudSpeechError.Offline, listen(engine(StreamDialer(null)), streamAudio(1)).error)
        val dialer = StreamDialer(StreamSocket())
        assertEquals(CloudSpeechError.KeyMissing, listen(engine(dialer, key = "  "), streamAudio(1)).error)
        assertTrue(dialer.calls.isEmpty())
    }

    @Test
    fun `stopping captions closes the connection without an error and leaves the line as it was`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(onConfig = listOf(frames[0]))
        val soniox = engine(StreamDialer(socket))
        val heard = StreamHeard()
        val listening = launch { listen(soniox, streamAudio(1, ends = false), heard = heard) }
        assertTrue(waitUntil { socket.sentTexts.size == 1 })
        delay(50.milliseconds)
        listening.cancel()
        listening.join()
        assertNull(heard.error)
        assertTrue(heard.tokens.all { !it.isFinal })
        assertTrue(waitUntil { socket.isClosed })
    }

    @Test
    fun `the key is checked once with Soniox and trusted for a while, a key Soniox turns down, or one out of credit, says so`() = runTest {
        val http = FakeCloudHTTP(
            keyChecks = listOf(
                FakeCloudHTTP.Answer.Status(200, """{"files":[],"next_page_cursor":null}"""),
                FakeCloudHTTP.Answer.Status(401, ""),
                FakeCloudHTTP.Answer.Status(402, ""),
            ),
            timeSource = testScheduler.timeSource,
        )
        val soniox = engine(StreamDialer(null), http = http)
        assertEquals(EngineAvailability.Available, soniox.prepare("he") { })
        assertEquals(EngineAvailability.Available, soniox.prepare("he") { })
        assertEquals(1, http.requests.size)
        assertEquals(SonioxSpeech.keyCheckRequest("sx-test"), http.requests.first())

        val rejected = engine(StreamDialer(null), http = http)
        val why = assertIs<EngineAvailability.Unavailable>(rejected.prepare("he") { }, "a turned-down key was accepted").why
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, why.kind)
        val broke = assertIs<EngineAvailability.Unavailable>(rejected.prepare("he") { }, "a key out of credit was accepted").why
        assertEquals(EngineUnavailability.Kind.CloudOutOfCredit, broke.kind)

        val none = assertIs<EngineAvailability.Unavailable>(engine(StreamDialer(null), key = null).prepare("he") { }, "no key was accepted").why
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, none.kind)
    }

    @Test
    fun `Soniox gets the engine that keeps one connection open, the other services get one request per sentence`() {
        val dialer = StreamDialer(null)
        assertEquals(CloudProvider.Soniox, (CloudProvider.Soniox.engine(connector = dialer, apiKey = { "k" }) as? CloudStreamEngine)?.provider)
        val deepgram = CloudProvider.Deepgram.engine(connector = dialer, apiKey = { "k" }) as? CloudSpeechEngine
        assertTrue(deepgram?.provider == CloudProvider.Deepgram && deepgram.model == DeepgramSpeech.MODEL)
        val openRouter = CloudProvider.OpenRouter.engine(model = CloudSpeech.models.last(), connector = dialer, apiKey = { "k" }) as? CloudSpeechEngine
        assertTrue(openRouter?.provider == CloudProvider.OpenRouter && openRouter.model == CloudSpeech.models.last())
    }

    @Test
    fun `Soniox is a cloud engine that names its service and model`() = runTest {
        val soniox = engine(StreamDialer(null))
        assertEquals(TranscriptionEngineKind.Cloud, soniox.kind)
        assertEquals(CloudProvider.Soniox, soniox.provider)
        assertEquals("stt-rt-v5", soniox.model)
    }
}
