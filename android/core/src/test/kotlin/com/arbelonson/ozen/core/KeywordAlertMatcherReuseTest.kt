package com.arbelonson.ozen.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KeywordAlertMatcherReuseTest {
    private class Case(val text: String, val alerts: List<KeywordAlert>, val language: String)

    private fun sharedCases(): List<Case> {
        val text = File(System.getProperty("ozen.fixtures"), "logic/keyword-matching.json").readText(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { element ->
            val item = element.jsonObject
            val alerts = item.getValue("alerts").jsonArray.map {
                val alert = it.jsonObject
                KeywordAlert(phrase = alert.getValue("phrase").jsonPrimitive.content, isEnabled = alert["isEnabled"]?.jsonPrimitive?.boolean ?: true)
            }
            Case(item.getValue("text").jsonPrimitive.content, alerts, item["language"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    }

    @Test
    fun `a matcher that read other lines and each pass of this one finds what a fresh one finds`() {
        val matchers = HashMap<String, KeywordAlertMatcher>()
        var compared = 0
        for (case in sharedCases()) {
            val used = matchers.getOrPut(case.language) { KeywordAlertMatcher(emptyList(), case.language) }
            used.alerts = case.alerts
            val words = case.text.split(" ")
            val grown = (1..words.size).map { words.take(it).joinToString(" ") }
            val passes = grown + grown.map { "$it נוע..." } + grown.map { "$it - , ...… ..." } + "\u200F${case.text} נוע…" +
                words.drop(1).joinToString(" ") + "${words.first()} ${case.text}" + case.text
            for (pass in passes) {
                val live = KeywordAlertMatcher(case.alerts, case.language).matches(KeywordAlertMatcher.droppingCutOffWord(pass))
                assertEquals(live, used.matchesInLiveText(pass), "live: $pass")
                assertEquals(KeywordAlertMatcher(case.alerts, case.language).matches(pass), used.matches(pass), "final: $pass")
                compared++
            }
        }
        assertTrue(compared > 200)
    }

    @Test
    fun `an alert changed in place is matched as it reads now`() {
        val alert = KeywordAlert(phrase = "דנה")
        val matcher = KeywordAlertMatcher(listOf(alert), "he")
        val line = "שלום דנה ונועה"
        assertEquals(listOf("דנה"), matcher.matches(line).map { it.matchedText })
        alert.phrase = "נועה"
        assertEquals(listOf("ונועה"), matcher.matches(line).map { it.matchedText })
        alert.isEnabled = false
        assertEquals(emptyList<KeywordMatch>(), matcher.matches(line))
        assertEquals(emptyList<KeywordMatch>(), matcher.matchesInLiveText(line))
    }

    @Test
    fun `a cut-off last word is found past direction marks, spaces and joiners`() {
        val lines = listOf(
            "שלום נוע..." to "שלום",
            "שלום נוע.\u200F.." to "שלום",
            "שלום נוע…\u200F " to "שלום",
            "שלום נוע…-" to "שלום",
            "שלום נוע.." to "שלום נוע..",
            "שלום נוע…." to "שלום נוע….",
            "שלום ..." to "",
            "   " to "   ",
            "" to "",
        )
        for ((line, kept) in lines) assertEquals(kept, KeywordAlertMatcher.droppingCutOffWord(line), line)
    }
}
