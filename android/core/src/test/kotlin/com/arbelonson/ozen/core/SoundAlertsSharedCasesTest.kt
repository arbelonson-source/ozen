package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundAlertsSharedCasesTest {
    private val table: JsonObject =
        Json.parseToJsonElement(File(System.getProperty("ozen.fixtures"), "logic/sound-alerts.json").readText(Charsets.UTF_8)).jsonObject

    private fun strings(key: String) = table.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the catalog lists the same sounds in the same order, with the same importance, cooldown, picture and names`() {
        val rows = table.getValue("catalog").jsonArray.map { it.jsonObject }
        val hebrew = Localization.withLanguage(UILanguage.Hebrew) { SoundEventCatalog.events }
        val english = Localization.withLanguage(UILanguage.English) { SoundEventCatalog.events }
        assertEquals(rows.map { it.getValue("identifier").jsonPrimitive.content }, hebrew.map { it.identifier })
        val failures = mutableListOf<String>()
        for ((row, events) in rows.zip(hebrew.zip(english))) {
            val (inHebrew, inEnglish) = events
            val wanted = listOf("importance", "sameSoundAs", "systemImage", "hebrew", "english").map { row.getValue(it).jsonPrimitive.content }
            val actual = listOf(inHebrew.importance.rawValue.toString(), inHebrew.cooldownKey, inHebrew.systemImage, inHebrew.name, inEnglish.name)
            if (actual != wanted) failures += "${inHebrew.identifier}: table $wanted, Kotlin $actual"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertEquals(strings("listed"), SoundEventCatalog.listed.map { it.identifier })
        assertEquals(strings("vibrationLookalikes").toSet(), SoundEventCatalog.vibrationLookalikes)
        assertEquals(strings("sustained").toSet(), SoundEventPolicy.sustainedIdentifiers)
    }

    @Test
    fun `every heard sequence raises the same alerts at the same moments`() {
        val sequences = table.getValue("sequences").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        var heard = 0
        for (sequence in sequences) {
            val note = sequence.getValue("note").jsonPrimitive.content
            val given = sequence["preferences"]?.jsonObject ?: JsonObject(emptyMap())
            val defaults = SoundAlertPreferences.default
            val preferences = SoundAlertPreferences(
                isEnabled = given["isEnabled"]?.jsonPrimitive?.booleanOrNull ?: defaults.isEnabled,
                minimumImportance = given["minimumImportance"]?.jsonPrimitive?.intOrNull?.let { SoundEvent.Importance.fromRawValue(it) } ?: defaults.minimumImportance,
                mutedIdentifiers = given["mutedIdentifiers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                sensitiveIdentifiers = given["sensitiveIdentifiers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
            )
            val policy = SoundEventPolicy(preferences = preferences)
            val changed = sequence["policy"]?.jsonObject ?: JsonObject(emptyMap())
            changed["minimumConfidence"]?.let { policy.minimumConfidence = it.jsonPrimitive.double }
            changed["sensitiveConfidence"]?.let { policy.sensitiveConfidence = it.jsonPrimitive.double }
            changed["cooldownSeconds"]?.let { policy.cooldownSeconds = it.jsonPrimitive.double }
            changed["emergencyConfidence"]?.let { policy.emergencyConfidence = it.jsonPrimitive.double }
            changed["persistenceWindowSeconds"]?.let { policy.persistenceWindowSeconds = it.jsonPrimitive.double }
            for ((index, element) in sequence.getValue("steps").jsonArray.withIndex()) {
                val step = element.jsonObject
                if ("reset" in step) {
                    policy.resetCooldowns()
                    continue
                }
                val hear = step.getValue("hear").jsonArray.map { it.jsonPrimitive }
                val observation = SoundObservation(hear[0].content, hear[1].double, hear[2].double)
                val alert = policy.evaluate(observation)
                val wanted = step.getValue("alert").jsonPrimitive.contentOrNull
                if (alert?.event?.identifier != wanted) failures += "$note, step ${index + 1}: table $wanted, Kotlin ${alert?.event?.identifier}"
                if (alert != null && (alert.confidence != observation.confidence || alert.timestamp != observation.timestamp)) {
                    failures += "$note, step ${index + 1}: the alert carries ${alert.confidence} at ${alert.timestamp}"
                }
                heard += 1
            }
        }
        assertTrue(sequences.size > 20 && heard > 70)
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `what is heard in one reading, which alert takes the banner and for how long, and the gate between gaps`() {
        for (item in table.getValue("matching").jsonArray.map { it.jsonObject }) {
            val candidates = item.getValue("candidates").jsonArray.map { it.jsonArray }.map { it[0].jsonPrimitive.content to it[1].jsonPrimitive.double }
            val found = SoundEventCatalog.matchingObservations(candidates, item.getValue("minimumConfidence").jsonPrimitive.double, 7.0)
            val expected = item.getValue("expected").jsonArray.map { it.jsonArray }.map { SoundObservation(it[0].jsonPrimitive.content, it[1].jsonPrimitive.double, 7.0) }
            assertEquals(expected, found, item.getValue("note").jsonPrimitive.content)
        }
        for (item in table.getValue("banners").jsonArray.map { it.jsonObject }) {
            val alert = SoundAlert(event = SoundEventCatalog.event(item.getValue("alert").jsonPrimitive.content)!!, confidence = 0.9, timestamp = 1.0)
            val shown = item.getValue("shown").jsonPrimitive.contentOrNull?.let { SoundAlert(event = SoundEventCatalog.event(it)!!, confidence = 0.9, timestamp = 0.0) }
            assertEquals(item.getValue("takesBanner").jsonPrimitive.boolean, alert.takesBanner(shown), "$item")
            assertEquals(item.getValue("bannerSeconds").jsonPrimitive.double, alert.bannerSeconds, "$item")
        }
        val gate = SoundAlertGate()
        for (item in table.getValue("gate").jsonArray.map { it.jsonObject }) {
            val passed = gate.pass(FloatArray(item.getValue("length").jsonPrimitive.int) { 0.5f }, item.getValue("on").jsonPrimitive.boolean).map { it.size }
            assertEquals(item.getValue("passed").jsonArray.map { it.jsonPrimitive.int }, passed, "$item")
        }
    }
}
