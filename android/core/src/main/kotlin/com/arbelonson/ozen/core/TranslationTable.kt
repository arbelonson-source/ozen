package com.arbelonson.ozen.core

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object TranslationTable {
    private val lines = MapSerializer(String.serializer(), String.serializer())
    private val tables = LanguageTables(::read)

    fun lookup(english: String, language: UILanguage): String? = tables[language][english]

    fun table(language: UILanguage): Map<String, String> = tables[language]

    private fun read(language: UILanguage): Map<String, String> {
        val text = TranslationTable::class.java.getResourceAsStream("/translations/${language.rawValue}.json")
            ?.use { it.readBytes().decodeToString() }
            ?: return emptyMap()
        return runCatching { Json.decodeFromString(lines, text) }.getOrDefault(emptyMap())
    }
}

internal class LanguageTables(read: (UILanguage) -> Map<String, String>) {
    private val tables = UILanguage.entries.associateWith { language -> lazy { read(language) } }

    operator fun get(language: UILanguage): Map<String, String> = tables.getValue(language).value
}
