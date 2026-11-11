package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockScreenSharedCasesTest {
    private val table: JsonObject =
        Json.parseToJsonElement(File(System.getProperty("ozen.fixtures"), "logic/lock-screen.json").readText(Charsets.UTF_8)).jsonObject

    @Test
    fun `the end of a line is cut at the same letter and word on this platform, in every script`() {
        val cases = table.getValue("tail").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { item ->
            val text = item.getValue("text").jsonPrimitive.content
            val maximum = item.getValue("maximumCharacters").jsonPrimitive.int
            val wanted = item.getValue("expected").jsonPrimitive.content
            val actual = LockScreenCaptions.tail(text, maximum)
            if (actual == wanted) null else "$maximum: table \"$wanted\", Kotlin \"$actual\""
        }
        assertTrue(cases.size > 20)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `the lock screen picks and cuts the same lines, with the same names, on this platform`() {
        val names = table.getValue("names").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val sets = table.getValue("lines").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (set in sets) {
            val segments = set.getValue("segments").jsonArray.map { it.jsonObject }.map { item ->
                TranscriptSegment(
                    id = UUID.fromString("00000000-0000-0000-0000-%012d".format(item.getValue("id").jsonPrimitive.int)),
                    text = item.getValue("text").jsonPrimitive.content,
                    isCommitted = item["committed"]?.jsonPrimitive?.boolean ?: true,
                    speakerClusterID = item["speaker"]?.jsonPrimitive?.int,
                    startTimestamp = item["start"]?.jsonPrimitive?.double ?: 0.0,
                    lastUpdateTimestamp = item["end"]?.jsonPrimitive?.double ?: 0.0,
                )
            }
            val size = LockScreenTextSize.entries.single { it.name.equals(set.getValue("textSize").jsonPrimitive.content, ignoreCase = true) }
            val lines = LockScreenCaptions.lines(segments, set.getValue("count").jsonPrimitive.int, size) { names[it.speakerClusterID.toString()] }
            val expected = set.getValue("expected").jsonArray.map { it.jsonObject }.map {
                listOf(it.getValue("speaker").jsonPrimitive.contentOrNull ?: "-", it.getValue("text").jsonPrimitive.content, "${it.getValue("isFinal").jsonPrimitive.boolean}", "${it.getValue("sameSpeakerAsAbove").jsonPrimitive.boolean}")
            }
            val actual = lines.map { listOf(it.speaker ?: "-", it.text, "${it.isFinal}", "${it.sameSpeakerAsAbove}") }
            if (actual != expected) failures += "${set.getValue("note").jsonPrimitive.content}: table $expected, Kotlin $actual"
        }
        assertTrue(sets.size > 5)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
