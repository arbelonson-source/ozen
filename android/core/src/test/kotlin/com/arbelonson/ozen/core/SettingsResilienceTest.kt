package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROFILE_ID = "7C9E6679-7425-40DE-944B-E07FC1F90AE7"

private fun decode(json: String): AppSettings = AppSettings.fromJson(json)

class SettingsResilienceTest {
    @Test
    fun `an engine name from a newer build falls back to the default and keeps the enrolled voices`() {
        val settings = decode(
            """{"engine":"someFutureEngine","speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2,0.3]}],"vocabulary":["אביטל"],"hasCompletedOnboarding":true}""",
        )
        assertEquals(AppSettings.default.engine, settings.engine)
        assertEquals(listOf("דנה"), settings.speakerProfiles.map { it.name })
        assertEquals(listOf("אביטל"), settings.vocabulary)
        assertTrue(settings.hasCompletedOnboarding)
    }

    @Test
    fun `one damaged voice profile is dropped and the others are kept`() {
        val settings = decode(
            """{"speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2]},{"name":"בלי מזהה","embedding":[0.3]},{"id":"not-a-uuid","name":"שבור","embedding":"x"},{"id":"${UUID.randomUUID().toString().uppercase()}","name":"יוסי","embedding":[0.4,0.5]}]}""",
        )
        assertEquals(listOf("דנה", "יוסי"), settings.speakerProfiles.map { it.name })
    }

    @Test
    fun `an empty voice print is dropped rather than kept to fail later`() {
        val settings = decode("""{"speakerProfiles":[{"id":"$PROFILE_ID","name":"ריק","embedding":[]}]}""")
        assertTrue(settings.speakerProfiles.isEmpty())
    }

    @Test
    fun `an unreadable display value only resets that value`() {
        val settings = decode("""{"display":{"fontSize":48,"theme":"neon","boldText":true},"saveHistory":false}""")
        assertEquals(48.0, settings.display.fontSize)
        assertEquals(DisplayPreferences.default.theme, settings.display.theme)
        assertTrue(settings.display.boldText)
        assertEquals(false, settings.saveHistory)
    }

    @Test
    fun `a value of the wrong type resets just that setting`() {
        val settings = decode(
            """{"saveHistory":"yes please","speechRate":0.3,"keywordAlerts":[{"id":"$PROFILE_ID","phrase":"סבתא"},42,{"phrase":"בלי מזהה"}],"soundAlerts":{"isEnabled":false,"minimumImportance":"loud"}}""",
        )
        assertEquals(AppSettings.default.saveHistory, settings.saveHistory)
        assertEquals(0.3f, settings.speechRate)
        assertEquals(listOf("סבתא"), settings.keywordAlerts.map { it.phrase })
        assertEquals(false, settings.soundAlerts.isEnabled)
        assertEquals(SoundAlertPreferences.default.minimumImportance, settings.soundAlerts.minimumImportance)
    }

    @Test
    fun `settings read back from damaged files can be saved again`() {
        val settings = decode(
            """{"engine":"someFutureEngine","speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2]}]}""",
        )
        val folder = File(System.getProperty("java.io.tmpdir"), "ozen-resilience-${UUID.randomUUID()}")
        try {
            val store = SettingsStore(File(folder, "settings.json"))
            store.save(settings)
            assertEquals(listOf("דנה"), store.load().speakerProfiles.map { it.name })
        } finally {
            folder.deleteRecursively()
        }
    }
}

class TranscriptRecordResilienceTest {
    @Test
    fun `an unknown engine and one damaged line still load the rest of the conversation`() {
        val id = UUID.randomUUID().toString().uppercase()
        val json = """
            {"id":"$id","startedAt":100,"engine":"someFutureEngine","segments":[
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":"שלום","startTimestamp":100,"isCommitted":true},
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":42,"startTimestamp":101,"isCommitted":true},
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":"להתראות","startTimestamp":102,"isCommitted":true}
            ],"title":7}
        """.trimIndent()
        val record = TranscriptSessionRecord.fromJson(json)
        assertEquals(id, record.id.toString().uppercase())
        assertEquals(listOf("שלום", "להתראות"), record.segments.map { it.text })
        assertEquals(TranscriptionEngineKind.WhisperKit, record.engine)
        assertNull(record.title)
    }
}
