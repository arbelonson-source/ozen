package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class NumberWordProfile(entry: JsonObject) {
    val numbers = strings(entry, "numbers")
    val scales = strings(entry, "scales")
    val connectors = strings(entry, "connectors")
    val fractions = strings(entry, "fractions")
    val fractionLinks = strings(entry, "fractionLinks")
    val clockLinks = strings(entry, "clockLinks")
    val ambiguous = strings(entry, "ambiguous")
    val notACountBefore = strings(entry, "notACountBefore")
    val onceWords = strings(entry, "onceWords")
    val repeatLinks = strings(entry, "repeatLinks")
    val repeatUnits = strings(entry, "repeatUnits")
    val periods = strings(entry, "periods")
    val idioms = strings(entry, "idioms")
    val units = strings(entry, "units")

    companion object {
        private val byLanguage: Map<String, NumberWordProfile> by lazy { load() }

        fun profile(languageCode: String): NumberWordProfile? {
            val language = UILanguage.match(languageCode) ?: return null
            return byLanguage[language.rawValue]?.takeIf { it.numbers.isNotEmpty() }
        }

        private fun load(): Map<String, NumberWordProfile> {
            val text = NumberWordProfile::class.java.getResourceAsStream("/NumberWords.json")
                ?.use { it.readBytes().decodeToString() }
                ?: return emptyMap()
            return runCatching {
                Json.parseToJsonElement(text).jsonObject.mapValues { (_, entry) -> NumberWordProfile(entry.jsonObject) }
            }.getOrDefault(emptyMap())
        }

        private fun strings(entry: JsonObject, key: String): Set<String> =
            entry[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
    }
}
