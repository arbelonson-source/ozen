package com.arbelonson.ozen.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun cloudReply(status: Int, json: String) = CloudHTTPResponse(status, json.toByteArray(Charsets.UTF_8))

private fun cloudBody(request: CloudHTTPRequest): JsonObject =
    Json.parseToJsonElement(String(assertNotNull(request.body), Charsets.UTF_8)).jsonObject

class CloudSpeechTest {
    @Test
    fun `each change of speaker is its own line, labels removed`() {
        val turns = CloudSpeech.turns("A: שלום לכולם\nB: מה נשמע?\nA: הכול טוב")
        assertEquals(listOf("שלום לכולם", "מה נשמע?", "הכול טוב"), turns)
    }

    @Test
    fun `lines from the same speaker, or with no label, stay one line`() {
        assertEquals(listOf("שלום מה נשמע"), CloudSpeech.turns("A: שלום\nA: מה נשמע"))
        assertEquals(listOf("שלום מה נשמע"), CloudSpeech.turns("שלום\nמה נשמע"))
        assertEquals(listOf("שלום מה נשמע", "טוב"), CloudSpeech.turns("A: שלום\nמה נשמע\nB: טוב"))
    }

    @Test
    fun `labels as models write them - numbers, 'Speaker', Hebrew letters`() {
        assertEquals(listOf("כן", "לא"), CloudSpeech.turns("Speaker 1: כן\nspeaker 2: לא"))
        assertEquals(listOf("כן", "לא"), CloudSpeech.turns("דובר א: כן\nדוברת ב: לא"))
        assertEquals(listOf("כן", "לא"), CloudSpeech.turns("א: כן\nב: לא"))
    }

    @Test
    fun `a sentence that opens with a short word and a colon keeps the word`() {
        assertEquals(listOf("אז: הלכנו הביתה"), CloudSpeech.turns("אז: הלכנו הביתה"))
        assertEquals(listOf("Mom: dinner is ready"), CloudSpeech.turns("Mom: dinner is ready"))
    }

    @Test
    fun `a line that opens with a time keeps the whole time`() {
        assertEquals(listOf("10:30 תבואי למרפאה"), CloudSpeech.turns("10:30 תבואי למרפאה"))
        assertEquals(listOf("9:15 בבוקר", "10:30 טוב"), CloudSpeech.turns("A: 9:15 בבוקר\nB: 10:30 טוב"))
    }

    @Test
    fun `notes about sounds are taken out, and a reply of only notes is no line`() {
        assertEquals(listOf("שלום לכולם"), CloudSpeech.turns("[inaudible] שלום (music) לכולם"))
        assertTrue(CloudSpeech.turns("[silence]").isEmpty())
        assertTrue(CloudSpeech.turns("<noise>\n").isEmpty())
        assertTrue(CloudSpeech.turns("").isEmpty())
    }

    @Test
    fun `lines speech models are known to invent are dropped`() {
        assertTrue(CloudSpeech.turns("A: תודה שצפיתם").isEmpty())
        assertEquals(listOf("בואו נאכל"), CloudSpeech.turns("A: תודה על הצפייה\nB: בואו נאכל"))
        assertEquals(listOf("שירה!", "בואו נאכל"), CloudSpeech.turns("A: שירה!\nB: בואו נאכל"))
    }

    @Test
    fun `a phrase said over and over is shortened`() {
        val turns = CloudSpeech.turns("כן כן כן כן כן כן כן")
        assertEquals(1, turns.size)
        assertTrue((turns.firstOrNull()?.split(" ")?.filter { it.isNotEmpty() }?.size ?: 0) <= 3)
    }

    @Test
    fun `a repeated phrase split across a same-speaker line break is shortened just like one on a single line`() {
        val turns = CloudSpeech.turns("A: כן כן\nA: כן כן כן")
        assertEquals(1, turns.size)
        assertTrue((turns.firstOrNull()?.split(" ")?.filter { it.isNotEmpty() }?.size ?: 0) <= 3)
    }

