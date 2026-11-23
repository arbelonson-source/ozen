package com.arbelonson.ozen.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TranslationTableTest {
    private val whole: Map<String, Map<String, String>> by lazy {
        val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
        val text = File(root, "Sources/OzenKit/Resources/Translations.json").readText(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject.mapValues { (_, table) ->
            table.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        }
    }

    @Test
    fun `every language gives every line exactly as the shared table has it`() {
        val translated = UILanguage.entries.filter { it != UILanguage.Hebrew && it != UILanguage.English }
        assertEquals(translated.map { it.rawValue }.toSet(), whole.keys)
        for (language in translated) {
            val lines = whole.getValue(language.rawValue)
            assertTrue(lines.size > 1_000, "$language")
            assertEquals(lines, TranslationTable.table(language), "$language")
            for ((english, text) in lines) assertEquals(text, TranslationTable.lookup(english, language), "$language: $english")
        }
        assertTrue(TranslationTable.table(UILanguage.Hebrew).isEmpty())
        assertTrue(TranslationTable.table(UILanguage.English).isEmpty())
    }

    @Test
    fun `switching to another language brings that language's lines`() {
        val english = "Start captions"
        val order = listOf(UILanguage.French, UILanguage.German, UILanguage.Hindi, UILanguage.French, UILanguage.Arabic)
        for (language in order) {
            assertEquals(whole.getValue(language.rawValue).getValue(english), tr("התחלת כתוביות", english, language), "$language")
        }
    }

    @Test
    fun `a lookup made while the language is still loading waits for it instead of giving English`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val tables = LanguageTables { language ->
            reads.incrementAndGet()
            reading.countDown()
            release.await(10, TimeUnit.SECONDS)
            if (language == UILanguage.French) mapOf("Start captions" to "Démarrer les sous-titres") else emptyMap()
        }
        val warming = thread(name = "warming") { tables[UILanguage.French] }
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        var seen: String? = null
        val screen = thread(name = "screen") { seen = tables[UILanguage.French]["Start captions"] }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (screen.state != Thread.State.BLOCKED && screen.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
        assertTrue(screen.isAlive, "the screen's lookup returned before the language finished loading")
        release.countDown()
        screen.join(10_000)
        warming.join(10_000)
        assertEquals("Démarrer les sous-titres", seen)
        assertEquals(1, reads.get())
    }
}
