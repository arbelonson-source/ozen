package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordFixesSharedCasesTest {
    @Test
    fun `every saved list of word fixes changes each line the same way on this platform`() {
        val text = File(System.getProperty("ozen.fixtures"), "logic/word-fixes.json").readText(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (item in cases) {
            val saved = item.getValue("fixes").jsonArray.map { pair -> pair.jsonArray.let { WordFix(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) } }
            val line = item.getValue("text").jsonPrimitive.content
            val wanted = item.getValue("expected").jsonPrimitive.content
            val actual = WordFixes.apply(WordFixes.normalized(saved), line)
            if (actual != wanted) failures += "$saved on \"$line\": table \"$wanted\", Kotlin \"$actual\""
        }
        assertTrue(cases.size > 100)
        assertTrue(cases.count { it.getValue("expected").jsonPrimitive.content != it.getValue("text").jsonPrimitive.content } > 50)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
