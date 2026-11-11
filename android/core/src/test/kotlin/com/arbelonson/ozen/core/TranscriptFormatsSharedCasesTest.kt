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

class TranscriptFormatsSharedCasesTest {
    @Test
    fun `every case in the shared table gives the same text on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/transcript-formats.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        for (element in cases) {
            val item = element.jsonObject
            val kind = item.getValue("kind").jsonPrimitive.content
            val expected = item.getValue("expected").jsonPrimitive.content
            if (kind == "clock") {
                val input = item.getValue("input").jsonPrimitive.double
                val actual = TranscriptFormats.clock(input)
                if (actual != expected) failures += "clock($input): table \"$expected\", Kotlin \"$actual\""
                continue
            }
            val lines = item.getValue("lines").jsonArray.map { line ->
                val parts = line.jsonArray
                TranscribedLine(parts[0].jsonPrimitive.double, parts[1].jsonPrimitive.double, parts[2].jsonPrimitive.content)
            }
            val code = item.getValue("language").jsonPrimitive.content
            val actual = if (kind == "text") TranscriptFormats.text(lines, code) else TranscriptFormats.srt(lines, code)
            if (actual != expected) failures += "$kind $code ${lines.map { it.text }}: table \"$expected\", Kotlin \"$actual\""
        }
        assertTrue(cases.size > 70)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
