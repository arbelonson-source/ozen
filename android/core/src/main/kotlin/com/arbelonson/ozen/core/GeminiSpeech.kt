package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GeminiSpeech {
    const val MODEL = "gemini-3.5-transcribe"
    val interactionsURL: URI = URI("https://generativelanguage.googleapis.com/v1beta/interactions")
    val keyURL: URI = URI("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
    val languageTags: Map<String, String> = mapOf(
        "he" to "he-IL", "en" to "en-US", "ar" to "ar-EG", "ru" to "ru-RU", "am" to "am-ET", "fr" to "fr-FR",
        "es" to "es-ES", "uk" to "uk-UA", "de" to "de-DE", "pt" to "pt-PT", "zh" to "cmn-Hans-CN", "hi" to "hi-IN",
    )
    internal const val MAXIMUM_TERMS = 100

    fun request(model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest {
        val terms = VocabularyHints.normalized(vocabulary).take(MAXIMUM_TERMS)
        val config = buildJsonObject {
            languageTags[languageCode]?.let { tag -> put("language_codes", buildJsonArray { add(JsonPrimitive(tag)) }) }
            if (terms.isNotEmpty()) put("custom_vocabulary", buildJsonArray { terms.forEach { add(JsonPrimitive(it)) } })
        }
        val body = buildJsonObject {
            put("model", model)
            put(
                "input",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "audio")
                            put("mime_type", "audio/wav")
                            put("data", Base64.getEncoder().encodeToString(wav))
                        },
                    )
                },
            )
            put("generation_config", buildJsonObject { put("transcription_config", config) })
        }
        return CloudHTTPRequest(
            url = interactionsURL,
            method = "POST",
            headers = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
            body = body.toString().toByteArray(Charsets.UTF_8),
            timeoutSeconds = 20.0,
        )
    }

    fun transcript(response: CloudHTTPResponse): String {
        if (response.status !in 200..299) throw failure(response)
        val steps = try {
            readSteps(response.body)
        } catch (_: ReplyUnreadable) {
            throw CloudSpeechError.BadReply
        }
        var text = ""
        for (step in steps) {
            if (step.type != null && step.type != "model_output") continue
            for (part in step.content) {
                if (part.type != null && part.type != "text") continue
                text += part.text ?: ""
            }
        }
        return text
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError = when (response.status) {
        400 -> {
            val body = String(response.body, Charsets.UTF_8)
            if (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) CloudSpeechError.KeyRejected else CloudSpeechError.ServerTrouble(400)
        }
        401, 403 -> CloudSpeechError.KeyRejected
        402 -> CloudSpeechError.OutOfCredit
        429 -> CloudSpeechError.RateLimited
        else -> CloudSpeechError.ServerTrouble(response.status)
    }

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = mapOf("x-goog-api-key" to apiKey), timeoutSeconds = 10.0)

    private class ReplyUnreadable : Exception()

    private class Part(val type: String?, val text: String?)

    private class Step(val type: String?, val content: List<Part>)

    private fun text(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw ReplyUnreadable()
        if (!primitive.isString) throw ReplyUnreadable()
        return primitive.content
    }

    private fun readSteps(body: ByteArray): List<Step> {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            val reply = Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(body)).toString()) as? JsonObject ?: throw ReplyUnreadable()
            val steps = reply["steps"]
            if (steps == null || steps is JsonNull) return emptyList()
            return (steps as? JsonArray ?: throw ReplyUnreadable()).map { entry ->
                val step = entry as? JsonObject ?: throw ReplyUnreadable()
                val content = step["content"]
                val parts = if (content == null || content is JsonNull) {
                    emptyList()
                } else {
                    (content as? JsonArray ?: throw ReplyUnreadable()).map { item ->
                        val part = item as? JsonObject ?: throw ReplyUnreadable()
                        Part(text(part["type"]), text(part["text"]))
                    }
                }
                Step(text(step["type"]), parts)
            }
        } catch (_: CharacterCodingException) {
            throw ReplyUnreadable()
        } catch (_: SerializationException) {
            throw ReplyUnreadable()
        } catch (_: IllegalArgumentException) {
            throw ReplyUnreadable()
        }
    }
}
