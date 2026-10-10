package com.arbelonson.ozen.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

private const val READY = SCRIPTED_READY

class HomeServerEngineTest {
    private fun TestScope.socket(helloReply: String?, afterEnd: List<String> = emptyList()) =
        ScriptedSocket(helloReply, afterEnd, testScheduler.timeSource)

    private fun TestScope.engine(
        socket: ScriptedSocket?,
        address: String = "10.0.0.5",
        token: String? = "1234",
        beam: Int? = null,
    ) = HomeServerEngine(
        address = address,
        token = { token },
        connector = ScriptedConnector(socket),
        handshakeSeconds = 1.0,
        client = "Ozen 36, iOS 18.2",
        beam = beam,
        timeSource = testScheduler.timeSource,
    )

    private fun TestScope.engineWith(
        socket: ScriptedSocket,
        handshakeSeconds: Double = 1.0,
        stallSeconds: Double = 35.0,
        pingSeconds: Double = 5.0,
        pongSeconds: Double = 8.0,
        approvalSeconds: Double = HomeServerEngine.DEFAULT_APPROVAL_SECONDS,
    ) = HomeServerEngine(
        address = "10.0.0.5",
        token = { "1234" },
        connector = ScriptedConnector(socket),
        handshakeSeconds = handshakeSeconds,
        stallSeconds = stallSeconds,
        pingSeconds = pingSeconds,
        pongSeconds = pongSeconds,
        approvalSeconds = approvalSeconds,
        timeSource = testScheduler.timeSource,
    )

