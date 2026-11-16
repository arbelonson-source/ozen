package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListeningRestartTest {
    private val day = 1_700_000_000.0 - 1_700_000_000.0 % 86_400
    private fun at(hours: Int, minutes: Int) = day + hours * 3_600 + minutes * 60
    private fun raw(aliveAt: Double, version: Long) = ListeningCut.encode(ListeningCut.Record(aliveAt - 3_600, aliveAt, version))

    @Test
    fun `a reboot with a live record gives a notice about the last alive time`() {
        val notice = ListeningRestart.notice(raw(at(1, 10), 7), now = at(6, 0))
        assertEquals(at(1, 10), notice?.stoppedAt)
        val text = Localization.withLanguage(UILanguage.English) { notice!!.text(ZoneOffset.UTC) }
        assertTrue("1:10" in text && "restarted or Ozen was updated" in text && "Tap to turn them back on" in text, text)
    }

    @Test
    fun `an update with a record from the old version gives a notice, even seconds after the last alive tick`() {
        assertEquals(at(1, 10), ListeningRestart.notice(raw(at(1, 10), 6), now = at(1, 10) + 20)?.stoppedAt)
    }

    @Test
    fun `no record gives nothing`() {
        assertNull(ListeningRestart.notice(null, now = at(6, 0)))
    }

    @Test
    fun `a listening that ended normally left no record, so the store is empty and nothing is said`() {
        assertNull(ListeningRestart.notice("", now = at(6, 0)))
    }

    @Test
    fun `a malformed record gives nothing and does not crash`() {
        for (raw in listOf("garbage", "listening 1", "listening a b 7", "listening NaN NaN 7", "listening -5 -5 7", "listening 9 3 7", "listening 1 2 3 4", "listening 1 2 x")) {
            assertNull(ListeningRestart.notice(raw, now = at(6, 0)), raw)
        }
    }

    @Test
    fun `a last alive time well in the future, from a clock that moved, gives nothing`() {
        assertNull(ListeningRestart.notice(raw(at(9, 0), 7), now = at(6, 0)))
    }

    @Test
    fun `only a boot or an update starts the check`() {
        assertTrue(ListeningRestart.isRestartAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(ListeningRestart.isRestartAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(ListeningRestart.isRestartAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(ListeningRestart.isRestartAction(null))
    }

    @Test
    fun `the title says the sound alerts are off`() {
        assertEquals("Sound alerts are off", Localization.withLanguage(UILanguage.English) { ListeningRestart.Notice(at(1, 10)).title() })
    }
}
