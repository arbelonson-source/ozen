package com.arbelonson.ozen

import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class HeardWordAnnouncementTest {
    private val line = TranscriptSegment(UUID.randomUUID(), "Doctor", true, speakerClusterID = 1, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
    private val unnamed = line.copy(id = UUID.randomUUID(), speakerClusterID = null)
    private val word = HeardWord(UUID.randomUUID(), "Doctor", 0.0, line = line.id)

    private fun said(on: Boolean, word: HeardWord = this.word, language: UILanguage = UILanguage.English) =
        HeardWordAnnouncement.text(word, listOf(unnamed, line), on, { "Dana" }, language)

    @Test
    fun `the announcement is the pill's own text, with who said it when the names are shown`() {
        assertEquals("Dana said: Doctor", said(on = true))
        assertEquals("דנה — נאמר: Doctor", HeardWordAnnouncement.text(word, listOf(line), true, { "דנה" }, UILanguage.Hebrew))
    }

    @Test
    fun `with no known speaker, or the names hidden, it is only the word`() {
        assertEquals("Said: Doctor", said(on = false))
        assertEquals("Said: Doctor", said(on = true, word = word.copy(line = unnamed.id)))
        assertEquals("Said: Doctor", said(on = true, word = word.copy(line = null)))
        assertEquals("Said: Doctor", said(on = true, word = word.copy(line = UUID.randomUUID())))
    }
}
