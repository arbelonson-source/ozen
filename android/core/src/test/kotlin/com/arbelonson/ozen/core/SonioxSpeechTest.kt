package com.arbelonson.ozen.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val sonioxFrames: List<String> =
    File(System.getProperty("ozen.fixtures"), "cloud/soniox-two-speakers.jsonl").readText(Charsets.UTF_8)
        .split(Regex("[\n\r\u000B\u000C\u0085  ]")).filter { it.isNotEmpty() }

private fun sonioxSettings(config: String): JsonObject = Json.parseToJsonElement(config).jsonObject

private fun sonioxStrings(element: kotlinx.serialization.json.JsonElement?): List<String>? =
    element?.jsonArray?.map { it.jsonPrimitive.content }

class SonioxSpeechTest {
    @Test
    fun `the connection opens on Soniox's live address with the key in its header, not in a message`() {
        assertEquals("wss://stt-rt.soniox.com/transcribe-websocket", SonioxSpeech.streamURL.toString())
        assertEquals(mapOf("Authorization" to "Bearer sx-test"), SonioxSpeech.headers("sx-test"))
        assertEquals("", SonioxSpeech.END)
    }

    @Test
    fun `the settings name the live model, the microphone's own format, the caption language, speakers, line ends and the names list`() {
        val sent = sonioxSettings(SonioxSpeech.config("he", listOf("דנה", " דנה ", "ד\"ר כהן")))
        assertEquals("stt-rt-v5", sent["model"]?.jsonPrimitive?.content)
        assertEquals("pcm_s16le", sent["audio_format"]?.jsonPrimitive?.content)
        assertEquals(16_000, sent["sample_rate"]?.jsonPrimitive?.int)
        assertEquals(1, sent["num_channels"]?.jsonPrimitive?.int)
        assertEquals(listOf("he"), sonioxStrings(sent["language_hints"]))
        assertEquals(true, sent["enable_speaker_diarization"]?.jsonPrimitive?.boolean)
        assertEquals(true, sent["enable_endpoint_detection"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("דנה", "ד\"ר כהן"), sonioxStrings(sent["context"]?.jsonObject?.get("terms")))
        assertNull(sent["api_key"])

        val plain = sonioxSettings(SonioxSpeech.config("fr", emptyList()))
        assertEquals(listOf("fr"), sonioxStrings(plain["language_hints"]))
        assertNull(plain["context"])
    }

    @Test
    fun `a long names list is cut to the first hundred`() {
        val names = (1..150).map { "שם$it" }
        val sent = sonioxSettings(SonioxSpeech.config("he", names))
        assertEquals(names.take(100), sonioxStrings(sent["context"]?.jsonObject?.get("terms")))
    }

    @Test
    fun `a reply gives its words in order, each final or still changing, with the speaker as text or number`() {
        val frame = """{"tokens":[{"text":"שלום","is_final":true,"speaker":"1","start_ms":0,"end_ms":400},{"text":" לכולם","is_final":false,"speaker":2}],"final_audio_proc_ms":400,"total_audio_proc_ms":900}"""
        assertEquals(
            CloudStreamReply.Tokens(
                listOf(
                    CloudStreamToken(text = "שלום", isFinal = true, speaker = "1", startMs = 0, endMs = 400),
                    CloudStreamToken(text = " לכולם", isFinal = false, speaker = "2", startMs = null, endMs = null),
                ),
                finished = false,
            ),
            SonioxSpeech.reply(frame),
        )
        assertEquals(CloudStreamReply.Tokens(emptyList(), finished = true), SonioxSpeech.reply("""{"tokens":[],"finished":true}"""))
        assertNull(SonioxSpeech.reply("<html>"))
        assertNull(SonioxSpeech.reply("""{"hello":1}"""))
    }

    @Test
    fun `an error message names what went wrong, by its code`() {
        fun error(code: Int, type: String): CloudStreamReply? =
            SonioxSpeech.reply("""{"tokens":[],"error_code":$code,"error_type":"$type","error_message":"x"}""")
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.KeyRejected), error(401, "unauthenticated"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error(402, "organization_balance_exhausted"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error(402, "project_monthly_budget_exhausted"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.RateLimited), error(429, "rate_limit_exceeded"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.ServerTrouble(503)), error(503, "service_unavailable"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.ServerTrouble(400)), error(400, "invalid_request"))
    }

    @Test
    fun `the key is checked by listing one file, and the check's answer reads the same way`() {
        val request = SonioxSpeech.keyCheckRequest("sx-test")
        assertEquals("GET", request.method)
        assertEquals("https://api.soniox.com/v1/files?limit=1", request.url.toString())
        assertEquals("Bearer sx-test", request.headers["Authorization"])
        assertEquals(CloudSpeechError.KeyRejected, SonioxSpeech.failure(CloudHTTPResponse(401, ByteArray(0))))
        assertEquals(CloudSpeechError.KeyRejected, SonioxSpeech.failure(CloudHTTPResponse(403, ByteArray(0))))
        assertEquals(CloudSpeechError.OutOfCredit, SonioxSpeech.failure(CloudHTTPResponse(402, ByteArray(0))))
        assertEquals(CloudSpeechError.ServerTrouble(500), SonioxSpeech.failure(CloudHTTPResponse(500, ByteArray(0))))
    }

    @Test
    fun `eleven of the twelve caption languages, Soniox has no Amharic`() {
        assertEquals(setOf("he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"), SonioxSpeech.languages)
        assertTrue(CloudProvider.Soniox.covers("he"))
        assertTrue(!CloudProvider.Soniox.covers("am"))
        assertEquals(listOf("stt-rt-v5"), CloudProvider.Soniox.models)
        assertEquals("com.arbelonson.ozen.cloud.soniox", CloudProvider.Soniox.keychainService)
        assertTrue(CloudProvider.Soniox.streams && !CloudProvider.Deepgram.streams && !CloudProvider.OpenRouter.streams)
    }

    @Test
    fun `a two-person conversation becomes a line per turn - live words, the final line, and a new turn marked when the voice changes`() {
        val lines = CloudStreamLines()
        val shown = ArrayList<TranscriptToken>()
        for (frame in sonioxFrames) {
            val reply = SonioxSpeech.reply(frame) as? CloudStreamReply.Tokens
            if (reply == null) {
                assertTrue(false, "unreadable fixture frame")
                continue
            }
            shown += lines.take(reply.tokens, 1.0)
            if (reply.finished) shown += lines.finish(1.0)
        }
        assertEquals(listOf("מה", "מה שלומך", "מה שלומך?", "טוב, תודה.", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"), shown.map { it.text })
        assertEquals(listOf(false, false, true, false, false, true, false, true), shown.map { it.isFinal })
        assertEquals(listOf(false, false, false, true, true, true, true, true), shown.map { it.startsNewSpeakerTurn })
        val ids = shown.map { it.utteranceID }
        assertEquals(1, ids.slice(0..2).toSet().size)
        assertEquals(1, ids.slice(3..5).toSet().size)
        assertEquals(1, ids.slice(6..7).toSet().size)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `one voice going on past 28 seconds is cut at the next word, as every other engine cuts a line`() {
        val lines = CloudStreamLines()
        val shown = ArrayList<TranscriptToken>()
        shown += lines.take(listOf(CloudStreamToken(text = "אחת", isFinal = true, speaker = "1", startMs = 0, endMs = 500)), 1.0)
        shown += lines.take(listOf(CloudStreamToken(text = "ים", isFinal = true, speaker = "1", startMs = 27_900, endMs = 28_100)), 1.0)
        assertEquals("אחתים", shown.last().text)
        shown += lines.take(listOf(CloudStreamToken(text = " שתיים", isFinal = true, speaker = "1", startMs = 28_200, endMs = 28_600)), 1.0)
        val finals = shown.filter { it.isFinal }
        assertEquals(listOf("אחתים"), finals.map { it.text })
        assertEquals("שתיים", shown.last().text)
        assertEquals(false, shown.last().isFinal)
        assertEquals(false, shown.last().startsNewSpeakerTurn)
        assertNotEquals(finals.firstOrNull()?.utteranceID, shown.last().utteranceID)
    }

    @Test
    fun `a line that has reached 28 seconds to the millisecond is cut at the next word, a millisecond short, it goes on`() {
        for ((end, cut) in listOf(28_000 to true, 27_999 to false)) {
            val lines = CloudStreamLines()
            val shown = ArrayList<TranscriptToken>()
            shown += lines.take(listOf(CloudStreamToken(text = "אחת", isFinal = true, speaker = "1", startMs = 0, endMs = 500)), 1.0)
            shown += lines.take(listOf(CloudStreamToken(text = " שתיים", isFinal = true, speaker = "1", startMs = end - 400, endMs = end)), 1.0)
            assertEquals(if (cut) listOf("אחת") else emptyList(), shown.filter { it.isFinal }.map { it.text }, "$end")
        }
    }

    @Test
    fun `a line Soniox ends before any of its guesses became final keeps what was on screen, as the other engines keep theirs`() {
        val lines = CloudStreamLines()
        val live = lines.take(listOf(CloudStreamToken(text = "שלום", isFinal = false, speaker = "1", startMs = 0, endMs = 300)), 1.0)
        val ended = lines.take(listOf(CloudStreamToken(text = "<end>", isFinal = true)), 2.0)
        assertEquals(listOf("שלום"), ended.map { it.text })
        assertEquals(true, ended.firstOrNull()?.isFinal)
        assertEquals(live.firstOrNull()?.utteranceID, ended.firstOrNull()?.utteranceID)
    }

    @Test
    fun `a new voice heard first in the guesses is a new turn from its first word, before any of it is final`() {
        val lines = CloudStreamLines()
        lines.take(
            listOf(
                CloudStreamToken(text = "שלום", isFinal = true, speaker = "1", startMs = 0, endMs = 300),
                CloudStreamToken(text = "<end>", isFinal = true),
            ),
            1.0,
        )
        val other = lines.take(listOf(CloudStreamToken(text = "היי", isFinal = false, speaker = "2", startMs = 900, endMs = 1_200)), 2.0)
        assertEquals(listOf("היי"), other.map { it.text })
        assertEquals(true, other.firstOrNull()?.startsNewSpeakerTurn)
        val same = lines.take(
            listOf(
                CloudStreamToken(text = "<end>", isFinal = true),
                CloudStreamToken(text = "ומה", isFinal = false, speaker = "2", startMs = 1_800, endMs = 2_000),
            ),
            3.0,
        )
        assertEquals("ומה", same.last().text)
        assertEquals(false, same.last().startsNewSpeakerTurn)
    }

    @Test
    fun `Chinese, written without spaces, is still cut into lines of 28 seconds at most, with spaces, a piece without one stays on its word`() {
        val chinese = CloudStreamLines(spaced = false)
        val spacedLines = CloudStreamLines()
        val cut = ArrayList<String>()
        val kept = ArrayList<String>()
        for (i in 0 until 40) {
            val piece = CloudStreamToken(text = "你好", isFinal = true, speaker = "1", startMs = i * 1_500, endMs = i * 1_500 + 1_400)
            cut += chinese.take(listOf(piece), i.toDouble()).filter { it.isFinal }.map { it.text }
            kept += spacedLines.take(listOf(piece), i.toDouble()).filter { it.isFinal }.map { it.text }
        }
        assertEquals(listOf("你好".repeat(18), "你好".repeat(18)), cut)
        assertTrue(kept.isEmpty())
        assertEquals(false, CloudStreamLines.spaced("zh"))
        assertTrue(CloudStreamLines.spaced("he") && CloudStreamLines.spaced("en"))
    }

    @Test
    fun `a line whose first word has no known voice learns the voice from a later word, and splits when the voice changes`() {
        val lines = CloudStreamLines()
        val shown = lines.take(
            listOf(
                CloudStreamToken(text = " אחת", isFinal = true),
                CloudStreamToken(text = " שתיים", isFinal = true, speaker = "1"),
                CloudStreamToken(text = " שלוש", isFinal = true, speaker = "2"),
            ),
            1.0,
        ) + lines.finish(2.0)
        val finals = shown.filter { it.isFinal }
        assertEquals(listOf("אחת שתיים", "שלוש"), finals.map { it.text })
        assertEquals(listOf(false, true), finals.map { it.startsNewSpeakerTurn })
    }

    @Test
    fun `a lost connection cuts the line on screen with the cut-off mark, with nothing on screen there is nothing to cut`() {
        val lines = CloudStreamLines()
        assertNull(lines.cutOff(1.0))
        val live = lines.take(listOf(CloudStreamToken(text = "שלום", isFinal = false, speaker = "1", startMs = 0, endMs = 300)), 1.0)
        val cut = lines.cutOff(2.0)
        assertEquals(CaptionStabilizer.markingCutOff("שלום"), cut?.text)
        assertEquals(true, cut?.isFinal)
        assertEquals(live.firstOrNull()?.utteranceID, cut?.utteranceID)
        assertNull(lines.cutOff(3.0))
    }
}
