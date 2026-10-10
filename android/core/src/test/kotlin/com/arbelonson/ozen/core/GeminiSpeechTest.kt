package com.arbelonson.ozen.core

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun geminiReply(status: Int, json: String) = CloudHTTPResponse(status, json.toByteArray(Charsets.UTF_8))

private fun geminiSent(request: CloudHTTPRequest): JsonObject =
    Json.parseToJsonElement(String(assertNotNull(request.body), Charsets.UTF_8)).jsonObject

private fun geminiConfig(body: JsonObject): JsonObject? =
    (body["generation_config"] as? JsonObject)?.get("transcription_config") as? JsonObject

class GeminiSpeechTest {
    @Test
    fun `a sentence goes to gemini-3 dot 5-transcribe as the WAV itself, with the caption language as a region tag and the names as its vocabulary`() {
        val wav = WAVFile.pcm16(floatArrayOf(0.1f, -0.1f, 0.2f), 16_000)
        val request = GeminiSpeech.request(GeminiSpeech.MODEL, "AIza-test", wav, "he", listOf("דנה", " דנה ", "ד\"ר כהן"))
        assertEquals("POST", request.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", request.url.toString())
        assertEquals("AIza-test", request.headers["x-goog-api-key"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertFalse(request.url.toString().contains("AIza"))
        val body = geminiSent(request)
        assertEquals("gemini-3.5-transcribe", body["model"]?.jsonPrimitive?.content)
        val input = assertNotNull(body["input"]).jsonArray
        assertEquals(1, input.size)
        val first = input.first().jsonObject
        assertEquals("audio", first["type"]?.jsonPrimitive?.content)
        assertEquals("audio/wav", first["mime_type"]?.jsonPrimitive?.content)
        assertContentEquals(wav, Base64.getDecoder().decode(first["data"]?.jsonPrimitive?.content))
        val config = assertNotNull(geminiConfig(body))
        assertEquals(listOf("he-IL"), config["language_codes"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("דנה", "ד\"ר כהן"), config["custom_vocabulary"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `every caption language has a region tag Gemini knows, Portuguese the European one the app speaks`() {
        assertEquals(
            mapOf(
                "he" to "he-IL", "en" to "en-US", "ar" to "ar-EG", "ru" to "ru-RU", "am" to "am-ET", "fr" to "fr-FR",
                "es" to "es-ES", "uk" to "uk-UA", "de" to "de-DE", "pt" to "pt-PT", "zh" to "cmn-Hans-CN", "hi" to "hi-IN",
            ),
            GeminiSpeech.languageTags,
        )
    }

    @Test
    fun `no names means no vocabulary, a long list sends its first hundred, and a language without a tag is left for Gemini to tell`() {
        val plain = geminiSent(GeminiSpeech.request("gemini-3.5-transcribe", "k", ByteArray(0), "xx", emptyList()))
        val config = geminiConfig(plain)
        assertNull(config?.get("custom_vocabulary"))
        assertNull(config?.get("language_codes"))
        val names = (1..150).map { "שם$it" }
        val long = geminiSent(GeminiSpeech.request("gemini-3.5-transcribe", "k", ByteArray(0), "he", names))
        val terms = geminiConfig(long)?.get("custom_vocabulary") as? JsonArray
        assertEquals(names.take(100), terms?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the transcript is the text in the model's output, other steps and parts are left out, an unreadable reply is a failure`() {
        val body = File(System.getProperty("ozen.fixtures"), "cloud/gemini-transcribe.json").readBytes()
        assertEquals("מה שלומך? טוב, תודה.", GeminiSpeech.transcript(CloudHTTPResponse(200, body)))
        val mixed = """{"status":"completed","steps":[{"type":"user_input","content":[{"type":"text","text":"x"}]},{"type":"model_output","content":[{"type":"text","text":"שלום "},{"type":"thought","text":"the speaker greets"},{"type":"text","text":"לכולם"}]}]}"""
        assertEquals("שלום לכולם", GeminiSpeech.transcript(geminiReply(200, mixed)))
        assertEquals("", GeminiSpeech.transcript(geminiReply(200, """{"status":"completed","steps":[]}""")))
        val textless = """{"steps":[{"type":"model_output","content":[{"type":"text"},{"type":"text","text":"שלום"}]}]}"""
        assertEquals("שלום", GeminiSpeech.transcript(geminiReply(200, textless)))
        assertFailsWith<CloudSpeechError.BadReply> {
            GeminiSpeech.transcript(geminiReply(200, "<html>"))
        }
    }

    @Test
    fun `Google's answer to a bad key is a 400 that says so, by its reason or its words, any other 400 is the service's trouble`() {
        assertEquals(
            CloudSpeechError.KeyRejected,
            GeminiSpeech.failure(geminiReply(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")),
        )
        assertEquals(
            CloudSpeechError.ServerTrouble(400),
            GeminiSpeech.failure(geminiReply(400, """{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}""")),
        )
        assertEquals(CloudSpeechError.KeyRejected, GeminiSpeech.failure(geminiReply(401, "")))
        assertEquals(CloudSpeechError.KeyRejected, GeminiSpeech.failure(geminiReply(403, """{"error":{"status":"PERMISSION_DENIED"}}""")))
        assertEquals(CloudSpeechError.OutOfCredit, GeminiSpeech.failure(geminiReply(402, "")))
        assertEquals(CloudSpeechError.RateLimited, GeminiSpeech.failure(geminiReply(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}""")))
        assertEquals(CloudSpeechError.ServerTrouble(503), GeminiSpeech.failure(geminiReply(503, "")))
        assertEquals(
            CloudSpeechError.KeyRejected,
            GeminiSpeech.failure(geminiReply(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")),
        )
        assertFailsWith<CloudSpeechError.KeyRejected> {
            GeminiSpeech.transcript(geminiReply(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"""))
        }
    }

    @Test
    fun `the key is checked by listing one model, with the key in a header rather than the address`() {
        val request = GeminiSpeech.keyCheckRequest("AIza-test")
        assertEquals("GET", request.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1", request.url.toString())
        assertEquals("AIza-test", request.headers["x-goog-api-key"])
    }
}
