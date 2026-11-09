package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class WhisperLanguageLines(val known: List<String>, val ambiguous: List<String>, val creditPrefixes: List<String>) {
    companion object {
        private val byLanguage: Map<String, WhisperLanguageLines> by lazy { load() }

        fun lines(language: UILanguage?): WhisperLanguageLines? = language?.let { byLanguage[it.rawValue] }

        private fun load(): Map<String, WhisperLanguageLines> {
            val text = WhisperLanguageLines::class.java.getResourceAsStream("/WhisperLanguageLines.json")
                ?.use { it.readBytes().decodeToString() }
                ?: return emptyMap()
            return runCatching {
                Json.parseToJsonElement(text).jsonObject.mapValues { (_, lines) ->
                    val entry = lines.jsonObject
                    WhisperLanguageLines(strings(entry, "known"), strings(entry, "ambiguous"), strings(entry, "creditPrefixes"))
                }
            }.getOrDefault(emptyMap())
        }

        private fun strings(entry: JsonObject, key: String): List<String> =
            entry[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    }
}
