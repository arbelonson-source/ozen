package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionTextSharedCasesTest {
    private fun strings(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

    private fun perLanguage(element: JsonElement): List<Pair<String, JsonElement>> =
        element.jsonObject.flatMap { (key, value) -> key.split(",").map { it to value } }

    @Test
    fun `every case in the shared table gives the same answer on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/caption-text.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        fun check(label: String, expected: Any, actual: Any) {
            if (expected != actual) failures += "$label: table $expected, Kotlin $actual"
        }
        for (element in cases) {
            val item = element.jsonObject
            val kind = item.getValue("kind").jsonPrimitive.content
            val input = item.getValue("input").jsonPrimitive.content
            val expected = item.getValue("expected")
            when (kind) {
                "phoneNumbers" -> check(
                    "$kind \"$input\"",
                    expected.jsonArray.map(::strings),
                    PhoneNumbers.matches(input).map { listOf(it.textIn(input), it.dialable) },
                )
                "readableText" -> check("$kind \"$input\"", expected.jsonPrimitive.content, CaptionLayout.readableText(input))
                "copiedText" -> check("$kind \"$input\"", expected.jsonPrimitive.content, CaptionLayout.copiedText(input))
                "displayText" -> perLanguage(expected).forEach { (language, value) ->
                    check("$kind $language \"$input\"", value.jsonPrimitive.content, CaptionLayout.displayText(input, language))
                }
                "directed" -> perLanguage(expected).forEach { (language, value) ->
                    check("$kind $language \"$input\"", value.jsonPrimitive.content, CaptionLayout.directed(input, language))
                }
                "readsRightToLeft" -> perLanguage(expected).forEach { (language, value) ->
                    check("$kind $language \"$input\"", value.jsonPrimitive.boolean, CaptionLayout.readsRightToLeft(input, language))
                }
                "uncertainSpoken" -> check(
                    "$kind \"$input\"",
                    strings(expected),
                    UncertainWords.spoken(input, strings(item.getValue("words"))),
                )
                "uncertainRanges" -> check(
                    "$kind \"$input\"",
                    strings(expected),
                    UncertainWords.ranges(input, strings(item.getValue("words"))).map { input.substring(it.start, it.endExclusive) },
                )
                else -> failures += "bad case: $kind"
            }
        }
        assertTrue(cases.size > 500)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
