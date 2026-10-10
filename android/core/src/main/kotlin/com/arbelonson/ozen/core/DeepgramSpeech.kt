package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object DeepgramSpeech {
    const val MODEL = "nova-3"
    val languages: Set<String> = setOf("he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh")
    val listenURL: URI = URI("https://api.deepgram.com/v1/listen")
    val keyURL: URI = URI("https://api.deepgram.com/v1/projects")
    internal const val MAXIMUM_TERMS = 100

    /**
     * Deepgram fails the whole request when its names come to more than
     * 500 tokens. A name is never more tokens than it has bytes; one more
     * is counted for whatever comes between names.
     */
    internal const val TERM_TOKENS = 500

    fun request(model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest {
        val settings = listOf(
            "model" to model,
            "language" to languageCode,
            "punctuate" to "true",
            "smart_format" to "true",
            "utterances" to "true",
            "diarize_model" to "latest",
            "mip_opt_out" to "true",
        ) + terms(vocabulary).map { "keyterm" to it }
        return CloudHTTPRequest(
            url = CloudQuery.url(listenURL, settings),
            method = "POST",
            headers = headers(apiKey) + mapOf("Content-Type" to "audio/wav"),
            body = wav,
            timeoutSeconds = 20.0,
        )
    }

    /** Whole names from the top of the list, as many as fit. */
    internal fun terms(vocabulary: List<String>): List<String> {
        var spent = 0
        val kept = ArrayList<String>()
        for (term in VocabularyHints.normalized(vocabulary).take(MAXIMUM_TERMS)) {
            spent += term.toByteArray(Charsets.UTF_8).size + 1
            if (spent > TERM_TOKENS) break
            kept.add(term)
        }
        return kept
    }

    fun transcript(response: CloudHTTPResponse): String {
        if (response.status !in 200..299) throw failure(response)
        val reply = parsedObject(response.body) ?: throw CloudSpeechError.BadReply
        val results = reply["results"] as? JsonObject ?: throw CloudSpeechError.BadReply
        val utterances = dictionaries(results["utterances"])
        if (utterances != null && utterances.isNotEmpty()) {
            return utterances.mapNotNull { utterance ->
                val text = utterance["transcript"] as? JsonPrimitive
                if (text == null || !text.isString || text.content.isEmpty()) return@mapNotNull null
                val speaker = integerValue(utterance["speaker"]) ?: return@mapNotNull text.content
                "Speaker $speaker: ${text.content}"
            }.joinToString("\n")
        }
        val alternatives = dictionaries(dictionaries(results["channels"])?.firstOrNull()?.get("alternatives"))
        val text = alternatives?.firstOrNull()?.get("transcript") as? JsonPrimitive
        return if (text != null && text.isString) text.content else ""
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError = when (response.status) {
        401, 403 -> CloudSpeechError.KeyRejected
        402 -> CloudSpeechError.OutOfCredit
        429 -> CloudSpeechError.RateLimited
        else -> CloudSpeechError.ServerTrouble(response.status)
    }

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = headers(apiKey), timeoutSeconds = 10.0)

    private fun headers(apiKey: String): Map<String, String> = mapOf("Authorization" to "Token $apiKey")

    private fun dictionaries(element: JsonElement?): List<JsonObject>? {
        val list = element as? JsonArray ?: return null
        return if (list.all { it is JsonObject }) list.map { it as JsonObject } else null
    }

    private fun integerValue(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (element is JsonNull || primitive.isString) return null
        return when (primitive.content) {
            "true" -> 1
            "false" -> 0
            else -> primitive.content.toDoubleOrNull()?.toInt()
        }
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
