package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedLineSpeechTest {
    private fun line(text: String, speaker: String?, starred: Boolean = false) = SavedSegment(
        id = UUID.randomUUID(), text = text, speakerName = speaker, speakerClusterID = null,
        startTimestamp = 0.0, isCommitted = true, isStarred = starred,
    )

    @Test
    fun `the time shown above a line is read with it`() {
        assertEquals("21:04. דנה: בוקר טוב", SavedLineSpeech.label(line("בוקר טוב", "דנה"), time = "21:04", uncertain = false))
        assertEquals("דנה: בוקר טוב", SavedLineSpeech.label(line("בוקר טוב", "דנה"), time = null, uncertain = false))
    }

    @Test
    fun `an unrecognised voice is not read out as unknown speaker on every line, in any language it was saved in`() {
        for (language in UILanguage.entries) {
            val unknown = tr("דובר לא ידוע", "Unknown speaker", language)
            assertEquals("כן", SavedLineSpeech.label(line("כן", unknown), time = null, uncertain = false))
        }
        assertEquals("דובר 2: כן", SavedLineSpeech.label(line("כן", "דובר 2"), time = null, uncertain = false))
        assertEquals("כן", SavedLineSpeech.label(line("כן", ""), time = null, uncertain = false))
    }

    @Test
    fun `star and doubt come before the words, after the time`() {
        val label = SavedLineSpeech.label(line("כדור אחד", null, starred = true), time = "9:30", uncertain = true)
        assertEquals("9:30. מסומן כחשוב. ייתכן שלא נשמע נכון. כדור אחד", label)
    }
}
