package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationStatsSharedCasesTest {
    private fun text(function: String, input: Double): String? {
        val count = input.toInt()
        return when (function) {
            "minutesText" -> ConversationStats.minutesText(input)
            "speakersText" -> ConversationStats.speakersText(count)
            "linesText" -> ConversationStats.linesText(count)
            "starredLinesText" -> ConversationStats.linesText(count, adjective = "מסומנת" to "מסומנות", englishAdjective = "starred")
            "newLinesText" -> ConversationStats.linesText(count, adjective = "חדשה" to "חדשות", englishAdjective = "new")
            "wordsText" -> ConversationStats.wordsText(count)
            "secondsText" -> ConversationStats.secondsText(count)
            "aboutMinutesLeftText" -> ConversationStats.aboutMinutesLeftText(count)
            "oldConversationsDeletedText" -> ConversationStats.oldConversationsDeletedText(count)
            "paceText" -> ConversationStats.paceText(input)
            else -> null
        }
    }

    @Test
    fun `every counted and timed phrase in the shared table reads the same in every language on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/conversation-stats-text.json").readText(Charsets.UTF_8)
        val table = Json.parseToJsonElement(text).jsonObject
        val languages = table.getValue("languages").jsonArray.map { UILanguage.fromRawValue(it.jsonPrimitive.content) }
        assertEquals(UILanguage.entries.toSet(), languages.filterNotNull().toSet())
        val cases = table.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        for (element in cases) {
            val item = element.jsonObject
            val function = item.getValue("function").jsonPrimitive.content
            val input = item.getValue("input").jsonPrimitive.double
            val expected = item.getValue("expected").jsonObject
            for (language in languages.filterNotNull()) {
                val wanted = expected.getValue(language.rawValue).jsonPrimitive.content
                val actual = Localization.withLanguage(language) { text(function, input) }
                if (actual != wanted) failures += "$function($input) in ${language.rawValue}: table \"$wanted\", Kotlin \"$actual\""
            }
        }
        assertTrue(cases.size > 250)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