    @Test
    fun `a hello that never finishes sending (a computer gone to sleep mid-connect) gives up within the handshake wait, not the system's minute`() = runTest {
        val socket = socket(READY)
        socket.setHangsOnHello(true)
        val started = testScheduler.timeSource.markNow()
        // Far above the 1 s handshake wait and far below the system's
        // minute: a CI simulator running every suite at once once held
        // the 1 s timer back for over 5 s.
        val answer = withTimeoutOrNull(30.seconds) { engine(socket).checkAvailability("he") }
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, answer?.unavailability?.kind)
        assertTrue(started.elapsedNow() < 20.seconds)
    }

    @Test
    fun `Test connection says connected with the time it took, a refused code, no answer, or nothing set up yet`() = runTest {
        val ok = engine(socket(READY)).checkAvailability("he")
        assertEquals(HomeServerCheck.Connected(42), HomeServerCheck.of(ok, 0.0424))
        val refused = engine(socket("""{"type":"error","code":"unauthorized","detail":""}""")).checkAvailability("he")
        assertEquals(HomeServerCheck.CodeRefused, HomeServerCheck.of(refused, 0.1))
        val silent = engine(socket(null)).checkAvailability("he")
        assertEquals(HomeServerCheck.Unreachable, HomeServerCheck.of(silent, 0.3))
        val noCode = engine(socket(READY), token = null).checkAvailability("he")
        assertEquals(HomeServerCheck.NotSetUp, HomeServerCheck.of(noCode, 0.0))
        val noAddress = engine(socket(READY), address = "").checkAvailability("he")
        assertEquals(HomeServerCheck.NotSetUp, HomeServerCheck.of(noAddress, 0.0))
    }

    private suspend fun TestScope.firstError(token: String?, address: String): Pair<EngineUnavailability.Kind?, List<String>> {
        val socket = socket(READY)
        val feed = AudioFeed()
        feed.add(listOf(FloatArray(1600) { 0.1f }))
        feed.finish()
        val heard = listen(engine(socket, address = address, token = token), feed.flow)
        return (heard.error as? EngineUnavailability)?.kind to socket.sentTexts
    }

    @Test
    fun `captions started with no code or no address end at once, saying which, without calling anyone`() = runTest {
        val (noCode, codeSent) = firstError(token = null, address = "10.0.0.5")
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, noCode)
        assertTrue(codeSent.isEmpty())
        val (blankCode, blankSent) = firstError(token = "", address = "10.0.0.5")
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, blankCode)
        assertTrue(blankSent.isEmpty())
        val (noAddress, addressSent) = firstError(token = "1234", address = "")
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, noAddress)
        assertTrue(addressSent.isEmpty())
    }

    @Test
    fun `a computer that turns the phone away for any reason but the code, or answers something else, is not answering, only a refused code needs a person`() = runTest {
        for (reply in listOf(
            """{"type":"error","code":"bad_request","detail":"hello expected"}""",
            """{"type":"error","code":"busy","detail":""}""",
            """{"type":"text","utterance":0,"text":"שלום","final":false}""",
            """{"type":"report_saved","name":"report.txt"}""",
            "not json",
        )) {
            val availability = engine(socket(reply)).checkAvailability("he")
            assertEquals(HomeServerCheck.Unreachable, HomeServerCheck.of(availability, 0.1), reply)
            val why = assertIs<EngineAvailability.Unavailable>(availability, "$reply was taken for a working computer").why
            assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, why.kind, reply)
        }
    }

    private suspend fun TestScope.runDeadPath(answering: Boolean): Pair<Throwable?, Int> {
        val socket = socket(READY)
        socket.setAnswersPings(answering)
        val server = engineWith(socket, pingSeconds = 0.05, pongSeconds = 1.0)
        val feed = AudioFeed()
        val quiet = FloatArray(1600) { 0.0005f }
        val feeding = launch {
            repeat(120) {
                feed.add(listOf(quiet))
                delay(25.milliseconds)
            }
            feed.finish()
        }
        try {
            val heard = listen(server, feed.flow)
            return heard.error to socket.pings
        } finally {
            feeding.cancel()
        }
    }

    @Test
    fun `a connection that stops answering pings is given up on within seconds, even in silence, one that answers is kept`() = runTest {
        val (dead, deadPings) = runDeadPath(answering = false)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (dead as? EngineUnavailability)?.kind)
        assertTrue((dead as? EngineUnavailability)?.detail?.contains("ping") == true, "$dead")
        assertEquals(1, deadPings)
        val (alive, alivePings) = runDeadPath(answering = true)
        assertNull(alive)
        assertTrue(alivePings >= 10)
    }

    @Test
    fun `an unanswered ping gives the connection up once its answer is overdue, not at the next ping after that`() = runTest {
        val socket = socket(READY)
        socket.setAnswersPings(false)
        // A ping every 2 s, overdue after 2.05 s: given up 2.05 s after the
        // ping, where waiting for the next ping's turn made it 4 s. Timed
        // from the ping: with every test starting at once, a test run can
        // hold everything up for most of a second (CI, 4 cores).
        val server = engineWith(socket, pingSeconds = 2.0, pongSeconds = 2.05)
        val feed = AudioFeed()
        val heard = listen(server, feed.flow)
        feed.finish()
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (heard.error as? EngineUnavailability)?.kind, "the connection was kept")
        val waited = assertNotNull(socket.firstPingAt).elapsedNow()
        assertTrue(waited >= 1.seconds && waited < 3.3.seconds, "$waited")
    }

    private suspend fun TestScope.runStuck(
        chunk: FloatArray,
        replyEvery: Int?,
        socket: ScriptedSocket = socket(READY),
        reply: (Int) -> String = { scriptedText(it, "", final = true) },
    ): Throwable? {
        val server = engineWith(socket, stallSeconds = 2.0)
        val feed = AudioFeed()
        val collected = collecting(server.stream("he", feed.flow))
        val quiet = FloatArray(1600) { 0.0005f }
        repeat(10) { feed.add(listOf(quiet)) }
        for (index in 0 until 40) {
            feed.add(listOf(chunk))
            if (replyEvery != null && index % replyEvery == 0) {
                waitUntil(1.0) { socket.sentBytes >= (10 + index + 1) * 3200 }
                socket.deliver(reply(index))
            }
        }
        feed.finish()
        collected.job.join()
        return collected.error
    }

    @Test
    fun `a server that stays connected but never answers speech is given up on, so the phone's own model can take over, silence alone never is`() = runTest {
        val loud = FloatArray(1600) { (0.3 * sin(it.toDouble() * 0.3)).toFloat() }
        val quiet = FloatArray(1600) { 0.0005f }
        val stuckSocket = socket(READY)
        val stuck = runStuck(loud, replyEvery = null, socket = stuckSocket)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (stuck as? EngineUnavailability)?.kind)
        assertTrue((stuck as? EngineUnavailability)?.detail?.contains("no reply") == true, "$stuck")
        // Given up on while the speech went on, not once the audio ended:
        // a hung computer still answers pings, and the audio never ends.
        assertFalse(stuckSocket.sentTexts.contains(HomeServer.END))
        // And only once stallSeconds of 16 kHz speech went out after the
        // first speech chunk: the 10 quiet chunks, that one, and 2 s more.
        assertEquals((10 + 1 + 20) * 3200, stuckSocket.sentBytes)
        assertNull(runStuck(quiet, replyEvery = null))
        assertNull(runStuck(loud, replyEvery = 5))
        val garbage = runStuck(loud, replyEvery = 5) { "<html>502 Bad Gateway</html>" }
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (garbage as? EngineUnavailability)?.kind)
    }

    @Test
    fun `a diagnostics report goes to the server, which says it kept it, a server that doesn't answer is a failure`() = runTest {
        val saving = socket(READY)
        saving.setReportReply("""{"type":"report_saved","name":"20260926-2100.txt"}""")
        assertTrue(engine(saving).sendReport("levels -40 dB", "he"))
        val sent = saving.sentTexts
        assertTrue(sent.firstOrNull()?.contains(""""purpose":"report"""") == true)
        assertTrue(sent.contains(HomeServer.report("levels -40 dB")))

        assertFalse(engine(socket(READY)).sendReport("x", "he"))
        assertFalse(engine(socket(READY), token = null).sendReport("x", "he"))
    }

    @Test
    fun `the Settings beam goes to the server in the hello, without one the hello leaves it to the server`() = runTest {
        val chosen = socket(READY)
        engine(chosen, beam = 2).checkAvailability("he")
        assertTrue(chosen.sentTexts.firstOrNull()?.contains(""""beam":2""") == true)

        val unset = socket(READY)
        engine(unset).checkAvailability("he")
        val hello = unset.sentTexts.firstOrNull() ?: ""
        assertTrue(hello.contains(""""type":"hello""""))
        assertFalse(hello.contains("beam"))
    }

    @Test
    fun `a name added while captions stream reaches the server at once, as a vocabulary frame`() = runTest {
        val socket = socket(READY)
        val server = engine(socket)
        val feed = AudioFeed()
        val collected = collecting(server.stream("he", feed.flow))
        feed.add(listOf(floatArrayOf(0.1f)))
        waitUntil(2.0) { socket.sentBytes != 0 }
        server.setVocabulary(listOf("Ruti"))
        assertEquals(HomeServer.vocabularyUpdate(listOf("Ruti")), socket.sentTexts.last())
        assertEquals("""{"terms":["Ruti"],"type":"vocabulary"}""", HomeServer.vocabularyUpdate(listOf("Ruti")))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
    }

    @Test
    fun `a name added while the connection is still being set up reaches the server once it is ready`() = runTest(timeout = 1.minutes) {
        val socket = socket(null)
        val server = engine(socket)
        server.setVocabulary(listOf("Avi"))
        val feed = AudioFeed()
        val collected = collecting(server.stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        assertTrue(socket.sentTexts.firstOrNull()?.contains("Avi") == true)
        server.setVocabulary(listOf("Avi", "Ruti"))
        socket.deliver(READY)
        feed.add(listOf(floatArrayOf(0.1f)))
        waitUntil(2.0) { socket.sentTexts.contains(HomeServer.vocabularyUpdate(listOf("Avi", "Ruti"))) }
        assertTrue(socket.sentTexts.contains(HomeServer.vocabularyUpdate(listOf("Avi", "Ruti"))))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
    }

    @Test
    fun `a server that answers ready is available, and the hello carries the code, language and names`() = runTest {
        val socket = socket(READY)
        val server = engine(socket)
        server.setVocabulary(listOf("Ruti"))
        assertEquals(EngineAvailability.Available, server.checkAvailability("he"))
        val hello = assertNotNull(socket.sentTexts.firstOrNull())
        assertTrue(hello.contains(""""token":"1234""""))
        assertTrue(hello.contains(""""language":"he""""))
        assertTrue(hello.contains("Ruti"))
        assertTrue(hello.contains(""""purpose":"check""""))
        assertTrue(hello.contains(""""client":"Ozen 36, iOS 18.2""""))
        assertTrue(socket.isClosed)
    }

    @Test
    fun `an approval is trusted for a quick restart, but a start long after the computer was last heard checks it again`() = runTest {
        val socket = socket(READY)
        val server = engineWith(socket, approvalSeconds = 0.3)
        assertEquals(EngineAvailability.Available, server.checkAvailability("he"))
        assertTrue(socket.isClosed)
        assertEquals(EngineAvailability.Available, server.checkAvailability("he"))
        delay(400.milliseconds)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, server.checkAvailability("he").unavailability?.kind)
    }

    @Test
    fun `a refused pairing code needs a person, a silent, missing or unparseable server is unreachable`() = runTest {
        val refused = socket("""{"type":"error","code":"unauthorized","detail":""}""")
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, engine(refused).checkAvailability("he").unavailability?.kind)
        assertEquals(
            EngineUnavailability.Kind.HomeServerRejected,
            engine(socket(READY), token = null).checkAvailability("he").unavailability?.kind,
        )

        val silent = socket(null)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, engine(silent).checkAvailability("he").unavailability?.kind)
        assertTrue(silent.isClosed)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, engine(null).checkAvailability("he").unavailability?.kind)
        assertEquals(
            EngineUnavailability.Kind.HomeServerUnreachable,
            engine(socket(READY), address = "http://x").checkAvailability("he").unavailability?.kind,
        )
    }

    @Test
    fun `replies become tokens - one id per line, a new id for the next, an empty final keeps the words, the end closes cleanly`() = runTest {
        val socket = socket(READY, afterEnd = listOf(scriptedText(1, "", final = true)))
        val feed = AudioFeed()
        val collected = collecting(engine(socket).stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }

        socket.deliver(scriptedText(0, "shalom", final = false))
        assertTrue(waitUntil { collected.tokens.size == 1 })
        val live = collected.tokens[0]
        socket.deliver(scriptedText(0, "shalom savta", final = true))
        assertTrue(waitUntil { collected.tokens.size == 2 })
        val final = collected.tokens[1]
        assertEquals(live.utteranceID, final.utteranceID)
        assertTrue(!live.isFinal && final.isFinal && final.text == "shalom savta")

        socket.deliver(scriptedText(0, "shalom savta", final = false))
        socket.deliver(scriptedText(1, "ma nishma", final = false))
        assertTrue(waitUntil { collected.tokens.size == 3 })
        val next = collected.tokens[2]
        assertTrue(next.utteranceID != final.utteranceID)
        assertEquals("ma nishma", next.text)

        feed.add(listOf(floatArrayOf(0.1f, 0.2f, 0.3f)))
        feed.finish()
        assertTrue(waitUntil { collected.tokens.size == 4 })
        val kept = collected.tokens[3]
        assertTrue(kept.utteranceID == next.utteranceID && kept.isFinal && kept.text == "ma nishma")
        collected.job.join()
        assertNull(collected.error)
        assertEquals(4, collected.tokens.size)
        assertEquals(6, socket.sentBytes)
        assertEquals(HomeServer.END, socket.sentTexts.last())
        assertTrue(socket.sentTexts.firstOrNull()?.contains(""""purpose":"captions"""") == true)
    }

    @Test
    fun `after a long conversation, a late frame for a line just finished still isn't shown again`() = runTest(timeout = 1.minutes) {
        val socket = socket(READY)
        val feed = AudioFeed()
        val collected = collecting(engine(socket).stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        // Past 200 finished lines the oldest are forgotten, to keep the
        // memory small; the latest hundred are still recognized.
        for (number in 0..200) {
            socket.deliver(scriptedText(number, "shalom $number", final = true))
            assertTrue(waitUntil { collected.tokens.size == number + 1 })
        }
        socket.deliver(scriptedText(200, "shalom 200", final = false))
        socket.deliver(scriptedText(150, "shalom 150", final = true))
        socket.deliver(scriptedText(201, "ma nishma", final = false))
        assertTrue(waitUntil { collected.tokens.size == 202 })
        assertEquals("ma nishma", collected.tokens[201].text)
        feed.finish()
        collected.job.join()
    }

    @Test
    fun `the server's text gets the phone's own checks - the names list read back, a TV sign-off and a thanks the model barely heard are dropped, real words stay`() = runTest {
        val socket = socket(READY)
        val server = engine(socket)
        server.setVocabulary(listOf("רותי", "אבי", "דני"))
        val feed = AudioFeed()
        val collected = collecting(server.stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        socket.deliver(scriptedText(0, "רותי, אבי, דני.", final = true))
        socket.deliver(scriptedText(1, "תודה שצפיתם", final = true))
        socket.deliver("""{"type":"text","utterance":2,"text":"תודה","final":true,"segments":[{"text":"תודה","no_speech":0.5,"logprob":-0.95,"compression":1.0}]}""")
        socket.deliver("""{"type":"text","utterance":3,"text":"תודה רבה, אבי","final":true,"segments":[{"text":"תודה רבה, אבי","no_speech":0.02,"logprob":-0.2,"compression":1.1}]}""")
        assertTrue(waitUntil { collected.tokens.isNotEmpty() })
        assertEquals("תודה רבה, אבי", collected.tokens[0].text)
        // A server that sends no segments has no numbers to say a thanks
        // was barely heard: only the text is checked, and it stays.
        socket.deliver(scriptedText(4, "תודה", final = true))
        feed.finish()
        collected.job.join()
        assertNull(collected.error)
        assertEquals(listOf("תודה רבה, אבי", "תודה"), collected.tokens.map { it.text })
    }

    @Test
    fun `stopping captions mid-sentence closes the connection, even though the server never hangs up`() = runTest {
        val socket = socket(READY)
        val feed = AudioFeed()
        val listening = launch { listen(engine(socket), feed.flow) }
        feed.add(listOf(floatArrayOf(0.1f)))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        listening.cancel()
        waitUntil(1.0) { socket.isClosed }
        assertTrue(socket.isClosed)
        feed.finish()
    }

    @Test
    fun `after the connection drops, the next availability check asks the server again instead of trusting the last answer`() = runTest {
        val socket = socket(READY)
        val server = engine(socket)
        assertEquals(EngineAvailability.Available, server.checkAvailability("he"))
        val feed = AudioFeed()
        val collected = collecting(server.stream("he", feed.flow))
        feed.add(listOf(floatArrayOf(0.1f)))
        waitUntil(2.0) { socket.sentBytes > 0 }
        socket.drop()
        collected.job.join()
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, server.checkAvailability("he").unavailability?.kind)
        feed.finish()
    }

    @Test
    fun `a line the connection cut before its final ends with the cut mark, a finished line is left alone`() = runTest {
        val socket = socket(READY)
        val feed = AudioFeed()
        val collected = collecting(engine(socket).stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        socket.deliver(scriptedText(0, "shalom savta", final = true))
        assertTrue(waitUntil { collected.tokens.size == 1 })
        socket.deliver(scriptedText(1, "tavi li et ha", final = false))
        assertTrue(waitUntil { collected.tokens.size == 2 })
        val live = collected.tokens[1]
        socket.drop()
        collected.job.join()
        assertEquals(3, collected.tokens.size)
        val cut = collected.tokens[2]
        assertEquals(live.utteranceID, cut.utteranceID)
        assertTrue(cut.isFinal && cut.text == "tavi li et ha" + CaptionStabilizer.CUT_OFF_MARK)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (collected.error as? EngineUnavailability)?.kind)
        feed.finish()
    }

    @Test
    fun `after the end, a line whose final never came ends with the cut mark`() = runTest {
        val socket = socket(READY)
        val feed = AudioFeed()
        val collected = collecting(engine(socket).stream("he", feed.flow))
        waitUntil(2.0) { socket.sentTexts.isNotEmpty() }
        socket.deliver(scriptedText(0, "ma nishma", final = false))
        assertTrue(waitUntil { collected.tokens.size == 1 })
        val live = collected.tokens[0]
        feed.finish()
        collected.job.join()
        assertEquals(2, collected.tokens.size)
        val cut = collected.tokens[1]
        assertEquals(live.utteranceID, cut.utteranceID)
        assertTrue(cut.isFinal && cut.text == "ma nishma" + CaptionStabilizer.CUT_OFF_MARK)
    }

    @Test
    fun `a connection that drops while she is still talking ends the stream as unreachable`() = runTest {
        val socket = socket(READY)
        val feed = AudioFeed()
        val collected = collecting(engine(socket).stream("he", feed.flow))
        feed.add(listOf(floatArrayOf(0.1f)))
        waitUntil(2.0) { socket.sentBytes > 0 }
        socket.drop()
        collected.job.join()
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, (collected.error as? EngineUnavailability)?.kind)
        feed.finish()
    }
}
