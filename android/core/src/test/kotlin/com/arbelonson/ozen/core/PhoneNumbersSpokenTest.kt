package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneNumbersSpokenTest {
    @Test
    fun `a number is spoken digit by digit in short groups, as on the other phone`() {
        val table = Json.parseToJsonElement(File(System.getProperty("ozen.fixtures"), "logic/phone-spoken.json").readText(Charsets.UTF_8)).jsonObject
        val cases = table.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { item ->
            val text = item.getValue("text").jsonPrimitive.content
            val wanted = item.getValue("spoken").jsonPrimitive.content
            val actual = PhoneNumbers.spokenForm(PhoneNumbers.matches(text).first(), text)
            if (actual == wanted) null else "\"$text\": wanted \"$wanted\", got \"$actual\""
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertTrue(cases.size >= 10)
    }
}
