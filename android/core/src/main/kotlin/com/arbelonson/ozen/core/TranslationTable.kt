package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object TranslationTable {
    private val tablesByLanguage: Map<String, Map<String, String>> by lazy { load() }

    fun lookup(english: String, language: UILanguage): String? = tablesByLanguage[language.rawValue]?.get(english)

    fun table(language: UILanguage): Map<String, String> = tablesByLanguage[language.rawValue].orEmpty()

    private fun load(): Map<String, Map<String, String>> {
        val text = TranslationTable::class.java.getResourceAsStream("/Translations.json")
            ?.use { it.readBytes().decodeToString() }
            ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, table) ->
                table.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            }
        }.getOrDefault(emptyMap())
    }
}