    @Test
    fun `the reply's text is the transcript`() {
        val text = CloudSpeech.transcript(cloudReply(200, """{"choices":[{"message":{"role":"assistant","content":"A: שלום"}}]}"""))
        assertEquals("A: שלום", text)
        val empty = CloudSpeech.transcript(cloudReply(200, """{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        assertEquals("", empty)
    }

    @Test
    fun `an error inside a successful reply, or an unreadable reply, is a failure`() {
        assertEquals(
            CloudSpeechError.ServerTrouble(200),
            assertFailsWith<CloudSpeechError> { CloudSpeech.transcript(cloudReply(200, """{"error":{"message":"provider failed"}}""")) },
        )
        assertEquals(
            CloudSpeechError.BadReply,
            assertFailsWith<CloudSpeechError> { CloudSpeech.transcript(cloudReply(200, "<html>")) },
        )
    }

    @Test
    fun `an answer marked as failed is a failure to retry, not a room where nobody spoke`() {
        assertEquals(
            CloudSpeechError.ServerTrouble(200),
            assertFailsWith<CloudSpeechError> {
                CloudSpeech.transcript(cloudReply(200, """{"choices":[{"finish_reason":"error","message":{"role":"assistant","content":""}}]}"""))
            },
        )
        assertEquals(
            CloudSpeechError.ServerTrouble(200),
            assertFailsWith<CloudSpeechError> {
                CloudSpeech.transcript(cloudReply(200, """{"choices":[{"error":{"message":"provider failed"},"message":{"role":"assistant","content":null}}]}"""))
            },
        )
        val finished = CloudSpeech.transcript(cloudReply(200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":""}}]}"""))
        assertEquals("", finished)
        val emptyErrorField = CloudSpeech.transcript(
            cloudReply(200, """{"choices":[{"error":null,"finish_reason":"stop","message":{"role":"assistant","content":"A: שלום"}}]}"""),
        )
        assertEquals("A: שלום", emptyErrorField)
    }

    @Test
    fun `what each refusal means`() {
        assertEquals(CloudSpeechError.KeyRejected, CloudSpeech.failure(cloudReply(401, "{}")))
        assertEquals(CloudSpeechError.OutOfCredit, CloudSpeech.failure(cloudReply(402, "{}")))
        assertEquals(CloudSpeechError.OutOfCredit, CloudSpeech.failure(cloudReply(403, """{"error":{"message":"Key limit exceeded"}}""")))
        assertEquals(CloudSpeechError.KeyRejected, CloudSpeech.failure(cloudReply(403, """{"error":{"message":"flagged"}}""")))
        assertEquals(CloudSpeechError.RateLimited, CloudSpeech.failure(cloudReply(429, "{}")))
        assertEquals(CloudSpeechError.ServerTrouble(503), CloudSpeech.failure(cloudReply(503, "{}")))
        assertEquals(CloudSpeechError.KeyRejected, assertFailsWith<CloudSpeechError> { CloudSpeech.transcript(cloudReply(401, "{}")) })
    }

