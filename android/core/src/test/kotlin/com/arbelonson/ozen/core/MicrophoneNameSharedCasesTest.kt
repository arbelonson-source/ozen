package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicrophoneNameSharedCasesTest {
    @Test
    fun `every microphone name in the shared table reads as the same kind on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/microphone-names.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (item in cases) {
            val name = item.getValue("name").jsonPrimitive.content
            val expected = item.getValue("expected").jsonPrimitive.contentOrNull
            val actual = AudioPortType.named(name)?.rawValue
            if (actual != expected) failures += "\"$name\": table $expected, Kotlin $actual"
        }
        assertTrue(cases.size > 40)
        assertEquals(setOf("remoteMic", "hearingAid"), cases.mapNotNull { it.getValue("expected").jsonPrimitive.contentOrNull }.toSet())
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
