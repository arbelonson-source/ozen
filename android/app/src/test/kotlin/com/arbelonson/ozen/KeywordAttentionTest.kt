package com.arbelonson.ozen

import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeywordAttentionTest {
    private val grandma = UUID.randomUUID()
    private val doctor = UUID.randomUUID()
    private val nurse = UUID.randomUUID()

    private fun hit(alert: UUID, phrase: String, said: String, at: Double) =
        KeywordHit(segmentID = UUID.randomUUID(), match = KeywordMatch(alert, phrase, said, 0), timestamp = at)

    @Test
    fun `each word buzzes at most once in fifteen seconds, every word in a line starting its own wait`() {
        val attention = KeywordAttention()
        Localization.withLanguage(UILanguage.English) {
            val first = hit(grandma, "סבתא", "לסבתא", 100.0)
            assertEquals(HeardWord(first.id, "Grandma", 100.0, line = first.segmentID), attention.claim(listOf(first)))
            assertNull(attention.claim(listOf(hit(grandma, "סבתא", "סבתא", 110.0))))

            val line = listOf(hit(doctor, "רופא", "רופא", 120.0), hit(nurse, "אחות", "אחות", 120.0))
            assertEquals("Doctor", attention.claim(line)?.said)
            assertNull(attention.claim(listOf(hit(nurse, "אחות", "אחות", 125.0))))
            assertEquals("Grandma", attention.claim(listOf(hit(grandma, "סבתא", "סבתא", 116.0)))?.said)
        }
    }

    @Test
    fun `a word not among the suggestions is said as it was heard`() {
        val heard = Localization.withLanguage(UILanguage.English) { KeywordAttention().claim(listOf(hit(nurse, "אחות", "לאחות", 100.0))) }
        assertEquals("לאחות", heard?.said)
    }

    @Test
    fun `the important-word buzz is three short pulses with the iPhone's gaps`() {
        val (timings, amplitudes) = AlertVibration.keyword.waveform()
        assertContentEquals(longArrayOf(0, 130, 110, 130, 110, 130), timings)
        assertContentEquals(intArrayOf(0, 255, 0, 255, 0, 255), amplitudes)
    }

    @Test
    fun `only a critical sound buzzes as an alarm, everything else as a notification`() {
        assertEquals(Buzz.Usage.Alarm, Buzz.usageFor(SoundEvent.Importance.Critical))
        for (importance in listOf(SoundEvent.Importance.High, SoundEvent.Importance.Medium, SoundEvent.Importance.Low, null)) {
            assertEquals(Buzz.Usage.Notification, Buzz.usageFor(importance))
        }
    }
}
