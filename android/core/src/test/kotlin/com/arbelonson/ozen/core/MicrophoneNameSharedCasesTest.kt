package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicrophoneNameSharedCasesTest {
    @Test
    fun `every microphone in the shared table, from its connection and name, is the same kind on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/microphone-names.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (item in cases) {
            val name = item.getValue("name").jsonPrimitive.content
            val connection = AudioPortType.fromRawValue(item.getValue("connection").jsonPrimitive.content)!!
            val expected = item.getValue("expected").jsonPrimitive.content
            val actual = AudioPortType.of(connection, name).rawValue
            if (actual != expected) failures += "\"$name\" over $connection: table $expected, Kotlin $actual"
        }
        assertTrue(cases.size > 40)
        assertEquals(setOf("remoteMic", "hearingAid", "bluetooth", "usb", "wired", "builtInMic", "other"), cases.map { it.getValue("expected").jsonPrimitive.content }.toSet())
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
