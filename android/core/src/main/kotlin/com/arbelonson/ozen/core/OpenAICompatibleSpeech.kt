package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object OpenAICompatibleSpeech {
    data class Service(val transcriptionsURL: URI, val modelsURL: URI, val model: String)

    val openAI = Service(
        transcriptionsURL = URI("https://api.openai.com/v1/audio/transcriptions"),
        modelsURL = URI("https://api.openai.com/v1/models"),
        model = "gpt-4o-transcribe",
    )

    val groq = Service(
        transcriptionsURL = URI("https://api.groq.com/openai/v1/audio/transcriptions"),
        modelsURL = URI("https://api.groq.com/openai/v1/models"),
        model = "whisper-large-v3",
    )

    internal const val PROMPT_BYTES = 224

    fun request(service: Service, model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest {
        val form = MultipartForm()
        form.add("model", model)
        form.add("language", languageCode)
        form.add("response_format", "json")
        val prompt = VocabularyHints.whisperPrompt(vocabulary, PROMPT_BYTES) { it.toByteArray(Charsets.UTF_8).size }
        if (prompt.isNotEmpty()) {
            form.add("prompt", prompt)
        }
        form.add("file", "speech.wav", "audio/wav", wav)
        return CloudHTTPRequest(
            url = service.transcriptionsURL,
            method = "POST",
            headers = headers(apiKey) + mapOf("Content-Type" to form.contentType),
            body = form.body,
            timeoutSeconds = 20.0,
        )
    }

    fun transcript(response: CloudHTTPResponse): String {
        if (response.status !in 200..299) throw failure(response)
        val text = parsedObject(response.body)?.get("text") as? JsonPrimitive
        if (text == null || !text.isString) throw CloudSpeechError.BadReply
        return text.content
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError = when (response.status) {
        401, 403 -> CloudSpeechError.KeyRejected
        402 -> CloudSpeechError.OutOfCredit
        429 -> if (errorCode(response.body) == "insufficient_quota") CloudSpeechError.OutOfCredit else CloudSpeechError.RateLimited
        else -> CloudSpeechError.ServerTrouble(response.status)
    }

    fun keyCheckRequest(service: Service, apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = service.modelsURL, headers = headers(apiKey), timeoutSeconds = 10.0)

    private fun headers(apiKey: String): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

    private fun errorCode(body: ByteArray): String? {
        val detail = parsedObject(body)?.get("error") as? JsonObject ?: return null
        val code: JsonElement = detail["code"] ?: return null
        if (code is JsonNull) return null
        val primitive = code as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun parsedObject(body: ByteArray): JsonObject? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(body)).toString()) as? JsonObject
        } catch (_: CharacterCodingException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
