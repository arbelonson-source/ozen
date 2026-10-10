package com.arbelonson.ozen.core

import java.io.File
import java.util.Base64
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun repositoryText(path: String): String {
    val root = File(assertNotNull(System.getProperty("ozen.fixtures"))).parentFile.parentFile
    return File(root, path).readText(Charsets.UTF_8)
}

class CloudSpeechEngineTest {
    private val chunk = 1_024

    private fun speech(seconds: Double): List<FloatArray> =
        List((seconds * 16_000).toInt() / chunk) { FloatArray(chunk) { index -> 0.05f * sin(index.toFloat() * 0.3f) } }

    private fun silence(seconds: Double): List<FloatArray> =
        List((seconds * 16_000).toInt() / chunk) { FloatArray(chunk) }

    private fun TestScope.http(
        answers: List<FakeCloudHTTP.Answer> = emptyList(),
        keyChecks: List<FakeCloudHTTP.Answer> = emptyList(),
    ) = FakeCloudHTTP(answers, keyChecks, testScheduler.timeSource)

    private fun TestScope.engine(http: FakeCloudHTTP, key: String? = "sk-test", pause: Double = 0.001) =
        CloudSpeechEngine(http = http, failedSegmentPauseSeconds = pause, timeSource = testScheduler.timeSource, apiKey = { key })

    private fun TestScope.providerEngine(provider: CloudProvider, http: FakeCloudHTTP, key: String) =
        CloudSpeechEngine(
            provider = provider,
            http = http,
            failedSegmentPauseSeconds = 0.001,
            timeSource = testScheduler.timeSource,
            apiKey = { key },
        )

    private suspend fun transcribe(engine: CloudSpeechEngine, chunks: List<FloatArray>): List<TranscriptToken> {
        val feed = AudioFeed()
        feed.add(chunks)
        feed.finish()
        return engine.stream("he", feed.flow).toList()
    }

    private fun secondsSent(request: CloudHTTPRequest): Double {
        val json = Json.parseToJsonElement(String(request.body ?: ByteArray(0), Charsets.UTF_8)).jsonObject
        val content = assertNotNull(json["messages"]).jsonArray.first().jsonObject["content"]!!.jsonArray
        val data = content.last().jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        val audio = Base64.getDecoder().decode(data)
        return (audio.size - 44).toDouble() / 2 / 16_000
    }

