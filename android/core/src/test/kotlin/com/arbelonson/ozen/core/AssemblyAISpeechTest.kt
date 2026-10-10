package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

private val assemblyAIFrames: List<String> =
    File(System.getProperty("ozen.fixtures"), "cloud/assemblyai-two-speakers.jsonl").readText(Charsets.UTF_8)
        .split("\n").filter { it.isNotEmpty() }

private fun assemblyAIIsEnd(text: String): Boolean = text.contains("Terminate")

private fun assemblyAIQuery(url: URI): Map<String, String> {
    val settings = LinkedHashMap<String, String>()
    for (pair in (url.rawQuery ?: "").split("&").filter { it.isNotEmpty() }) {
        val parts = pair.split("=", limit = 2)
        val name = URLDecoder.decode(parts[0].replace("+", "%2B"), "UTF-8")
        if (name !in settings) settings[name] = URLDecoder.decode(parts.getOrElse(1) { "" }.replace("+", "%2B"), "UTF-8")
    }
    return settings
}

private fun assemblyAIList(json: String?): List<String>? =
    json?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { item -> item.jsonPrimitive.content } }.getOrNull() }

class AssemblyAISpeechTest {
    @Test
    fun `the settings go in the address - 16-bit audio at 16 kHz, Universal-3-6 Pro, the caption language, speakers, and the names as key terms`() {
        val url = AssemblyAISpeech.address("he", listOf("דנה", " דנה ", "ד\"ר כהן", "C&A=1+1"))
        assertEquals("wss", url.scheme)
        assertEquals("streaming.assemblyai.com", url.host)
        assertEquals("/v3/ws", url.path)
        val settings = assemblyAIQuery(url)
        assertEquals("16000", settings["sample_rate"])
        assertEquals("pcm_s16le", settings["encoding"])
        assertEquals("universal-3-6-pro", settings["speech_model"])
        assertEquals(listOf("he"), assemblyAIList(settings["language_codes"]))
        assertEquals("true", settings["speaker_labels"])
        assertEquals(listOf("דנה", "ד\"ר כהן", "C&A=1+1"), assemblyAIList(settings["keyterms_prompt"]))
        assertFalse(assertNotNull(url.rawQuery).contains("+"))
        assertTrue(AssemblyAISpeech.config("he", listOf("דנה")).isEmpty())
    }

    @Test
    fun `no names sends no key terms, a long list sends its first hundred, each within AssemblyAI's fifty letters`() {
        assertNull(assemblyAIQuery(AssemblyAISpeech.address("zh", emptyList()))["keyterms_prompt"])
        assertEquals(listOf("zh"), assemblyAIList(assemblyAIQuery(AssemblyAISpeech.address("zh", emptyList()))["language_codes"]))
        val long = "א".repeat(51)
        val sent = assertNotNull(
            assemblyAIList(assemblyAIQuery(AssemblyAISpeech.address("he", listOf(long) + (1..150).map { "שם$it" }))["keyterms_prompt"]),
        )
        assertEquals(100, sent.size)
        assertTrue(sent.all { it.codePointCount(0, it.length) <= 50 })
        assertEquals(long.take(VocabularyHints.maximumTermLength), sent.first())
        assertEquals((1..99).map { "שם$it" }, sent.drop(1))
    }

    @Test
    fun `the key goes in the header as it is, and the audio ends with Terminate`() {
        assertEquals(mapOf("Authorization" to "aai-test"), AssemblyAISpeech.headers("aai-test"))
        val end = Json.parseToJsonElement(AssemblyAISpeech.endMessage(9)).jsonObject
        assertEquals("Terminate", end["type"]?.jsonPrimitive?.content)
        assertEquals(1, end.size)
        assertFalse(AssemblyAISpeech.waitsForStart)
    }

