package com.arbelonson.ozen.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val speechmaticsFrames: List<String> =
    File(System.getProperty("ozen.fixtures"), "cloud/speechmatics-two-speakers.jsonl").readText(Charsets.UTF_8)
        .split("\n").filter { it.isNotEmpty() }

private const val SPEECHMATICS_STARTED = """{"message":"RecognitionStarted","id":"x"}"""
private const val SPEECHMATICS_ENDED = """{"message":"EndOfTranscript"}"""

private fun speechmaticsJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun speechmaticsStrings(element: kotlinx.serialization.json.JsonElement?): List<String>? =
    element?.jsonArray?.map { it.jsonPrimitive.content }

class SpeechmaticsSpeechTest {
    @Test
    fun `the settings ask for the enhanced model in the caption language, guesses as it goes, speakers, a line at a pause, and the names as extra words`() {
        val start = speechmaticsJson(SpeechmaticsSpeech.config("he", listOf("דנה", " דנה ", "ד\"ר כהן")))
        assertEquals("StartRecognition", start["message"]?.jsonPrimitive?.content)
        val format = assertNotNull(start["audio_format"]).jsonObject
        assertEquals("raw", format["type"]?.jsonPrimitive?.content)
        assertEquals("pcm_s16le", format["encoding"]?.jsonPrimitive?.content)
        assertEquals(16_000, format["sample_rate"]?.jsonPrimitive?.int)
        val settings = assertNotNull(start["transcription_config"]).jsonObject
        assertEquals("he", settings["language"]?.jsonPrimitive?.content)
        assertEquals("enhanced", settings["operating_point"]?.jsonPrimitive?.content)
        assertEquals(true, settings["enable_partials"]?.jsonPrimitive?.boolean)
        assertEquals(2.0, settings["max_delay"]?.jsonPrimitive?.double)
        assertEquals("speaker", settings["diarization"]?.jsonPrimitive?.content)
        assertEquals(0.7, settings["conversation_config"]?.jsonObject?.get("end_of_utterance_silence_trigger")?.jsonPrimitive?.double)
        assertEquals(listOf("דנה", "ד\"ר כהן"), speechmaticsStrings(settings["additional_vocab"]))
    }

    @Test
    fun `Chinese is asked for as Mandarin, no names sends no extra words, and a long list sends its first hundred`() {
        val chinese = assertNotNull(speechmaticsJson(SpeechmaticsSpeech.config("zh", emptyList()))["transcription_config"]).jsonObject
        assertEquals("cmn", chinese["language"]?.jsonPrimitive?.content)
        assertNull(chinese["additional_vocab"])
        val names = (1..150).map { "שם$it" }
        val long = assertNotNull(speechmaticsJson(SpeechmaticsSpeech.config("ar", names))["transcription_config"]).jsonObject
        assertEquals("ar", long["language"]?.jsonPrimitive?.content)
        assertEquals(names.take(100), speechmaticsStrings(long["additional_vocab"]))
    }

    @Test
    fun `the end of the audio names how many pieces of it were sent`() {
        val end = speechmaticsJson(SpeechmaticsSpeech.endMessage(7))
        assertEquals("EndOfStream", end["message"]?.jsonPrimitive?.content)
        assertEquals(7, end["last_seq_no"]?.jsonPrimitive?.int)
    }

    @Test
    fun `words get a space before them and punctuation joins the word before it unless it says otherwise, Chinese has no spaces, and an unknown voice is no voice`() {
        val quoted = """{"message":"AddTranscript","results":[{"type":"word","start_time":1.0,"end_time":1.25,"alternatives":[{"content":"אמר","speaker":"S1"}]},{"type":"punctuation","attaches_to":"next","alternatives":[{"content":"\""}]},{"type":"word","alternatives":[{"content":"שלום","speaker":"UU"}]},{"type":"punctuation","attaches_to":"previous","alternatives":[{"content":"\""}]}]}"""
        val said = assertNotNull(SpeechmaticsSpeech.reply(quoted, "he") as? CloudStreamReply.Tokens, "no words")
        assertFalse(said.finished)
        val tokens = said.tokens
        assertEquals(" אמר \"שלום\"", tokens.joinToString("") { it.text })
        assertTrue(tokens.all { it.isFinal })
        assertEquals(listOf("S1", null, null, null), tokens.map { it.speaker })
        assertEquals(1_000, tokens.first().startMs)
        assertEquals(1_250, tokens.first().endMs)
        val chinese = """{"message":"AddPartialTranscript","results":[{"type":"word","alternatives":[{"content":"你好"}]},{"type":"word","alternatives":[{"content":"世界"}]},{"type":"punctuation","alternatives":[{"content":"。"}]}]}"""
        val guesses = assertNotNull(SpeechmaticsSpeech.reply(chinese, "zh") as? CloudStreamReply.Tokens, "no words").tokens
        assertEquals("你好世界。", guesses.joinToString("") { it.text })
        assertTrue(guesses.all { !it.isFinal })
        val bare = """{"message":"AddPartialTranscript","results":[{"type":"word","alternatives":[{"content":"כן"}]},{"type":"punctuation","alternatives":[{"content":"."}]}]}"""
        val plain = assertNotNull(SpeechmaticsSpeech.reply(bare, "he") as? CloudStreamReply.Tokens, "no words").tokens
        assertEquals(listOf(" כן", "."), plain.map { it.text })
    }

