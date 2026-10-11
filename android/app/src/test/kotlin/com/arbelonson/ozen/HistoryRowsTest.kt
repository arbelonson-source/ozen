package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.StarredLine
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRowsTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private val morning = 1_800_000_000.0

    private fun summary(names: List<String> = emptyList(), title: String? = null) = TranscriptSessionSummary(
        id = UUID.randomUUID(),
        startedAt = morning,
        endedAt = morning + 25 * 60,
        segmentCount = 12,
        preview = "Good morning",
        engine = TranscriptionEngineKind.WhisperKit,
        speakerNames = names,
        title = title,
    )

    private fun line(text: String, at: Double, speaker: String? = null) =
        SavedSegment(UUID.randomUUID(), text, speaker, null, at, true)

    @Test
    fun `a conversation row shows its start in the phone's time zone, how long it ran, and how many lines`() = Localization.withLanguage(UILanguage.English) {
        val row = ConversationRow.of(summary(), jerusalem)
        assertTrue(row.time.contains("10:00"), row.time)
        assertEquals("25 minutes", row.duration)
        assertEquals("12 lines", row.lines)
        assertEquals("Good morning", row.preview)
        assertNull(row.title)
        assertNull(row.speakers)
    }

    @Test
    fun `up to three names are listed, then and more`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Dana, Yossi", ConversationRow.of(summary(listOf("Dana", "Yossi")), jerusalem).speakers)
        assertEquals(
            "Dana, Yossi, Ronit and more",
            ConversationRow.of(summary(listOf("Dana", "Yossi", "Ronit", "Avi")), jerusalem).speakers,
        )
    }

    @Test
    fun `the saving footer says where conversations are kept, without the iPhone's backups`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(
            "Conversations are saved on this phone (2 MB). They can be deleted anytime.",
            HistoryTexts.savingFooter(saving = true, size = "2 MB", retention = HistoryRetention.Forever),
        )
        assertEquals(
            "New conversations are not being saved. Those saved before stay on this phone (2 MB). They can be deleted anytime." +
                " Conversations with a starred line or a name are always kept.",
            HistoryTexts.savingFooter(saving = false, size = "2 MB", retention = HistoryRetention.Week),
        )
    }

    @Test
    fun `a shorter keeping time says how many old conversations go now`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("1 old conversation will be deleted now", HistoryTexts.expiryWarning(1))
        assertEquals("2 old conversations will be deleted now", HistoryTexts.expiryWarning(2))
        assertEquals("5 old conversations will be deleted now", HistoryTexts.expiryWarning(5))
        assertEquals(
            listOf("Never", "After a year", "After 3 months", "After a month", "After a week"),
            HistoryRetention.entries.map(HistoryTexts::name),
        )
    }

    @Test
    fun `an empty list says whether nothing matched the search, nothing is saved yet, or saving is off`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Nothing found for “dentist”.", HistoryTexts.emptyMessage(query = "dentist", saving = true))
        assertEquals("No saved conversations yet.", HistoryTexts.emptyMessage(query = "  ", saving = true))
        assertEquals("Saving conversations is off.", HistoryTexts.emptyMessage(query = "", saving = false))
    }

    @Test
    fun `an empty list under a chosen name says nobody by that name was found, unless a search said nothing matched first`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("No conversations with Grandma.", HistoryTexts.emptyMessage(query = "", saving = true, speakerFilter = "Grandma"))
        assertEquals("No conversations with Grandma.", HistoryTexts.emptyMessage(query = " ", saving = false, speakerFilter = "Grandma"))
        assertEquals("Nothing found for “dentist”.", HistoryTexts.emptyMessage(query = "dentist", saving = true, speakerFilter = "Grandma"))
        assertEquals("No saved conversations yet.", HistoryTexts.emptyMessage(query = "", saving = true, speakerFilter = null))
    }

    @Test
    fun `clearing the captions warns they will not be saved only while History is off or saving is failing`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("History is off, so they won't be saved.", HistoryTexts.clearWarning(saving = false, failing = false))
        assertEquals("History is off, so they won't be saved.", HistoryTexts.clearWarning(saving = false, failing = true))
        assertEquals("Saving to History isn't working right now, so they won't be saved.", HistoryTexts.clearWarning(saving = true, failing = true))
        assertNull(HistoryTexts.clearWarning(saving = true, failing = false))
    }

    @Test
    fun `the next match says which of how many, and keeps its place when the conversation grows`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Next match (2 of 5)", SearchMatches.nextTitle(current = 1, count = 5))
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertEquals(2, SearchMatches.position(1, old = listOf(first, second), new = listOf(UUID.randomUUID(), first, second)))
        assertNull(SearchMatches.position(1, old = listOf(first, second), new = listOf(first)))
    }

    @Test
    fun `with no search the button leads to the starred lines, then says which of how many`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("To starred lines (3)", SearchMatches.starredTitle(current = 0, count = 3, jumped = false))
        assertEquals("Next starred (1 of 3)", SearchMatches.starredTitle(current = 0, count = 3, jumped = true))
        assertEquals("Next starred (3 of 3)", SearchMatches.starredTitle(current = 2, count = 3, jumped = true))
    }

    @Test
    fun `numbers mentioned lists the first twelve lines with a time, an amount or a phone number, and counts the rest`() = Localization.withLanguage(UILanguage.English) {
        val numbered = (1..14).map { line("Take $it pills at 8:00", at = morning + it) }
        val record = TranscriptSessionRecord(
            startedAt = morning, endedAt = null, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
            segments = listOf(line("Good morning", at = morning)) + numbered + line("See you then", at = morning + 20),
        )
        val mentioned = NumbersMentioned.of(record)
        assertEquals(numbered.take(12).map { it.id }, mentioned.listed.map { it.id })
        assertEquals("Plus 2 lines with numbers later in the conversation", mentioned.moreText)
    }

    @Test
    fun `a conversation without numbers lists none and says nothing more`() {
        val record = TranscriptSessionRecord(
            startedAt = morning, endedAt = null, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
            segments = listOf(line("Good morning", at = morning), line("See you then", at = morning + 20)),
        )
        val mentioned = NumbersMentioned.of(record)
        assertTrue(mentioned.listed.isEmpty())
        assertNull(mentioned.moreText)
    }

    @Test
    fun `a named conversation keeps its name`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Doctor's visit", ConversationRow.of(summary(title = "Doctor's visit"), jerusalem).title)
    }

    @Test
    fun `saved lines show the time every five minutes, and the speaker where their turn starts or after five quiet minutes`() = Localization.withLanguage(UILanguage.English) {
        val record = TranscriptSessionRecord(
            startedAt = morning,
            endedAt = morning + 600,
            engine = TranscriptionEngineKind.WhisperKit,
            modelVariant = null,
            inputName = null,
            segments = listOf(
                line("Hello", morning, "Dana"),
                line("How are you", morning + 10, "Dana"),
                line("Fine", morning + 20, "Yossi"),
                line("Later on", morning + 400, "Yossi"),
            ),
        )
        val lines = SavedLine.of(record, jerusalem)
        assertEquals(listOf(true, false, false, true), lines.map { it.time != null })
        assertTrue(lines[0].time!!.contains("10:00"), lines[0].time)
        assertEquals(listOf("Dana", null, "Yossi", "Yossi"), lines.map { it.speaker })
        assertEquals(listOf("Hello", "How are you", "Fine", "Later on"), lines.map { it.text })
        assertTrue(lines[2].spoken.startsWith("Yossi: Fine"), lines[2].spoken)
        assertTrue(lines[3].spoken.startsWith(lines[3].time!!), lines[3].spoken)
    }

    @Test
    fun `a saved line that may have been misheard is marked and says so while the marks are on, and a starred one says it is important`() = Localization.withLanguage(UILanguage.English) {
        val record = TranscriptSessionRecord(
            startedAt = morning,
            endedAt = morning + 60,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), "Two pills after dinner", null, null, morning, true, confidence = 0.05f),
                SavedSegment(UUID.randomUUID(), "Call the pharmacy", null, null, morning + 10, true, isStarred = true, confidence = 0.95f),
            ),
        )
        val marked = SavedLine.of(record, jerusalem, markUncertain = true)
        assertEquals(listOf(true, false), marked.map { it.unsure })
        assertEquals(listOf(false, true), marked.map { it.starred })
        assertTrue(marked[0].spoken.contains("May not have been heard correctly. Two pills after dinner"), marked[0].spoken)
        assertTrue(marked[1].spoken.contains("Marked as important. Call the pharmacy"), marked[1].spoken)

        val unmarked = SavedLine.of(record, jerusalem, markUncertain = false)
        assertEquals(listOf(false, false), unmarked.map { it.unsure })
        assertFalse(unmarked[0].spoken.contains("May not have been heard"), unmarked[0].spoken)
        assertEquals(listOf(false, true), unmarked.map { it.starred })
    }

    @Test
    fun `a saved line copies as said, its phone number kept in order for a Hebrew chat`() {
        val record = TranscriptSessionRecord(
            startedAt = morning,
            endedAt = morning + 60,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(line("תתקשרי למרפאה 050 123 4567", morning)),
        )
        val copied = SavedLine.of(record, jerusalem).single().copied
        assertEquals(CaptionLayout.copiedText("תתקשרי למרפאה 050 123 4567"), copied)
        assertTrue(copied.contains("050 123 4567"), copied)
        assertTrue(copied != "תתקשרי למרפאה 050 123 4567", copied)
        assertEquals(listOf("0501234567"), SavedLine.of(record, jerusalem).single().phones.map { it.dialable })
    }

    @Test
    fun `a starred line always shows its time and a real speaker's name, and is read without saying it is starred`() = Localization.withLanguage(UILanguage.English) {
        val at = morning + 90
        val pills = SavedSegment(UUID.randomUUID(), "Two pills after dinner", "Dana", null, at, true, isStarred = true, confidence = 0.05f)
        val row = SavedLine.of(StarredLine(UUID.randomUUID(), morning, pills, engine = TranscriptionEngineKind.HomeServer, model = "home server"), jerusalem, markUncertain = true)
        val time = clockTime(at, jerusalem)
        assertEquals(time, row.time)
        assertEquals("Dana", row.speaker)
        assertTrue(row.unsure)
        assertEquals("$time. May not have been heard correctly. Dana: Two pills after dinner", row.spoken)
        assertEquals(CaptionLayout.copiedText("Two pills after dinner"), row.copied)

        val unnamed = SavedSegment(UUID.randomUUID(), "Call the pharmacy", "Speaker 2", null, at, true, isStarred = true, confidence = 0.05f)
        val plain = SavedLine.of(StarredLine(UUID.randomUUID(), morning, unnamed, engine = TranscriptionEngineKind.HomeServer, model = "home server"), jerusalem, markUncertain = false)
        assertNull(plain.speaker)
        assertFalse(plain.unsure)
        assertEquals("$time. Call the pharmacy", plain.spoken)
    }

    @Test
    fun `the time above talk that picked up after a break is the clock time, with the day when the words before it were on another day`() = Localization.withLanguage(UILanguage.English) {
        val later = morning + 10 * 60
        assertEquals(clockTime(later, jerusalem), resumedTime(QuietGap(lastWordsAt = morning, resumedAt = later), jerusalem))
        val tomorrow = morning + 24 * 60 * 60
        val text = resumedTime(QuietGap(lastWordsAt = morning, resumedAt = tomorrow), jerusalem)
        assertTrue(text.contains("Jan") && text.contains("16") && text.contains(clockTime(tomorrow, jerusalem)), text)
    }
}
