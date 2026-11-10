package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionStabilizerSharedCasesTest {
    private fun id(u: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(u))

    private fun state(segment: TranscriptSegment): List<Any?> = listOf(
        segment.id.toString().takeLast(12).toInt(), segment.text, segment.isCommitted, segment.isProvisionalCommit,
        segment.speakerClusterID, segment.startTimestamp, segment.lastUpdateTimestamp,
    )

    private fun state(row: JsonArray): List<Any?> = listOf(
        row[0].jsonPrimitive.int, row[1].jsonPrimitive.content, row[2].jsonPrimitive.boolean, row[3].jsonPrimitive.boolean,
        row[4].jsonPrimitive.intOrNull, row[5].jsonPrimitive.double, row[6].jsonPrimitive.double,
    )

    @Test
    fun `every sequence in the shared table leaves the same lines after every step on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/caption-stabilizer.json").readText(Charsets.UTF_8)
        val sequences = Json.parseToJsonElement(text).jsonObject.getValue("sequences").jsonArray
        val failures = mutableListOf<String>()
        var steps = 0
        for (element in sequences) {
            val sequence = element.jsonObject
            val name = sequence.getValue("name").jsonPrimitive.content
            val stabilizer = CaptionStabilizer()
            for ((index, stepElement) in sequence.getValue("steps").jsonArray.withIndex()) {
                val step = stepElement.jsonObject
                when (step.getValue("op").jsonPrimitive.content) {
                    "ingest" -> stabilizer.ingest(
                        TranscriptToken(
                            utteranceID = id(step.getValue("u").jsonPrimitive.int),
                            text = step.getValue("text").jsonPrimitive.content,
                            isFinal = step.getValue("final").jsonPrimitive.boolean,
                            timestamp = step.getValue("at").jsonPrimitive.double,
                            speakerClusterID = step["speaker"]?.jsonPrimitive?.intOrNull,
                            startsNewSpeakerTurn = step.getValue("newTurn").jsonPrimitive.boolean,
                        ),
                    )
                    "commitStale" -> stabilizer.commitStale(step.getValue("at").jsonPrimitive.double)
                    "commitAll" -> stabilizer.commitAll()
                    "commit" -> stabilizer.commit(id(step.getValue("u").jsonPrimitive.int))
                    else -> failures += "$name: bad step"
                }
                steps++
                val expected = step.getValue("after").jsonArray.map { state(it.jsonArray) }
                val actual = stabilizer.segments.map(::state)
                if (actual != expected) failures += "$name, step ${index + 1}: table $expected, Kotlin $actual"
            }
        }
        assertTrue(steps > 80)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
