package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionAnnouncerTest {
    private fun line(text: String, committed: Boolean = true, speaker: Int? = null) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = committed, speakerClusterID = speaker, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private val names: (TranscriptSegment) -> String? = {
        when (it.speakerClusterID) {
            1 -> "דנה"
            2 -> "יוסי"
            else -> null
        }
    }

    private val nobody: (TranscriptSegment) -> String? = { null }

    @Test
    fun `a finished line is announced once, and a line still changing is not announced yet`() {
        val announcer = CaptionAnnouncer()
        val open = line("שלום מה", committed = false)
        val finished = line("בוקר טוב")
        assertEquals("בוקר טוב", announcer.announcement(listOf(finished, open), nobody))
        assertNull(announcer.announcement(listOf(finished, open), nobody))
        val done = open.copy(text = "שלום מה שלומך", isCommitted = true)
        assertEquals("שלום מה שלומך", announcer.announcement(listOf(finished, done), nobody))
    }

    @Test
    fun `more new lines at once than the few rechecked ones are all read out`() {
        val announcer = CaptionAnnouncer()
        val earlier = listOf(line("one"), line("two"))
        announcer.skipLinesSoFar(earlier)
        val burst = (1..12).map { line("line $it") }
        assertEquals(burst.joinToString("\n") { it.text }, announcer.announcement(earlier + burst, nobody))
    }

    @Test
    fun `a line the engine was unsure of is read out with the warning the screen reader gives it`() {
        val announcer = CaptionAnnouncer()
        val unsure = line("ניפגש בשלוש", speaker = 1)
        val sure = line("טוב, בסדר", speaker = 1)
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                "May not have been heard correctly. דנה: ניפגש בשלוש\nטוב, בסדר",
                announcer.announcement(listOf(unsure, sure), names) { it.id == unsure.id },
            )
        }
    }

    @Test
    fun `a line already read is not read again only because a dropped connection marked it cut off`() {
        val announcer = CaptionAnnouncer()
        val quiet = line("נתראה מחר בבוקר")
        assertEquals("נתראה מחר בבוקר", announcer.announcement(listOf(quiet), nobody))
        assertNull(announcer.announcement(listOf(quiet.copy(text = quiet.text + CaptionStabilizer.CUT_OFF_MARK)), nobody))
        val changed = quiet.copy(text = "נתראה מחר בערב" + CaptionStabilizer.CUT_OFF_MARK)
        assertEquals(changed.text, announcer.announcement(listOf(changed), nobody))
    }

    @Test
    fun `lines finished together go out as one announcement, in order`() {
        assertEquals("אחת\nשתיים\nשלוש", CaptionAnnouncer().announcement(listOf(line("אחת"), line("שתיים"), line("שלוש")), nobody))
    }

    @Test
    fun `the speaker's name leads a line only when the speaker changes`() {
        val announcer = CaptionAnnouncer()
        val first = line("איך את מרגישה?", speaker = 1)
        val second = line("והכדורים?", speaker = 1)
        val third = line("טוב, תודה", speaker = 2)
        assertEquals("דנה: איך את מרגישה?\nוהכדורים?", announcer.announcement(listOf(first, second), names))
        assertEquals("יוסי: טוב, תודה", announcer.announcement(listOf(first, second, third), names))
        assertEquals("יופי", announcer.announcement(listOf(first, second, third, line("יופי", speaker = 2)), names))
    }

    @Test
    fun `after five quiet minutes the same speaker is named again`() {
        val announcer = CaptionAnnouncer()
        val before = TranscriptSegment(id = UUID.randomUUID(), text = "לילה טוב", isCommitted = true, speakerClusterID = 1, startTimestamp = 1000.0, lastUpdateTimestamp = 1002.0)
        val soon = TranscriptSegment(id = UUID.randomUUID(), text = "ונשיקות", isCommitted = true, speakerClusterID = 1, startTimestamp = 1010.0, lastUpdateTimestamp = 1011.0)
        val later = TranscriptSegment(id = UUID.randomUUID(), text = "בוקר טוב", isCommitted = true, speakerClusterID = 1, startTimestamp = 1400.0, lastUpdateTimestamp = 1402.0)
        assertEquals("דנה: לילה טוב", announcer.announcement(listOf(before), names))
        assertEquals("ונשיקות", announcer.announcement(listOf(before, soon), names))
        assertEquals("דנה: בוקר טוב", announcer.announcement(listOf(before, soon, later), names))
    }

    @Test
    fun `blank finished lines are skipped and never announced later`() {
        val announcer = CaptionAnnouncer()
        val blank = line("   ")
        assertNull(announcer.announcement(listOf(blank), nobody))
        assertEquals("כן", announcer.announcement(listOf(blank, line("כן")), nobody))
    }

    @Test
    fun `turning on mid-conversation skips the backlog but not a line still being said`() {
        val announcer = CaptionAnnouncer()
        val old = listOf(line("מזמן"), line("גם מזמן"))
        val open = line("עכשיו", committed = false)
        announcer.skipLinesSoFar(old + open)
        assertNull(announcer.announcement(old + open, nobody))
        assertEquals("עכשיו", announcer.announcement(old + open.copy(isCommitted = true), nobody))
    }

    @Test
    fun `after the screen is cleared the next speaker is named again`() {
        val announcer = CaptionAnnouncer()
        assertEquals("דנה: לפני", announcer.announcement(listOf(line("לפני", speaker = 1)), names))
        assertNull(announcer.announcement(emptyList(), names))
        assertEquals("דנה: אחרי", announcer.announcement(listOf(line("אחרי", speaker = 1)), names))
    }

    @Test
    fun `a line left open while many others finish is still announced when it finishes`() {
        val announcer = CaptionAnnouncer()
        val open = line("והרופא", committed = false)
        val lines = mutableListOf(open)
        assertNull(announcer.announcement(lines, nobody))
        for (n in 1..CaptionAnnouncer.RECHECKED_LINES * 3) {
            lines += line("שורה $n")
            announcer.announcement(lines, nobody)
        }
        lines[0] = open.copy(text = "והרופא אמר", isCommitted = true)
        lines += line("עוד")
        assertEquals("והרופא אמר\nעוד", announcer.announcement(lines, nobody))
    }

    @Test
    fun `a line provisionally committed by the stale-commit safety net is still caught when it reopens and changes, however far back it's scrolled`() {
        val announcer = CaptionAnnouncer()
        val provisional = TranscriptSegment(
            id = UUID.randomUUID(), text = "הרופא אמר", isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0, isProvisionalCommit = true,
        )
        val lines = mutableListOf(provisional)
        assertEquals("הרופא אמר", announcer.announcement(lines, nobody))
        for (n in 1..CaptionAnnouncer.RECHECKED_LINES * 3) {
            lines += line("שורה $n")
            announcer.announcement(lines, nobody)
        }
        lines[0] = provisional.copy(text = "הרופא אמר כדור אחד", isProvisionalCommit = false)
        assertEquals("הרופא אמר כדור אחד", announcer.announcement(lines, nobody))
    }

    @Test
    fun `once enough unrelated lines have replaced it, a rolled-off line is forgotten, so its id reused later is announced fresh`() {
        val announcer = CaptionAnnouncer()
        val stale = line("ישן מאוד")
        assertEquals("ישן מאוד", announcer.announcement(listOf(stale), nobody))
        repeat(2) { announcer.announcement(listOf(line("א"), line("ב")), nobody) }
        assertEquals("ישן מאוד", announcer.announcement(listOf(stale.copy(startTimestamp = 100.0, lastUpdateTimestamp = 100.0)), nobody))
    }

    @Test
    fun `a line corrected after it was read out is read again, and unchanged lines are not`() {
        val announcer = CaptionAnnouncer()
        val said = line("הרופא אמר")
        assertEquals("הרופא אמר", announcer.announcement(listOf(said), nobody))
        assertNull(announcer.announcement(listOf(said), nobody))
        val reopened = said.copy(isCommitted = false, text = "הרופא אמר כדור אחד")
        assertNull(announcer.announcement(listOf(reopened), nobody))
        assertEquals("הרופא אמר כדור אחד", announcer.announcement(listOf(reopened.copy(isCommitted = true)), nobody))
    }
}
