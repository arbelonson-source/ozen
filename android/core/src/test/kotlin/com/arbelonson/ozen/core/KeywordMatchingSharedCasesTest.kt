package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordMatchingSharedCasesTest {
    @Test
    fun `every case in the shared table finds the same alerts on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/keyword-matching.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size > 50)
        var checked = 0
        for (element in cases) {
            val item = element.jsonObject
            val line = item.getValue("text").jsonPrimitive.content
            item["knownDifference"]?.jsonPrimitive?.contentOrNull?.let {
                println("skipped (known difference): $line - $it")
                continue
            }
            checked++
            val alerts = item.getValue("alerts").jsonArray.map {
                val alert = it.jsonObject
                KeywordAlert(phrase = alert.getValue("phrase").jsonPrimitive.content, isEnabled = alert["isEnabled"]?.jsonPrimitive?.boolean ?: true)
            }
            val matcher = KeywordAlertMatcher(alerts)
            val found = if (item["live"]?.jsonPrimitive?.boolean == true) matcher.matchesInLiveText(line) else matcher.matches(line)
            val expected = item.getValue("matches").jsonArray.map {
                val match = it.jsonObject
                Triple(match.getValue("phrase").jsonPrimitive.content, match.getValue("matchedText").jsonPrimitive.content, match.getValue("wordIndex").jsonPrimitive.int)
            }
            assertEquals(expected, found.map { Triple(it.phrase, it.matchedText, it.wordIndex) }, "text: $line")
        }
        assertTrue(checked > 50)
    }
}
