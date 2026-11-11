package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneNumbersSharedCasesTest {
    @Test
    fun `every line in the shared table gets the same Call buttons, over the same characters, on this platform`() {
        val table = Json.parseToJsonElement(File(System.getProperty("ozen.fixtures"), "logic/phone-numbers.json").readText(Charsets.UTF_8)).jsonObject
        val cases = table.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { item ->
            val text = item.getValue("text").jsonPrimitive.content
            val wanted = item.getValue("found").jsonArray.map { found ->
                val parts = found.jsonArray
                "${parts[0].jsonPrimitive.int}-${parts[1].jsonPrimitive.int} ${parts[2].jsonPrimitive.content}"
            }
            val actual = PhoneNumbers.matches(text).map { "${it.start}-${it.end} ${it.dialable}" }
            if (actual == wanted) null else "\"$text\": table $wanted, Kotlin $actual"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertTrue(cases.size > 100)
    }
}
