package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumberEmphasisSharedCasesTest {
    @Test
    fun `every case in the shared table gives the same answer on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/number-emphasis.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size > 100)
        var checked = 0
        for (element in cases) {
            val item = element.jsonObject
            val input = item.getValue("input").jsonPrimitive.content
            val note = item["knownDifference"]?.jsonPrimitive?.contentOrNull
            if (note != null) {
                println("skipped (known difference): $input - $note")
                continue
            }
            val only = item["only"]?.jsonPrimitive?.contentOrNull
            if (only != null && only != "kotlin") continue
            checked++
            val decimalComma = item["decimalComma"]?.jsonPrimitive?.boolean ?: false
            val expected = item.getValue("expected")
            when (item.getValue("kind").jsonPrimitive.content) {
                "emphasized" -> {
                    val actual = NumberEmphasis.ranges(input, decimalComma).map { input.substring(it.start, it.endExclusive) }
                    assertEquals((expected as JsonArray).map { it.jsonPrimitive.content }, actual, "input: $input")
                }
                "listable" -> assertEquals(
                    (expected as JsonPrimitive).boolean,
                    NumberEmphasis.hasListableNumber(input, decimalComma),
                    "input: $input",
                )
                else -> error("bad case: $input")
            }
        }
        assertTrue(checked > 100)
    }
}