    @Test
    fun `only problems the person has to fix stop retrying, and each says what to fix`() {
        val needsPerson = listOf(
            CloudSpeechError.KeyMissing, CloudSpeechError.KeyRejected, CloudSpeechError.OutOfCredit, CloudSpeechError.RateLimited,
            CloudSpeechError.Offline, CloudSpeechError.ServerTrouble(500), CloudSpeechError.BadReply,
        ).map { it.needsPerson }
        assertEquals(listOf(true, true, true, false, false, false, false), needsPerson)
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, CloudSpeechError.KeyMissing.unavailability.kind)
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, CloudSpeechError.KeyRejected.unavailability.kind)
        assertEquals(EngineUnavailability.Kind.CloudOutOfCredit, CloudSpeechError.OutOfCredit.unavailability.kind)
        assertEquals(EngineUnavailability.Kind.NoInternet, CloudSpeechError.Offline.unavailability.kind)
        assertEquals(EngineUnavailability.Kind.TemporarilyUnavailable, CloudSpeechError.RateLimited.unavailability.kind)
    }

    @Test
    fun `a key check with no credit left, a key with no limit, an unreadable answer`() {
        assertFalse(CloudSpeech.hasCreditLeft(cloudReply(200, """{"data":{"limit":3,"limit_remaining":0}}""")))
        assertFalse(CloudSpeech.hasCreditLeft(cloudReply(200, """{"data":{"limit":3,"limit_remaining":-0.01}}""")))
        assertTrue(CloudSpeech.hasCreditLeft(cloudReply(200, """{"data":{"limit":3,"limit_remaining":2.5}}""")))
        assertTrue(CloudSpeech.hasCreditLeft(cloudReply(200, """{"data":{"limit":3,"limit_remaining":0.5}}""")))
        assertTrue(CloudSpeech.hasCreditLeft(cloudReply(200, """{"data":{"limit":null,"limit_remaining":null}}""")))
        assertTrue(CloudSpeech.hasCreditLeft(cloudReply(200, "nope")))
    }

    @Test
    fun `the request carries the key, the model, the audio and the names list`() {
        val wav = WAVFile.pcm16(floatArrayOf(0.1f, -0.1f), sampleRate = 16_000)
        val request = CloudSpeech.completionRequest(CloudSpeech.FAST_MODEL, "sk-test", wav, "he", listOf("דנה", "ד\"ר כהן"))
        assertEquals(CloudSpeech.completionsURL, request.url)
        assertEquals("POST", request.method)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val json = cloudBody(request)
        assertEquals(CloudSpeech.FAST_MODEL, json["model"]?.jsonPrimitive?.content)
        // No sampling: the same audio is written the same way, without invented variety.
        assertEquals(0.0, json["temperature"]?.jsonPrimitive?.double)
        assertEquals("minimal", json["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val content = assertNotNull(json["messages"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonArray)
        val prompt = assertNotNull(content.first().jsonObject["text"]?.jsonPrimitive?.content)
        assertTrue(prompt.contains("Hebrew"))
        assertTrue(prompt.contains("דנה"))
        assertTrue(prompt.contains("ד\"ר כהן"))
        val audio = assertNotNull(content.last().jsonObject["input_audio"]?.jsonObject)
        assertEquals("wav", audio["format"]?.jsonPrimitive?.content)
        assertTrue(wav.contentEquals(Base64.getDecoder().decode(audio["data"]?.jsonPrimitive?.content ?: "")))
    }

    @Test
    fun `the request and the key check are shaped the way OpenRouter reads them, and go only to OpenRouter over https`() {
        val request = CloudSpeech.completionRequest(CloudSpeech.FAST_MODEL, "k", ByteArray(0), "he", emptyList())
        val message = assertNotNull(cloudBody(request)["messages"]?.jsonArray?.firstOrNull()?.jsonObject)
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        val content = assertNotNull(message["content"]?.jsonArray)
        assertEquals(listOf("text", "input_audio"), content.map { it.jsonObject["type"]?.jsonPrimitive?.content })
        val check = CloudSpeech.keyCheckRequest("k")
        assertEquals("GET", check.method)
        assertEquals("Bearer k", check.headers["Authorization"])
        for (url in listOf(CloudSpeech.completionsURL, CloudSpeech.keyURL)) {
            assertTrue(url.scheme == "https" && url.host == "openrouter.ai", "$url")
        }
        assertEquals("/api/v1/chat/completions", CloudSpeech.completionsURL.path)
        assertEquals("/api/v1/key", CloudSpeech.keyURL.path)
    }

    @Test
    fun `both Gemini models the app offers are told not to think first, which would make every line seconds later`() {
        for (model in CloudSpeech.models) {
            val request = CloudSpeech.completionRequest(model, "k", ByteArray(0), "he", emptyList())
            assertEquals("minimal", cloudBody(request)["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content, model)
        }
    }

    @Test
    fun `a cloud failure's detail in Diagnostics names no one service, since every service shares these reasons`() {
        val errors = listOf(
            CloudSpeechError.KeyMissing, CloudSpeechError.KeyRejected, CloudSpeechError.OutOfCredit, CloudSpeechError.RateLimited,
            CloudSpeechError.Offline, CloudSpeechError.ServerTrouble(503), CloudSpeechError.BadReply,
        )
        for (error in errors) {
            val detail = error.unavailability.detail
            assertTrue(detail.isNotEmpty())
        }
        assertTrue(CloudSpeechError.ServerTrouble(503).unavailability.detail.contains("503"))
    }

    @Test
    fun `the prompt names each caption language in full, so Ukrainian is never asked for as "uk", which reads as British English`() {
        val names = mapOf(
            "he" to "Hebrew", "en" to "English", "ar" to "Arabic", "ru" to "Russian", "am" to "Amharic", "fr" to "French",
            "es" to "Spanish", "uk" to "Ukrainian", "de" to "German", "pt" to "Portuguese", "zh" to "Chinese", "hi" to "Hindi",
        )
        for ((code, name) in names) {
            assertTrue(CloudSpeech.prompt(code, emptyList()).contains("as spoken, in $name. Do not translate"), code)
        }
    }

    @Test
    fun `a request to OpenRouter carries the key, says it is JSON, and names the app`() {
        val request = CloudSpeech.completionRequest(CloudSpeech.ACCURATE_MODEL, "k", ByteArray(0), "he", emptyList())
        assertEquals(mapOf("Authorization" to "Bearer k", "Content-Type" to "application/json", "X-Title" to "Ozen"), request.headers)
    }

    @Test
    fun `no names list, no names sentence - a model that isn't Google's gets no thinking setting`() {
        assertFalse(CloudSpeech.prompt("he", emptyList()).contains("Names"))
        val request = CloudSpeech.completionRequest("openai/gpt-audio-mini", "k", ByteArray(0), "he", emptyList())
        assertNull(cloudBody(request)["reasoning"])
        assertEquals(CloudSpeech.keyURL, CloudSpeech.keyCheckRequest("k").url)
    }
}
