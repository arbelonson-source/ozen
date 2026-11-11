package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSpeechSharedCasesTest {
    @Test
    fun `every cloud transcript in the shared table splits into the same caption lines on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/cloud-turns.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { item ->
            val transcript = item.getValue("transcript").jsonPrimitive.content
            val wanted = item.getValue("turns").jsonArray.map { it.jsonPrimitive.content }
            val actual = CloudSpeech.turns(transcript)
            if (actual == wanted) null else "\"$transcript\": table $wanted, Kotlin $actual"
        }
        assertTrue(cases.size > 50)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