    @Test
    fun `the start, the end of what was said, and the end of the transcript are told apart, acknowledgements and notes are skipped`() {
        assertEquals(CloudStreamReply.Started, SpeechmaticsSpeech.reply(SPEECHMATICS_STARTED, "he"))
        assertEquals(
            CloudStreamReply.Tokens(listOf(CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true)), finished = false),
            SpeechmaticsSpeech.reply("""{"message":"EndOfUtterance","metadata":{"start_time":1.7,"end_time":1.7}}""", "he"),
        )
        assertEquals(CloudStreamReply.Tokens(emptyList(), finished = true), SpeechmaticsSpeech.reply(SPEECHMATICS_ENDED, "he"))
        assertNull(SpeechmaticsSpeech.reply("""{"message":"AudioAdded","seq_no":3}""", "he"))
        assertNull(SpeechmaticsSpeech.reply("""{"message":"Warning","type":"duration_limit_exceeded","reason":"x"}""", "he"))
        assertNull(SpeechmaticsSpeech.reply("""{"message":"Info","type":"recognition_quality"}""", "he"))
        assertNull(SpeechmaticsSpeech.reply("<html>", "he"))
    }

    @Test
    fun `a refused key is a key to fix, too many connections a busy service, the account's usage quota used up no credit, a session that ran out of time no connection, anything else the service's trouble`() {
        fun error(type: String, code: Int? = null): CloudStreamReply? {
            val number = code?.let { ""","code":$it""" } ?: ""
            return SpeechmaticsSpeech.reply("""{"message":"Error","type":"$type","reason":"x"$number}""", "he")
        }
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.KeyRejected), error("not_authorised", 4001))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.KeyRejected), error("not_allowed"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.RateLimited), error("quota_exceeded", 4005))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.OutOfCredit), error("timelimit_exceeded", 4006))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.Offline), error("idle_timeout"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.Offline), error("session_timeout"))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.ServerTrouble(4003)), error("invalid_language", 4003))
        assertEquals(CloudStreamReply.Failure(CloudSpeechError.ServerTrouble(500)), error("job_error"))
    }

    @Test
    fun `a session Speechmatics closes says why by its documented codes, a normal close is no connection`() {
        assertEquals(CloudSpeechError.KeyRejected, SpeechmaticsSpeech.failure(4001, "not_authorised"))
        assertEquals(CloudSpeechError.RateLimited, SpeechmaticsSpeech.failure(4005, "quota_exceeded"))
        assertEquals(CloudSpeechError.ServerTrouble(4013), SpeechmaticsSpeech.failure(4013, "job_error"))
        assertEquals(CloudSpeechError.ServerTrouble(1011), SpeechmaticsSpeech.failure(1011, "internal_error"))
        assertEquals(CloudSpeechError.OutOfCredit, SpeechmaticsSpeech.failure(4006, "timelimit_exceeded"))
        assertEquals(CloudSpeechError.KeyRejected, SpeechmaticsSpeech.failure(4003, "not_allowed"))
        assertEquals(CloudSpeechError.ServerTrouble(4004), SpeechmaticsSpeech.failure(4004, "invalid_model"))
        assertNull(SpeechmaticsSpeech.failure(1000, ""))
        assertNull(SonioxSpeech.failure(1008, "x"))
    }

    @Test
    fun `the recorded conversation becomes a line per turn, each speaker change a new turn`() {
        val lines = CloudStreamLines()
        val shown = ArrayList<TranscriptToken>()
        var finished = false
        for ((index, frame) in speechmaticsFrames.withIndex()) {
            val reply = SpeechmaticsSpeech.reply(frame, "he") as? CloudStreamReply.Tokens ?: continue
            shown += lines.take(reply.tokens, index.toDouble())
            if (reply.finished) {
                shown += lines.finish(index.toDouble())
                finished = true
            }
        }
        assertTrue(finished)
        assertEquals(
            listOf("מה", "מה שלומך", "מה שלומך?", "מה שלומך?", "טוב", "טוב, תודה.", "טוב, תודה. ואתה", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"),
            shown.map { it.text },
        )
        assertEquals(listOf(false, false, false, true, false, false, false, false, true, false, true), shown.map { it.isFinal })
        assertEquals(listOf(false, true, true), shown.filter { it.isFinal }.map { it.startsNewSpeakerTurn })
    }

    @Test
    fun `the key is checked by the request Speechmatics' own guide gives, on its European host as captions are, and a failing check reads as the HTTP answer says`() {
        val request = SpeechmaticsSpeech.keyCheckRequest("sm-test")
        assertEquals("GET", request.method)
        assertEquals("https://eu1.asr.api.speechmatics.com/v2/jobs/", request.url.toString())
        assertEquals("Bearer sm-test", request.headers["Authorization"])
        assertEquals(mapOf("Authorization" to "Bearer sm-test"), SpeechmaticsSpeech.headers("sm-test"))
        assertEquals("wss://eu.rt.speechmatics.com/v2", SpeechmaticsSpeech.streamURL.toString())
        assertEquals(CloudSpeechError.KeyRejected, SpeechmaticsSpeech.failure(CloudHTTPResponse(401, ByteArray(0))))
        assertEquals(CloudSpeechError.KeyRejected, SpeechmaticsSpeech.failure(CloudHTTPResponse(403, ByteArray(0))))
        assertEquals(CloudSpeechError.OutOfCredit, SpeechmaticsSpeech.failure(CloudHTTPResponse(402, ByteArray(0))))
        assertEquals(CloudSpeechError.RateLimited, SpeechmaticsSpeech.failure(CloudHTTPResponse(429, ByteArray(0))))
        assertEquals(CloudSpeechError.ServerTrouble(503), SpeechmaticsSpeech.failure(CloudHTTPResponse(503, ByteArray(0))))
    }
}
