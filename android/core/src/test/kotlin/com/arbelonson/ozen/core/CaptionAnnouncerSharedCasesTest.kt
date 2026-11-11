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

class CaptionAnnouncerSharedCasesTest {
    private fun line(item: JsonObject) = TranscriptSegment(
        id = UUID.fromString("00000000-0000-0000-0000-%012d".format(item.getValue("id").jsonPrimitive.int)),
        text = item.getValue("text").jsonPrimitive.content,
        isCommitted = item["committed"]?.jsonPrimitive?.boolean ?: true,
        speakerClusterID = item["speaker"]?.jsonPrimitive?.int,
        startTimestamp = item["start"]?.jsonPrimitive?.double ?: 0.0,
        lastUpdateTimestamp = item["end"]?.jsonPrimitive?.double ?: 0.0,
        isProvisionalCommit = item["provisional"]?.jsonPrimitive?.boolean ?: false,
    )

    @Test
    fun `every sequence of caption lines is read out the same way on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/caption-announcer.json").readText(Charsets.UTF_8)
        val sequences = Json.parseToJsonElement(text).jsonObject.getValue("sequences").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        var shown = 0
        Localization.withLanguage(UILanguage.English) {
            for (sequence in sequences) {
                val note = sequence.getValue("note").jsonPrimitive.content
                val names = sequence["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val announcer = CaptionAnnouncer()
                for ((index, element) in sequence.getValue("steps").jsonArray.withIndex()) {
                    val step = element.jsonObject
                    val raw = (step["show"] ?: step.getValue("skip")).jsonArray.map { it.jsonObject }
                    val segments = raw.map(::line)
                    val unsure = raw.zip(segments).filter { it.first["unsure"]?.jsonPrimitive?.boolean == true }.map { it.second.id }.toSet()
                    if ("skip" in step) {
                        announcer.skipLinesSoFar(segments)
                        continue
                    }
                    val said = announcer.announcement(segments, { names[it.speakerClusterID.toString()] }, { it.id in unsure })
                    val wanted = step.getValue("say").jsonPrimitive.contentOrNull
                    if (said != wanted) failures += "$note, step ${index + 1}: table $wanted, Kotlin $said"
                    shown += 1
                }
            }
        }
        assertTrue(sequences.size > 20 && shown > 40)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
