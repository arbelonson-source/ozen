package com.arbelonson.ozen.core

import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun deepgramReply(status: Int, json: String) = CloudHTTPResponse(status, json.toByteArray(Charsets.UTF_8))

private fun deepgramQuery(request: CloudHTTPRequest): List<Pair<String, String>> {
    val raw = request.url.rawQuery ?: return emptyList()
    return raw.split("&").filter { it.isNotEmpty() }.map { pair ->
        val parts = pair.split("=", limit = 2)
        decodedPercent(parts[0]) to decodedPercent(parts.getOrElse(1) { "" })
    }
}

private fun decodedPercent(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")

class DeepgramSpeechTest {
    @Test
    fun `a sentence goes to Nova-3 as the WAV itself, in the caption language, with speakers, punctuation and the names list, and not for improving Deepgram's models`() {
        val wav = WAVFile.pcm16(floatArrayOf(0.1f, -0.1f), 16_000)
        val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "dg-test", wav, "he", listOf("דנה", " דנה ", "ד\"ר כהן"))
        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("api.deepgram.com", request.url.host)
        assertEquals("/v1/listen", request.url.path)
        assertEquals("Token dg-test", request.headers["Authorization"])
        assertEquals("audio/wav", request.headers["Content-Type"])
        assertContentEquals(wav, request.body)
        val items = deepgramQuery(request)
        val settings = LinkedHashMap<String, String>()
        for ((name, value) in items) {
            if (name != "keyterm" && name !in settings) settings[name] = value
        }
        assertEquals(
            mapOf(
                "model" to "nova-3", "language" to "he", "punctuate" to "true", "smart_format" to "true",
                "utterances" to "true", "diarize_model" to "latest", "mip_opt_out" to "true",
            ),
            settings,
        )
        assertEquals(listOf("דנה", "ד\"ר כהן"), items.filter { it.first == "keyterm" }.map { it.second })
    }

    @Test
    fun `a name with a plus or an ampersand reaches Deepgram as typed, which reads a plus in the address as a space`() {
        val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "k", ByteArray(0), "en", listOf("C++", "Dana & Avi", "x=1"))
        val query = assertNotNull(request.url.rawQuery)
        val heard = query.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2).map { URLDecoder.decode(it, "UTF-8") }
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        assertEquals(listOf("C++", "Dana & Avi", "x=1"), heard.filter { it.first == "keyterm" }.map { it.second })
        assertEquals("nova-3", heard.first { it.first == "model" }.second)
    }

    @Test
    fun `a long list of short names sends its first hundred`() {
        val names = (1..150).map { "n$it" }
        val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "k", ByteArray(0), "en", names)
        assertEquals(names.take(100), deepgramQuery(request).filter { it.first == "keyterm" }.map { it.second })
    }

    @Test
    fun `names longer together than Deepgram's 500-token limit, which fails the whole request, keep whole names from the top that fit, counting each name's bytes and one more`() {
        val long = (0 until 12).map { ('a' + it).toString().repeat(40) }
        for ((last, sent) in listOf(7 to 13, 8 to 12)) {
            val names = long + listOf("z".repeat(last), "after")
            val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "k", ByteArray(0), "en", names)
            val terms = deepgramQuery(request).filter { it.first == "keyterm" }.map { it.second }
            assertEquals(names.take(sent), terms, "$last")
        }
        val hebrew = (1..10).map { "$it" + "א".repeat(38) }
        val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "k", ByteArray(0), "he", hebrew)
        assertEquals(6, deepgramQuery(request).count { it.first == "keyterm" })
    }

    @Test
    fun `Chinese goes to Nova-3 like the rest, which Deepgram lists with Mandarin, and takes the names list`() {
        val request = DeepgramSpeech.request(DeepgramSpeech.MODEL, "k", ByteArray(0), "zh", listOf("王芳"))
        val items = deepgramQuery(request)
        val settings = LinkedHashMap<String, String>()
        for ((name, value) in items) {
            if (name !in settings) settings[name] = value
        }
        assertEquals("nova-3", settings["model"])
        assertEquals("zh", settings["language"])
        assertEquals("latest", settings["diarize_model"])
        assertNull(settings["diarize"])
        assertEquals(listOf("王芳"), items.filter { it.first == "keyterm" }.map { it.second })
    }

    @Test
    fun `each speaker's stretch is its own line, and the same speaker going on stays one line`() {
        val body = File(System.getProperty("ozen.fixtures"), "cloud/deepgram-two-speakers.json").readBytes()
        val transcript = DeepgramSpeech.transcript(CloudHTTPResponse(200, body))
        assertEquals(listOf("מה שלומך?", "טוב, תודה. ואתה?"), CloudSpeech.turns(transcript))
    }

    @Test
    fun `no speech is no line, a reply without speaker stretches still gives its words, an unreadable one is a failure`() {
        val silent = """{"results":{"channels":[{"alternatives":[{"transcript":"","words":[]}]}],"utterances":[]}}"""
        assertEquals("", DeepgramSpeech.transcript(deepgramReply(200, silent)))
        val plain = """{"results":{"channels":[{"alternatives":[{"transcript":"שלום לכולם","words":[]}]}]}}"""
        assertEquals("שלום לכולם", DeepgramSpeech.transcript(deepgramReply(200, plain)))
        assertFailsWith<CloudSpeechError.BadReply> {
            DeepgramSpeech.transcript(deepgramReply(200, "<html>"))
        }
    }

    @Test
    fun `what each refusal means - a bad key, no credit left, too many requests, Deepgram's own trouble`() {
        assertEquals(CloudSpeechError.KeyRejected, DeepgramSpeech.failure(deepgramReply(401, """{"err_code":"INVALID_AUTH"}""")))
        assertEquals(CloudSpeechError.KeyRejected, DeepgramSpeech.failure(deepgramReply(403, """{"err_code":"INSUFFICIENT_PERMISSIONS"}""")))
        assertEquals(CloudSpeechError.OutOfCredit, DeepgramSpeech.failure(deepgramReply(402, """{"err_code":"ASR_PAYMENT_REQUIRED"}""")))
        assertEquals(CloudSpeechError.RateLimited, DeepgramSpeech.failure(deepgramReply(429, """{"err_code":"TOO_MANY_REQUESTS"}""")))
        assertEquals(CloudSpeechError.ServerTrouble(503), DeepgramSpeech.failure(deepgramReply(503, "{}")))
        assertFailsWith<CloudSpeechError.OutOfCredit> {
            DeepgramSpeech.transcript(deepgramReply(402, "{}"))
        }
    }

    @Test
    fun `the key is checked by listing its projects, which costs nothing`() {
        val check = DeepgramSpeech.keyCheckRequest("k")
        assertEquals("GET", check.method)
        assertEquals("https://api.deepgram.com/v1/projects", check.url.toString())
        assertEquals("Token k", check.headers["Authorization"])
    }

    @Test
    fun `Deepgram covers eleven of Ozen's twelve caption languages, Amharic it has none of`() {
        for (code in listOf("he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh")) {
            assertTrue(code in DeepgramSpeech.languages, code)
        }
        assertTrue("am" !in DeepgramSpeech.languages)
    }
}