    @Test
    fun `a sentence and a pause become one finished line`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("A: שלום לכולם")))
        val tokens = transcribe(engine(http), speech(1.0) + silence(1.0))
        val finals = tokens.filter { it.isFinal }
        assertEquals(listOf("שלום לכולם"), finals.map { it.text })
        val request = assertNotNull(http.transcriptionRequests.lastOrNull())
        assertEquals("Bearer sk-test", request.headers["Authorization"])
    }

    @Test
    fun `a pause finishes the line while the conversation is still going`() = runTest(timeout = 1.minutes) {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום לכולם")))
        val feed = AudioFeed()
        val tokens = engine(http).stream("he", feed.flow)
        feed.add(speech(1.0) + silence(1.0))
        val started = testScheduler.timeSource.markNow()
        val closer = launch {
            delay(5.seconds)
            feed.finish()
        }
        val finished = tokens.first { it.isFinal }.text
        closer.cancel()
        feed.finish()
        assertEquals("שלום לכולם", finished)
        assertTrue(started.elapsedNow() < 5.seconds)
    }

    @Test
    fun `the audio sent is the sentence, not the silence around it`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום")))
        transcribe(engine(http), silence(2.0) + speech(1.0) + silence(2.0))
        // An engine that looks before all the audio is in sends a live pass
        // first (2.04 s with the pause not yet reached); the line is the last.
        val sent = http.transcriptionRequests.map { secondsSent(it) }
        val line = assertNotNull(sent.lastOrNull())
        assertTrue(line > 1.0)
        assertTrue(line < 2.0)
        assertTrue(sent.all { it < 2.3 }, "$sent")
    }

    @Test
    fun `the half second before the speech goes with it, so its first syllable isn't clipped`() = runTest(timeout = 1.minutes) {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום")))
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(silence(2.0))
        // Long enough for the engine to look at the quiet several times and
        // trim it, as it does while a room is silent.
        delay(400.milliseconds)
        feed.add(speech(1.0) + silence(2.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        val request = assertNotNull(http.transcriptionRequests.firstOrNull())
        assertTrue(secondsSent(request) > 1.6)
    }

    @Test
    fun `a line never runs past 28 seconds, even when more than that is waiting`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום")))
        // Talk: syllables with short dips, which a steady tone is not.
        val talk = List(40 * 16_000 / chunk) { index ->
            val level = if (index % 6 == 5) 0.001f else 0.05f
            FloatArray(chunk) { level * sin(it.toFloat() * 0.3f) }
        }
        transcribe(engine(http), talk + silence(1.0))
        val sent = http.transcriptionRequests.map { secondsSent(it) }
        assertTrue(sent.size >= 2)
        assertTrue(sent.all { it <= CloudSpeechEngine.MAX_UTTERANCE_SECONDS }, "$sent")
    }

    private fun pause(path: String, after: String): Double {
        val line = repositoryText(path).split("\n").first { it.trim().startsWith(after) }
        return line.split("=")[1].split("#")[0].trim().toDouble()
    }

    @Test
    fun `a line ends after the same quiet as on the home computer and the phone's own model`() {
        assertEquals(CloudSpeechEngine.PAUSE_SECONDS, pause("server/ozen_server.py", "pause = "))
        assertEquals(CloudSpeechEngine.PAUSE_SECONDS, pause("Sources/OzenPlatform/WhisperKitEngine.swift", "private let pauseSeconds = "))
    }

    @Test
    fun `two voices in one reply become two lines`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("A: מה שלומך?\nB: טוב, תודה")))
        val finals = transcribe(engine(http), speech(1.5) + silence(1.0)).filter { it.isFinal }
        assertEquals(listOf("מה שלומך?", "טוב, תודה"), finals.map { it.text })
        assertEquals(2, finals.map { it.utteranceID }.toSet().size)
        assertEquals(listOf(false, true), finals.map { it.startsNewSpeakerTurn })
    }

    @Test
    fun `the same sentence loop works through Deepgram - its key check, its reply, two voices as two lines`() = runTest {
        val body = File(System.getProperty("ozen.fixtures"), "cloud/deepgram-two-speakers.json").readText(Charsets.UTF_8)
        val http = http(
            listOf(FakeCloudHTTP.Answer.Status(200, body)),
            listOf(FakeCloudHTTP.Answer.Status(200, """{"projects":[]}""")),
        )
        val engine = providerEngine(CloudProvider.Deepgram, http, "dg-test")
        assertEquals(DeepgramSpeech.MODEL, engine.model)
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        assertEquals(DeepgramSpeech.keyURL, http.requests.first().url)
        val finals = transcribe(engine, speech(1.5) + silence(1.0)).filter { it.isFinal }
        assertEquals(listOf("מה שלומך?", "טוב, תודה. ואתה?"), finals.map { it.text })
        val sent = assertNotNull(http.transcriptionRequests.lastOrNull())
        assertEquals("api.deepgram.com", sent.url.host)
        assertEquals("Token dg-test", sent.headers["Authorization"])
    }

    @Test
    fun `Groq gets each sentence once, when it ends, since it bills a request as at least ten seconds, OpenAI gets the live guesses as the others do`() = runTest {
        val answer = FakeCloudHTTP.Answer.Status(200, """{"text":"שלום לכולם"}""")
        val groqHTTP = http(listOf(answer))
        val openAIHTTP = http(listOf(answer))
        val groq = providerEngine(CloudProvider.Groq, groqHTTP, "gsk-test")
        val openAI = providerEngine(CloudProvider.OpenAI, openAIHTTP, "sk-test")
        val groqFeed = AudioFeed()
        val openAIFeed = AudioFeed()
        val groqHeard = collecting(groq.stream("he", groqFeed.flow))
        val openAIHeard = collecting(openAI.stream("he", openAIFeed.flow))
        for (piece in speech(3.0)) {
            groqFeed.add(listOf(piece))
            openAIFeed.add(listOf(piece))
        }
        assertTrue(eventually { openAIHTTP.transcriptionRequests.size == 1 })
        delay(200.milliseconds)
        assertTrue(groqHTTP.transcriptionRequests.isEmpty())
        for (piece in silence(1.0)) {
            groqFeed.add(listOf(piece))
            openAIFeed.add(listOf(piece))
        }
        groqFeed.finish()
        openAIFeed.finish()
        groqHeard.job.join()
        openAIHeard.job.join()
        assertNull(groqHeard.error)
        assertNull(openAIHeard.error)
        assertEquals(listOf("שלום לכולם"), groqHeard.tokens.map { it.text })
        assertTrue(groqHeard.tokens.all { it.isFinal })
        assertEquals(1, groqHTTP.transcriptionRequests.size)
        assertEquals("api.groq.com", groqHTTP.transcriptionRequests.first().url.host)
        assertEquals(2, openAIHTTP.transcriptionRequests.size)
        assertEquals("Bearer sk-test", openAIHTTP.transcriptionRequests.last().headers["Authorization"])
    }

    @Test
    fun `a quiet room sends nothing and shows nothing`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("תודה")))
        val tokens = transcribe(engine(http), silence(3.0))
        assertTrue(tokens.isEmpty())
        assertTrue(http.transcriptionRequests.isEmpty())
    }

    @Test
    fun `words appear while someone is still talking, then the line is finished`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום"), FakeCloudHTTP.Answer.Text("שלום לכולם")))
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        val received = collected.tokens
        assertEquals(listOf("שלום", "שלום לכולם"), received.map { it.text })
        assertEquals(listOf(false, true), received.map { it.isFinal })
        assertEquals(1, received.map { it.utteranceID }.toSet().size)
    }

    @Test
    fun `while two people are still talking their words show as one live line, a space apart, finished, they become two`() = runTest {
        val http = http(
            listOf(FakeCloudHTTP.Answer.Text("A: מה שלומך?\nB: טוב"), FakeCloudHTTP.Answer.Text("A: מה שלומך?\nB: טוב, תודה")),
        )
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        val received = collected.tokens
        assertEquals(listOf("מה שלומך? טוב", "מה שלומך?", "טוב, תודה"), received.map { it.text })
        assertEquals(listOf(false, true, true), received.map { it.isFinal })
    }

    @Test
    fun `the next sentence coming back empty doesn't bring back the last sentence's live words`() = runTest {
        val http = http(
            listOf(FakeCloudHTTP.Answer.Text("שלום"), FakeCloudHTTP.Answer.Text("שלום לכולם"), FakeCloudHTTP.Answer.Text("")),
        )
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        assertTrue(eventually { http.transcriptionRequests.size == 2 })
        feed.add(speech(1.0) + silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        assertEquals(listOf("שלום", "שלום לכולם"), collected.tokens.map { it.text })
        assertTrue(http.transcriptionRequests.size >= 3)
    }

    @Test
    fun `a live request that fails is not tried again, only a final one is worth a second request`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Offline, FakeCloudHTTP.Answer.Text("שלום לכולם")))
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        // A retried live pass would have shown the answer meant for the
        // final one as a live line, and asked a third time.
        assertEquals(listOf(true), collected.tokens.map { it.isFinal })
        assertEquals(2, http.transcriptionRequests.size)
    }

    @Test
    fun `a final request that fails once is tried again`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Offline, FakeCloudHTTP.Answer.Text("שלום")))
        val finals = transcribe(engine(http), speech(1.0) + silence(1.0)).filter { it.isFinal }
        assertEquals(listOf("שלום"), finals.map { it.text })
        assertEquals(2, http.transcriptionRequests.size)
    }

    @Test
    fun `when the final request never gets through, the words already shown stay`() = runTest {
        val http = http(
            listOf(
                FakeCloudHTTP.Answer.Text("שלום"),
                FakeCloudHTTP.Answer.Status(503, "{}"),
                FakeCloudHTTP.Answer.Status(503, "{}"),
                FakeCloudHTTP.Answer.Text(""),
            ),
        )
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        assertEquals(listOf("שלום", "שלום"), collected.tokens.map { it.text })
        assertEquals(listOf(false, true), collected.tokens.map { it.isFinal })
    }

    @Test
    fun `a final request that fails after words were shown is tried again, so the end of the sentence isn't dropped behind them`() = runTest {
        val http = http(
            listOf(
                FakeCloudHTTP.Answer.Text("שלום"),
                FakeCloudHTTP.Answer.Status(503, "{}"),
                FakeCloudHTTP.Answer.Status(503, "{}"),
                FakeCloudHTTP.Answer.Text("שלום מה שלומך היום"),
            ),
        )
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        assertEquals(listOf("שלום", "שלום מה שלומך היום"), collected.tokens.map { it.text })
        assertEquals(listOf(false, true), collected.tokens.map { it.isFinal })
    }

    @Test
    fun `giving up after words were shown finishes that line with the cut-off mark, since the rest of the sentence is lost`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("שלום מה"), FakeCloudHTTP.Answer.Offline))
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(3.0))
        assertTrue(eventually { http.transcriptionRequests.size == 1 })
        feed.add(silence(1.0))
        feed.finish()
        collected.job.join()
        val received = collected.tokens
        assertEquals(listOf("שלום מה", "שלום מה" + CaptionStabilizer.CUT_OFF_MARK), received.map { it.text })
        assertEquals(listOf(false, true), received.map { it.isFinal })
        assertEquals(1, received.map { it.utteranceID }.toSet().size)
    }

    @Test
    fun `a rejected key ends the stream at once`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Status(401, """{"error":{"message":"No auth credentials found"}}""")))
        val failure = assertFailsWith<CloudSpeechError> { transcribe(engine(http), speech(1.0) + silence(1.0)) }
        assertEquals(CloudSpeechError.KeyRejected, failure)
        assertEquals(1, http.transcriptionRequests.size)
    }

    @Test
    fun `several failures in a row end the stream`() = runTest {
        // Nothing ever gets shown for this utterance, so every failed
        // final segment is retried (see shortUtteranceFinalFailureRetried)
        // rather than silently moved past -- eventually the retries
        // themselves are the "several failures in a row" that give up.
        val http = http(listOf(FakeCloudHTTP.Answer.Offline))
        val failure = assertFailsWith<CloudSpeechError> { transcribe(engine(http), speech(1.0) + silence(1.0)) }
        assertEquals(CloudSpeechError.Offline, failure)
        // Two attempts per retried final segment, until failuresInARow
        // reaches the limit.
        assertEquals(CloudSpeechEngine.FAILURES_BEFORE_STOPPING * 2, http.transcriptionRequests.size)
    }

    @Test
    fun `a line that gets through starts the count of failures over`() = runTest(timeout = 1.minutes) {
        // Three failed tries at the first sentence (two requests each),
        // then it gets through; the next sentence then gets a whole new run
        // of failures before the stream gives up.
        val failedTries = (CloudSpeechEngine.FAILURES_BEFORE_STOPPING - 1) * 2
        val http = http(List(failedTries) { FakeCloudHTTP.Answer.Offline } + listOf(FakeCloudHTTP.Answer.Text("שלום"), FakeCloudHTTP.Answer.Offline))
        val feed = AudioFeed()
        val collected = collecting(engine(http).stream("he", feed.flow))
        feed.add(speech(1.0) + silence(1.0))
        assertTrue(eventually { http.transcriptionRequests.size == failedTries + 1 })
        feed.add(speech(1.0) + silence(1.0))
        feed.finish()
        collected.job.join()
        assertEquals(CloudSpeechError.Offline, collected.error)
        assertEquals(failedTries + 1 + CloudSpeechEngine.FAILURES_BEFORE_STOPPING * 2, http.transcriptionRequests.size)
    }

    @Test
    fun `the same audio is sent again only after a growing pause, not in a burst`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Offline))
        runCatching { transcribe(engine(http, pause = 0.25), speech(1.0) + silence(1.0)) }
        val times = http.transcriptionSentAt
        assertEquals(CloudSpeechEngine.FAILURES_BEFORE_STOPPING * 2, times.size)
        // Each failed segment is two attempts, 0.4 s apart; between one
        // segment's second attempt and the next segment's first, the pause
        // grows: 1, 2, 3 steps.
        if (times.size != 8) return@runTest
        assertTrue(times[1] - times[0] >= 0.35.seconds)
        assertTrue(times[2] - times[1] >= 0.2.seconds)
        assertTrue(times[4] - times[3] >= 0.45.seconds)
        assertTrue(times[6] - times[5] >= 0.7.seconds)
    }

    @Test
    fun `a short utterance's final request failing outright, with nothing shown yet, is retried rather than lost`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Offline, FakeCloudHTTP.Answer.Offline, FakeCloudHTTP.Answer.Text("שלום")))
        val engine = engine(http)
        val tokens = transcribe(engine, speech(1.0) + silence(1.0))
        // Too short for a live pass (under livePassSeconds); the first
        // final attempt-pair fails outright with nothing shown, so it must
        // retry rather than move on with the words lost.
        assertEquals(listOf("שלום"), tokens.map { it.text })
        assertEquals(3, http.transcriptionRequests.size)
    }

    @Test
    fun `the names list read back on its own is not a line`() = runTest {
        val http = http(listOf(FakeCloudHTTP.Answer.Text("דנה, יוסי, מרים")))
        val engine = engine(http)
        engine.setVocabulary(listOf("דנה", "יוסי", "מרים"))
        val tokens = transcribe(engine, speech(1.0) + silence(1.0))
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `no key - nothing is sent and the screen asks for one`() = runTest {
        val http = http()
        val availability = engine(http, key = "  ").checkAvailability("he")
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, availability.unavailability?.kind)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `the key is checked once, and again only when it changes`() = runTest {
        val http = http()
        val key = KeyBox("sk-one")
        val engine = CloudSpeechEngine(http = http, timeSource = testScheduler.timeSource, apiKey = { key.value })
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        assertEquals(1, http.requests.size)
        key.value = "sk-two"
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        assertEquals(2, http.requests.size)
        assertEquals("Bearer sk-two", http.requests.last().headers["Authorization"])
    }

    @Test
    fun `an old approval is not trusted - a check after the internet dropped really asks`() = runTest {
        val http = http(keyChecks = listOf(FakeCloudHTTP.Answer.Status(200, "{}"), FakeCloudHTTP.Answer.Offline))
        val engine = CloudSpeechEngine(http = http, approvalSeconds = 0.05, timeSource = testScheduler.timeSource, apiKey = { "sk-test" })
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        delay(300.milliseconds)
        assertEquals(EngineUnavailability.Kind.NoInternet, engine.checkAvailability("he").unavailability?.kind)
    }

    @Test
    fun `captions coming back from the cloud keep the approval fresh, so a restart right after doesn't ask again`() = runTest {
        val http = http(
            listOf(FakeCloudHTTP.Answer.Text("A: שלום")),
            listOf(FakeCloudHTTP.Answer.Status(200, "{}"), FakeCloudHTTP.Answer.Offline),
        )
        val engine = CloudSpeechEngine(
            http = http,
            failedSegmentPauseSeconds = 0.001,
            approvalSeconds = 1.0,
            timeSource = testScheduler.timeSource,
            apiKey = { "sk-test" },
        )
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        delay(700.milliseconds)
        transcribe(engine, speech(1.0) + silence(1.0))
        delay(500.milliseconds)
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
    }

    @Test
    fun `a key check that fails says why`() = runTest {
        val rejected = engine(http(keyChecks = listOf(FakeCloudHTTP.Answer.Status(401, "{}")))).checkAvailability("he")
        val spent = engine(http(keyChecks = listOf(FakeCloudHTTP.Answer.Status(200, """{"data":{"limit_remaining":0}}""")))).checkAvailability("he")
        val offline = engine(http(keyChecks = listOf(FakeCloudHTTP.Answer.Offline))).checkAvailability("he")
        val busy = engine(http(keyChecks = listOf(FakeCloudHTTP.Answer.Status(502, "{}")))).checkAvailability("he")
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, rejected.unavailability?.kind)
        assertEquals(EngineUnavailability.Kind.CloudOutOfCredit, spent.unavailability?.kind)
        assertEquals(EngineUnavailability.Kind.NoInternet, offline.unavailability?.kind)
        assertEquals(EngineUnavailability.Kind.TemporarilyUnavailable, busy.unavailability?.kind)
    }

    @Test
    fun `a key turned down mid-conversation is checked again before the next start`() = runTest {
        val http = http(
            listOf(FakeCloudHTTP.Answer.Status(401, "{}")),
            listOf(FakeCloudHTTP.Answer.Status(200, "{}"), FakeCloudHTTP.Answer.Status(401, "{}")),
        )
        val engine = engine(http)
        assertEquals(EngineAvailability.Available, engine.checkAvailability("he"))
        val failure = assertFailsWith<CloudSpeechError> { transcribe(engine, speech(1.0) + silence(1.0)) }
        assertEquals(CloudSpeechError.KeyRejected, failure)
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, engine.checkAvailability("he").unavailability?.kind)
    }
}
