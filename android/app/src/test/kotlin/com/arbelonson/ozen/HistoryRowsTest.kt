package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
