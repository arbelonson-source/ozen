package com.arbelonson.ozen.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun elevenLabsReply(status: Int, json: String) = CloudHTTPResponse(status, json.toByteArray(Charsets.UTF_8))

private class ElevenLabsFormPart(val name: String, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

private fun elevenLabsIndexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    var start = from
    while (start <= haystack.size - needle.size) {
        if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        start += 1
    }
    return -1
}

private fun elevenLabsFormParts(request: CloudHTTPRequest): List<ElevenLabsFormPart> {
    val prefix = "multipart/form-data; boundary="
    val type = assertNotNull(request.headers["Content-Type"])
    assertTrue(type.startsWith(prefix))
    val boundary = type.drop(prefix.length)
    val body = assertNotNull(request.body)
    val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
    val lineBreak = "\r\n".toByteArray(Charsets.UTF_8)
    val found = mutableListOf<ElevenLabsFormPart>()
    val first = elevenLabsIndexOf(body, delimiter)
    if (first < 0) return found
    var position = first + delimiter.size
    while (true) {
        val next = elevenLabsIndexOf(body, delimiter, position)
        if (next < 0) break
        val part = body.copyOfRange(position, next)
        position = next + delimiter.size
        val split = elevenLabsIndexOf(part, "\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (split < 0) continue
        val headers = String(part.copyOfRange(0, split), Charsets.UTF_8)
        var content = part.copyOfRange(split + 4, part.size)
        assertTrue(
            content.size >= 2 && content.copyOfRange(content.size - 2, content.size).contentEquals(lineBreak),
            "a part must end with a line break before the next boundary",
        )
        content = content.copyOfRange(0, maxOf(content.size - 2, 0))
        val start = headers.indexOf("name=\"")
        if (start < 0) continue
        found.add(ElevenLabsFormPart(headers.substring(start + 6).takeWhile { it != '"' }, content))
    }
    val rest = body.copyOfRange(position, body.size)
    assertTrue(rest.size >= 4 && rest.copyOfRange(0, 4).contentEquals("--\r\n".toByteArray(Charsets.UTF_8)))
    return found
}

class ElevenLabsSpeechTest {
    @Test
    fun `a sentence goes to Scribe v2 as the WAV itself, in the caption language, with speakers on, sound tags off and each name as a key term`() {
        val wav = WAVFile.pcm16(floatArrayOf(0.1f, -0.1f), 16_000)
        val request = ElevenLabsSpeech.request(ElevenLabsSpeech.MODEL, "xi-test", wav, "he", listOf("דנה", " דנה ", "ד\"ר כהן"))
        assertEquals("POST", request.method)
        assertEquals("https://api.elevenlabs.io/v1/speech-to-text", request.url.toString())
        assertEquals("xi-test", request.headers["xi-api-key"])
        assertNull(request.headers["Authorization"])
        val parts = elevenLabsFormParts(request)
        assertEquals(listOf("model_id", "language_code", "diarize", "tag_audio_events", "keyterms", "keyterms", "file"), parts.map { it.name })
        assertEquals(listOf("scribe_v2", "he", "true", "false", "דנה", "ד\"ר כהן"), parts.map { it.text }.take(6))
        assertContentEquals(wav, parts.last().body)
    }

    @Test
    fun `a long names list sends the first hundred as key terms`() {
        val names = (1..150).map { "שם$it" }
        val parts = elevenLabsFormParts(ElevenLabsSpeech.request("scribe_v2", "k", ByteArray(0), "am", names))
        assertEquals(names.take(100), parts.filter { it.name == "keyterms" }.map { it.text })
        assertEquals("am", parts.first { it.name == "language_code" }.text)
    }

    @Test
    fun `a name ElevenLabs would refuse, more than five words or with a bracket, brace, angle bracket or backslash, is left out, and the hundred are counted after it`() {
        val refused = listOf("Avi", "Ben", "Chen", "Dana", "Eli", "Gal", "Hila").zip(listOf("<", ">", "{", "}", "[", "]", "\\")).map { "${it.first}${it.second}x" }
        val names = listOf("אחת שתיים שלוש ארבע חמש", "one two three four five six") + refused + listOf("דנה") + (1..100).map { "n$it" }
        val parts = elevenLabsFormParts(ElevenLabsSpeech.request("scribe_v2", "k", ByteArray(0), "he", names))
        val terms = parts.filter { it.name == "keyterms" }.map { it.text }
        assertEquals(listOf("אחת שתיים שלוש ארבע חמש", "דנה"), terms.take(2))
        assertEquals(100, terms.size)
        assertEquals("n98", terms.last())
    }

    @Test
    fun `each speaker's stretch is its own line, a laugh tag is left out, and the same speaker going on stays one line`() {
        val body = File(System.getProperty("ozen.fixtures"), "cloud/elevenlabs-two-speakers.json").readBytes()
        val transcript = ElevenLabsSpeech.transcript(CloudHTTPResponse(200, body))
        assertEquals(listOf("מה שלומך?", "טוב, תודה. ואתה?"), CloudSpeech.turns(transcript))
        assertFalse(transcript.contains("צחוק"))
    }

    @Test
    fun `a reply without words still gives its text, an unreadable one is a failure`() {
        assertEquals("שלום לכולם", ElevenLabsSpeech.transcript(elevenLabsReply(200, """{"text":"שלום לכולם","words":[]}""")))
        assertEquals("", ElevenLabsSpeech.transcript(elevenLabsReply(200, """{"text":""}""")))
        assertFailsWith<CloudSpeechError.BadReply> {
            ElevenLabsSpeech.transcript(elevenLabsReply(200, "<html>"))
        }
    }

    @Test
    fun `no quota is no credit whatever the status it comes with, a bad key, a busy service and anything else read as the others do`() {
        assertEquals(CloudSpeechError.OutOfCredit, ElevenLabsSpeech.failure(elevenLabsReply(400, """{"detail":{"status":"quota_exceeded","message":"You have insufficient quota"}}""")))
        assertEquals(CloudSpeechError.OutOfCredit, ElevenLabsSpeech.failure(elevenLabsReply(401, """{"detail":{"status":"quota_exceeded"}}""")))
        assertEquals(CloudSpeechError.KeyRejected, ElevenLabsSpeech.failure(elevenLabsReply(401, """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""")))
        assertEquals(CloudSpeechError.OutOfCredit, ElevenLabsSpeech.failure(elevenLabsReply(402, "")))
        assertEquals(CloudSpeechError.RateLimited, ElevenLabsSpeech.failure(elevenLabsReply(429, "")))
        assertEquals(CloudSpeechError.ServerTrouble(422), ElevenLabsSpeech.failure(elevenLabsReply(422, """{"detail":[{"msg":"field required"}]}""")))
    }

    @Test
    fun `the key is checked against the account, only a key ElevenLabs calls invalid fails, so a key limited to speech to text still passes`() {
        val request = ElevenLabsSpeech.keyCheckRequest("xi-test")
        assertEquals("GET", request.method)
        assertEquals("https://api.elevenlabs.io/v1/user", request.url.toString())
        assertEquals("xi-test", request.headers["xi-api-key"])
        assertTrue(ElevenLabsSpeech.keyCheckPasses(elevenLabsReply(200, "{}")))
        assertFalse(ElevenLabsSpeech.keyCheckPasses(elevenLabsReply(401, """{"detail":{"status":"invalid_api_key"}}""")))
        assertTrue(ElevenLabsSpeech.keyCheckPasses(elevenLabsReply(401, """{"detail":{"status":"missing_permissions","message":"The API key you used is missing the permission user_read"}}""")))
        assertFalse(ElevenLabsSpeech.keyCheckPasses(elevenLabsReply(503, "")))
    }
}
