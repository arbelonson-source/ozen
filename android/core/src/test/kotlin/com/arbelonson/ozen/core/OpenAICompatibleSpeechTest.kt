package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun openAIReply(status: Int, json: String) = CloudHTTPResponse(status, json.toByteArray(Charsets.UTF_8))

private class OpenAIFormPart(val headers: String, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

private fun openAIIndexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    var start = from
    while (start <= haystack.size - needle.size) {
        if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        start += 1
    }
    return -1
}

private fun openAIParts(request: CloudHTTPRequest): Map<String, OpenAIFormPart> {
    val prefix = "multipart/form-data; boundary="
    val type = assertNotNull(request.headers["Content-Type"])
    assertTrue(type.startsWith(prefix))
    val boundary = type.drop(prefix.length)
    val body = assertNotNull(request.body)
    val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
    val lineBreak = "\r\n".toByteArray(Charsets.UTF_8)
    val found = LinkedHashMap<String, OpenAIFormPart>()
    val first = openAIIndexOf(body, delimiter)
    if (first < 0) return found
    var position = first + delimiter.size
    while (true) {
        val next = openAIIndexOf(body, delimiter, position)
        if (next < 0) break
        val part = body.copyOfRange(position, next)
        position = next + delimiter.size
        val split = openAIIndexOf(part, "\r\n\r\n".toByteArray(Charsets.UTF_8))
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
        found[headers.substring(start + 6).takeWhile { it != '"' }] = OpenAIFormPart(headers, content)
    }
    val rest = body.copyOfRange(position, body.size)
    assertTrue(rest.size >= 4 && rest.copyOfRange(0, 4).contentEquals("--\r\n".toByteArray(Charsets.UTF_8)))
    return found
}

class OpenAICompatibleSpeechTest {
    @Test
    fun `a sentence goes to OpenAI's gpt-4o-transcribe as the WAV itself, in the caption language, with the names list as the prompt`() {
        val wav = WAVFile.pcm16(floatArrayOf(0.1f, -0.1f, 0.2f), 16_000)
        val request = OpenAICompatibleSpeech.request(
            OpenAICompatibleSpeech.openAI, OpenAICompatibleSpeech.openAI.model, "sk-test", wav, "he", listOf("דנה", " דנה ", "ד\"ר כהן"),
        )
        assertEquals("POST", request.method)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", request.url.toString())
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val form = openAIParts(request)
        assertEquals(setOf("file", "model", "language", "response_format", "prompt"), form.keys)
        assertEquals("gpt-4o-transcribe", form["model"]?.text)
        assertEquals("he", form["language"]?.text)
        assertEquals("json", form["response_format"]?.text)
        assertEquals("דנה, ד\"ר כהן.", form["prompt"]?.text)
        assertContentEquals(wav, form["file"]?.body)
        assertEquals(true, form["file"]?.headers?.contains("filename=\"speech.wav\""))
        assertEquals(true, form["file"]?.headers?.contains("Content-Type: audio/wav"))
    }

    @Test
    fun `Groq takes the same form at its own address, for Whisper large-v3, and no names list means no prompt`() {
        val request = OpenAICompatibleSpeech.request(
            OpenAICompatibleSpeech.groq, OpenAICompatibleSpeech.groq.model, "gsk-test", byteArrayOf(1, 2), "am", emptyList(),
        )
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url.toString())
        assertEquals("Bearer gsk-test", request.headers["Authorization"])
        val form = openAIParts(request)
        assertEquals("whisper-large-v3", form["model"]?.text)
        assertEquals("am", form["language"]?.text)
        assertNull(form["prompt"])
    }

    @Test
    fun `a long names list keeps only the names from the top that fit the prompt, never half a name`() {
        val names = (1..100).map { "שם$it" }
        val form = openAIParts(
            OpenAICompatibleSpeech.request(OpenAICompatibleSpeech.openAI, "gpt-4o-transcribe", "k", ByteArray(0), "he", names),
        )
        val prompt = assertNotNull(form["prompt"]).text
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= OpenAICompatibleSpeech.PROMPT_BYTES)
        assertTrue(prompt.endsWith("."))
        val kept = prompt.dropLast(1).split(", ")
        assertTrue(kept.size > 10)
        assertEquals(names.take(kept.size), kept)
    }

    @Test
    fun `the prompt may fill all 224 bytes, counting its leading space, and not one byte more`() {
        val long = listOf("a", "b", "c", "d", "e").map { it.repeat(40) }
        for ((last, kept) in listOf(12 to 6, 13 to 5)) {
            val form = openAIParts(
                OpenAICompatibleSpeech.request(
                    OpenAICompatibleSpeech.openAI, "gpt-4o-transcribe", "k", ByteArray(0), "en", long + listOf("f".repeat(last)),
                ),
            )
            val prompt = assertNotNull(form["prompt"]).text
            assertEquals(kept, prompt.dropLast(1).split(", ").size, "$last")
        }
    }

    @Test
    fun `the reply's text is the transcript, an empty one is no speech, an unreadable one a failure`() {
        assertEquals(
            "שלום לכולם",
            OpenAICompatibleSpeech.transcript(openAIReply(200, """{"text":"שלום לכולם","usage":{"type":"duration","seconds":3}}""")),
        )
        assertEquals("", OpenAICompatibleSpeech.transcript(openAIReply(200, """{"text":""}""")))
        assertFailsWith<CloudSpeechError.BadReply> {
            OpenAICompatibleSpeech.transcript(openAIReply(200, "<html>"))
        }
        assertFailsWith<CloudSpeechError.KeyRejected> {
            OpenAICompatibleSpeech.transcript(openAIReply(401, """{"error":{"code":"invalid_api_key"}}"""))
        }
    }

    @Test
    fun `an account out of money is told apart from a busy service, though both answer 429`() {
        assertEquals(CloudSpeechError.KeyRejected, OpenAICompatibleSpeech.failure(openAIReply(401, "")))
        assertEquals(CloudSpeechError.KeyRejected, OpenAICompatibleSpeech.failure(openAIReply(403, "")))
        assertEquals(CloudSpeechError.OutOfCredit, OpenAICompatibleSpeech.failure(openAIReply(402, "")))
        assertEquals(
            CloudSpeechError.OutOfCredit,
            OpenAICompatibleSpeech.failure(openAIReply(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""")),
        )
        assertEquals(
            CloudSpeechError.RateLimited,
            OpenAICompatibleSpeech.failure(openAIReply(429, """{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit_exceeded"}}""")),
        )
        assertEquals(CloudSpeechError.RateLimited, OpenAICompatibleSpeech.failure(openAIReply(429, "")))
        assertEquals(CloudSpeechError.ServerTrouble(500), OpenAICompatibleSpeech.failure(openAIReply(500, "")))
    }

    @Test
    fun `a key is checked by listing the service's models`() {
        val openAI = OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.openAI, "sk-test")
        assertEquals("GET", openAI.method)
        assertEquals("https://api.openai.com/v1/models", openAI.url.toString())
        assertEquals("Bearer sk-test", openAI.headers["Authorization"])
        assertEquals("https://api.groq.com/openai/v1/models", OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.groq, "g").url.toString())
    }
}