    @Test
    fun `a turn still being said is a guess of its words, a finished turn is one final line in that voice, the end of the session ends the stream`() {
        val open = """{"type":"Turn","turn_order":3,"end_of_turn":false,"transcript":"שלום","speaker_label":"B","words":[{"text":"שלום","word_is_final":true},{"text":"לכו","word_is_final":false}]}"""
        assertEquals(
            CloudStreamReply.Tokens(
                listOf(
                    CloudStreamToken(text = " שלום", isFinal = false, speaker = "B"),
                    CloudStreamToken(text = " לכו", isFinal = false, speaker = "B"),
                ),
                finished = false,
            ),
            AssemblyAISpeech.reply(open, "he"),
        )
        val done = """{"type":"Turn","turn_order":3,"end_of_turn":true,"turn_is_formatted":true,"transcript":"שלום לכולם.","speaker_label":"B","words":[{"text":"שלום","word_is_final":true,"start":100,"end":400},{"text":"לכולם.","word_is_final":true,"start":400,"end":900}]}"""
        assertEquals(
            CloudStreamReply.Tokens(
                listOf(
                    CloudStreamToken(text = " שלום לכולם.", isFinal = true, speaker = "B", startMs = 100, endMs = 900),
                    CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true),
                ),
                finished = false,
            ),
            AssemblyAISpeech.reply(done, "he"),
        )
        val unknown = """{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"כן.","speaker_label":"UNKNOWN","words":[]}"""
        assertEquals(
            CloudStreamReply.Tokens(
                listOf(
                    CloudStreamToken(text = " כן.", isFinal = true),
                    CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true),
                ),
                finished = false,
            ),
            AssemblyAISpeech.reply(unknown, "he"),
        )
        val unformatted = """{"type":"Turn","end_of_turn":true,"turn_is_formatted":false,"transcript":"כן","words":[{"text":"כן","word_is_final":true}]}"""
        assertEquals(
            CloudStreamReply.Tokens(listOf(CloudStreamToken(text = " כן", isFinal = false)), finished = false),
            AssemblyAISpeech.reply(unformatted, "he"),
        )
        assertEquals(
            CloudStreamReply.Tokens(emptyList(), finished = true),
            AssemblyAISpeech.reply("""{"type":"Termination","audio_duration_seconds":5}""", "he"),
        )
    }

    @Test
    fun `Chinese words are joined without spaces`() {
        val done = """{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"你好世界。","words":[]}"""
        assertEquals(
            CloudStreamReply.Tokens(
                listOf(
                    CloudStreamToken(text = "你好世界。", isFinal = true),
                    CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true),
                ),
                finished = false,
            ),
            AssemblyAISpeech.reply(done, "zh"),
        )
        val open = """{"type":"Turn","end_of_turn":false,"words":[{"text":"你好","word_is_final":true},{"text":"世界","word_is_final":false}]}"""
        val guesses = assertNotNull(AssemblyAISpeech.reply(open, "zh") as? CloudStreamReply.Tokens, "no words").tokens
        assertEquals("你好世界", guesses.joinToString("") { it.text })
    }

    @Test
    fun `the start, heartbeats, speech starting and speaker revisions are skipped`() {
        for (frame in listOf(
            """{"type":"Begin","id":"x","expires_at":1}""",
            """{"type":"Heartbeat","total_audio_received_ms":1}""",
            """{"type":"SpeechStarted","timestamp":1}""",
            """{"type":"SpeakerRevision","revisions":[]}""",
            "<html>",
        )) {
            assertNull(AssemblyAISpeech.reply(frame, "he"), frame)
        }
    }

    @Test
    fun `AssemblyAI's documented reasons read as a key to fix, no credit even when also called unauthorized, an expired session as no connection, anything else the service's trouble`() {
        fun error(text: String): CloudStreamReply? = AssemblyAISpeech.reply("""{"error":"$text"}""", "he")
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.KeyRejected), error("Not Authorized"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.KeyRejected), error("Unauthorized Connection: Missing Authorization header"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error("Insufficient Funds"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error("Unauthorized Connection: insufficient account balance"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error("This feature is paid-only and requires you to add a credit card"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.Offline), error("Session Expired"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.ServerTrouble(500)), error("Client sent audio too fast"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.RateLimited), error("Unauthorized Connection: Too many concurrent sessions"))
        assertEquals(
            CloudStreamReply.Failure(CloudSpeechError.KeyRejected),
            AssemblyAISpeech.reply("""{"type":"Error","error":"Not Authorized"}""", "he"),
        )
    }

    @Test
    fun `a session AssemblyAI closes says why - no funds or a card needed is no credit even when called unauthorized, a refused key a key to fix, its own trouble its code, an expired or normal close is no connection`() {
        assertEquals(CloudSpeechError.KeyRejected, AssemblyAISpeech.failure(4001, "Not Authorized"))
        assertEquals(CloudSpeechError.OutOfCredit, AssemblyAISpeech.failure(4002, "Insufficient Funds"))
        assertEquals(CloudSpeechError.OutOfCredit, AssemblyAISpeech.failure(4003, "This feature is paid-only and requires you to add a credit card"))
        assertEquals(CloudSpeechError.OutOfCredit, AssemblyAISpeech.failure(1008, "Unauthorized Connection: insufficient account balance"))
        assertEquals(CloudSpeechError.KeyRejected, AssemblyAISpeech.failure(1008, "Unauthorized Connection: Missing Authorization header"))
        assertEquals(CloudSpeechError.ServerTrouble(3005), AssemblyAISpeech.failure(3005, "Internal error"))
        assertEquals(CloudSpeechError.RateLimited, AssemblyAISpeech.failure(3009, "Unauthorized Connection: Too many concurrent sessions"))
        assertNull(AssemblyAISpeech.failure(4008, "Session Expired"))
        assertNull(AssemblyAISpeech.failure(1000, ""))
    }

    @Test
    fun `the recorded conversation becomes a line per turn, each speaker change a new turn`() {
        val lines = CloudStreamLines()
        val shown = ArrayList<TranscriptToken>()
        var finished = false
        for ((index, frame) in assemblyAIFrames.withIndex()) {
            val reply = AssemblyAISpeech.reply(frame, "he") as? CloudStreamReply.Tokens ?: continue
            shown += lines.take(reply.tokens, index.toDouble())
            if (reply.finished) {
                shown += lines.finish(index.toDouble())
                finished = true
            }
        }
        assertTrue(finished)
        assertEquals(listOf("מה", "מה שלומך", "מה שלומך?", "טוב", "טוב תודה", "טוב, תודה. ואתה?", "מצוין", "מצוין."), shown.map { it.text })
        assertEquals(listOf(false, false, true, false, false, true, false, true), shown.map { it.isFinal })
        assertEquals(listOf(false, true, true), shown.filter { it.isFinal }.map { it.startsNewSpeakerTurn })
    }

    @Test
    fun `a finished turn that came without its transcript shows nothing`() {
        val lines = CloudStreamLines()
        val reply = AssemblyAISpeech.reply("""{"type":"Turn","end_of_turn":true,"turn_is_formatted":true}""", "en") as? CloudStreamReply.Tokens
        val tokens = assertNotNull(reply, "a finished turn is read as words").tokens
        assertTrue(lines.take(tokens, 1.0).isEmpty())
    }

    @Test
    fun `the key is checked by listing one transcript, with the key as it is`() {
        val request = AssemblyAISpeech.keyCheckRequest("aai-test")
        assertEquals("GET", request.method)
        assertEquals("https://api.assemblyai.com/v2/transcript?limit=1", request.url.toString())
        assertEquals("aai-test", request.headers["Authorization"])
        assertEquals(CloudSpeechError.KeyRejected, AssemblyAISpeech.failure(CloudHTTPResponse(401, ByteArray(0))))
        assertEquals(CloudSpeechError.KeyRejected, AssemblyAISpeech.failure(CloudHTTPResponse(403, ByteArray(0))))
        assertEquals(CloudSpeechError.OutOfCredit, AssemblyAISpeech.failure(CloudHTTPResponse(402, ByteArray(0))))
        assertEquals(CloudSpeechError.RateLimited, AssemblyAISpeech.failure(CloudHTTPResponse(429, ByteArray(0))))
        assertEquals(CloudSpeechError.ServerTrouble(500), AssemblyAISpeech.failure(CloudHTTPResponse(500, ByteArray(0))))
    }

    @Test
    fun `credit running out mid-sentence ends captions as no credit rather than no connection, the words on screen marked cut, an expired session is no connection`() = runTest(timeout = 1.minutes) {
        for ((closing, expected) in listOf(
            SocketClosed(4002, "Insufficient Funds") to CloudSpeechError.OutOfCredit,
            SocketClosed(4008, "Session Expired") to CloudSpeechError.Offline,
        )) {
            val socket = StreamSocket(endsAudio = ::assemblyAIIsEnd)
            socket.deliver(assemblyAIFrames[1])
            val engine = AssemblyAIEngine(connector = StreamDialer(socket), timeSource = testScheduler.timeSource, apiKey = { "aai-test" })
            val heard = StreamHeard()
            listen(engine, streamAudio(2, ends = false), heard = heard) { socket.drop(closing) }
            assertEquals(expected, heard.error)
            assertEquals(listOf("מה", CaptionStabilizer.markingCutOff("מה")), heard.tokens.map { it.text })
        }
    }

    @Test
    fun `AssemblyAI streams, writes ten caption languages but not Ukrainian or Amharic, and keeps its own key`() {
        for (code in listOf("he", "en", "ar", "ru", "fr", "es", "de", "pt", "zh", "hi")) {
            assertTrue(CloudProvider.AssemblyAI.covers(code), code)
        }
        assertTrue(!CloudProvider.AssemblyAI.covers("uk") && !CloudProvider.AssemblyAI.covers("am"))
        assertEquals("AssemblyAI", CloudProvider.AssemblyAI.displayName)
        assertEquals(listOf("universal-3-6-pro"), CloudProvider.AssemblyAI.models)
        assertEquals("com.arbelonson.ozen.cloud.assemblyAI", CloudProvider.AssemblyAI.keychainService)
        assertTrue(CloudProvider.AssemblyAI.streams)
        assertEquals(AssemblyAISpeech.keyURL, CloudProvider.AssemblyAI.keyCheckRequest("k").url)
        val engine = CloudProvider.AssemblyAI.engine(connector = StreamDialer(null), apiKey = { "k" }) as? CloudStreamEngine
        assertTrue(engine?.provider == CloudProvider.AssemblyAI && engine.model == "universal-3-6-pro")
    }

    @Test
    fun `a conversation streams over one connection opened with the settings in its address and the key in its header, no settings message, the audio at once, and a line per turn back`() = runTest(timeout = 1.minutes) {
        val socket = StreamSocket(onConfig = assemblyAIFrames.dropLast(2), afterEnd = assemblyAIFrames.takeLast(2), endsAudio = ::assemblyAIIsEnd)
        val dialer = StreamDialer(socket)
        val engine = AssemblyAIEngine(connector = dialer, timeSource = testScheduler.timeSource, apiKey = { "aai-test" })
        engine.setVocabulary(listOf("דנה"))
        val heard = listen(engine, streamAudio(4))
        assertNull(heard.error)
        assertEquals(listOf("מה שלומך?", "טוב, תודה. ואתה?", "מצוין."), heard.tokens.filter { it.isFinal }.map { it.text })
        assertEquals(listOf(AssemblyAISpeech.address("he", listOf("דנה"))), dialer.calls.map { it.url })
        assertEquals(mapOf("Authorization" to "aai-test"), dialer.calls.firstOrNull()?.headers)
        assertEquals(listOf(AssemblyAISpeech.endMessage(4)), socket.sentTexts)
        assertEquals(4, socket.sentChunks)
    }

    @Test
    fun `the microphone's 43 ms pieces reach AssemblyAI gathered to at least 50 ms, which it closes the session over otherwise (3007), the last filled out with silence`() = runTest(timeout = 1.minutes) {
        for ((pieces, expected) in listOf(10 to listOf(2_752, 2_752, 2_752, 2_752, 2_752), 3 to listOf(2_752, 1_600))) {
            val socket = StreamSocket(onConfig = emptyList(), afterEnd = assemblyAIFrames.takeLast(1), endsAudio = ::assemblyAIIsEnd)
            val engine = AssemblyAIEngine(connector = StreamDialer(socket), timeSource = testScheduler.timeSource, apiKey = { "k" })
            val microphone = AudioFeed()
            microphone.add(List(pieces) { FloatArray(688) { 0.05f } })
            microphone.finish()
            engine.stream("he", microphone.flow).collect { }
            assertEquals(expected, socket.sentChunkBytes, "$pieces")
        }
    }

    @Test
    fun `the key is checked before captions start, and one AssemblyAI turns down needs fixing`() = runTest {
        val http = FakeCloudHTTP(
            keyChecks = listOf(FakeCloudHTTP.Answer.Status(401, """{"error":"Authentication error, API token missing/invalid"}""")),
            timeSource = testScheduler.timeSource,
        )
        val engine = AssemblyAIEngine(http = http, connector = StreamDialer(null), timeSource = testScheduler.timeSource, apiKey = { "aai-bad" })
        val why = assertIs<EngineAvailability.Unavailable>(engine.prepare("he") { }, "a refused key was accepted").why
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, why.kind)
        assertEquals(listOf(AssemblyAISpeech.keyURL), http.requests.map { it.url })
    }
}
