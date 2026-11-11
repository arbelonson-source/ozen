package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryDaysSharedCasesTest {
    @Test
    fun `every heading the app writes itself reads the same on this platform as in the shared table`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/history-days.json").readText(Charsets.UTF_8)
        val table = Json.parseToJsonElement(text).jsonObject
        val languages = table.getValue("languages").jsonArray.map { UILanguage.fromRawValue(it.jsonPrimitive.content) }
        assertEquals(UILanguage.entries.toSet(), languages.filterNotNull().toSet())
        val cases = table.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        var checked = 0
        for (element in cases) {
            val item = element.jsonObject
            val day = item.getValue("day").jsonPrimitive.long
            val today = item.getValue("today").jsonPrimitive.long
            val note = item.getValue("note").jsonPrimitive.content
            val expected = item.getValue("expected").jsonObject
            if ("hebrew" !in expected || "english" !in expected) failures += "$note: no Hebrew or English heading"
            for (language in languages.filterNotNull()) {
                val wanted = expected[language.rawValue]?.jsonPrimitive?.content ?: continue
                val actual = Localization.withLanguage(language) { HistoryDays.title(day, today) }
                if (actual != wanted) failures += "$note in ${language.rawValue}: table \"$wanted\", Kotlin \"$actual\""
                checked += 1
            }
        }
        assertTrue(cases.size > 40 && checked > 100)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
