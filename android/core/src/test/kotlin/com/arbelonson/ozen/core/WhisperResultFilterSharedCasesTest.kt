package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhisperResultFilterSharedCasesTest {
    private val names = listOf("default", "he", "en", "ar", "de", "es", "fr", "pt", "ru", "uk", "zh", "am", "hi")
    private val filters = names.associateWith { if (it == "default") WhisperResultFilter() else WhisperResultFilter().forSpeech(it) }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun `every case in the shared table gives the same answer on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/whisper-result-filter.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        for (element in cases) {
            val item = element.jsonObject
            val expected = item.text("expected")
            when (val kind = item.text("kind")) {
                "knownHallucination" -> {
                    val input = item.text("input")!!
                    val wanted = item.text("hallucinationIn")!!.split(",").filter { it.isNotEmpty() }.toSet()
                    val actual = names.filter { filters.getValue(it).isKnownHallucination(input) }.toSet()
                    if (actual != wanted) failures += "knownHallucination \"$input\": table $wanted, Kotlin $actual"
                }
                "collapsingRepeats" -> {
                    val input = item.text("input")!!
                    val actual = WhisperResultFilter.collapsingRepeats(input, maxRepeats = item.getValue("maxRepeats").jsonPrimitive.int)
                    if (actual != expected) failures += "collapsingRepeats \"$input\": table \"$expected\", Kotlin \"$actual\""
                }
                "repeatedSentence" -> {
                    val input = item.text("input")!!
                    val actual = WhisperResultFilter.repeatedSentence(input)
                    if (actual != expected) failures += "repeatedSentence \"$input\": table \"$expected\", Kotlin \"$actual\""
                }
                "stripSpecialTokens" -> {
                    val input = item.text("input")!!
                    val actual = WhisperResultFilter.stripSpecialTokens(input)
                    if (actual != expected) failures += "stripSpecialTokens \"$input\": table \"$expected\", Kotlin \"$actual\""
                }
                "acceptedText" -> {
                    val segment = WhisperSegmentSummary(
                        text = item.text("text")!!,
                        noSpeechProb = item.getValue("noSpeechProb").jsonPrimitive.float,
                        avgLogprob = item.getValue("avgLogprob").jsonPrimitive.float,
                        compressionRatio = item.getValue("compressionRatio").jsonPrimitive.float,
                    )
                    val actual = filters.getValue(item.text("language")!!).acceptedText(listOf(segment))
                    if (actual != expected) failures += "acceptedText \"${segment.text}\" ${item.text("language")} $segment: table \"$expected\", Kotlin \"$actual\""
                }
                else -> failures += "bad case: $kind"
            }
        }
        assertTrue(cases.size > 600)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
