package com.arbelonson.ozen

import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptionLine
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListeningNoticeTest {
    private fun line(text: String, speaker: String? = null, same: Boolean = false) =
        LockScreenCaptionLine(speaker = speaker, text = text, isFinal = true, sameSpeakerAsAbove = same)

    @Test
    fun `closed it shows the newest line with who said it, and opened both lines`() {
        val content = LockScreenCaptionContent(listOf(line("How did you sleep", "Dana"), line("Fine thanks", "Speaker 2")))
        assertEquals("Speaker 2: Fine thanks", ListeningNotice.glance(content))
        assertEquals("Dana: How did you sleep\nSpeaker 2: Fine thanks", ListeningNotice.opened(content))
    }

    @Test
    fun `the newest line shown alone takes back the name it shares with the line above`() {
        val content = LockScreenCaptionContent(listOf(line("Good morning", "Dana"), line("How did you sleep", same = true)))
        assertEquals("Dana: How did you sleep", ListeningNotice.glance(content))
        assertEquals("Dana: Good morning\nHow did you sleep", ListeningNotice.opened(content))
    }

    @Test
    fun `a line nobody was recognised on keeps no name`() {
        val unnamed = LockScreenCaptionContent(listOf(line("Good morning", "Dana"), line("Who is there")))
        assertEquals("Who is there", ListeningNotice.glance(unnamed))
    }

    @Test
    fun `one line alone opens whole too, so the end a single row cuts off can still be read`() {
        val said = "and the newest words of a long first sentence are at its end"
        val one = LockScreenCaptionContent(listOf(line(said, "Dana")))
        assertEquals("Dana: $said", ListeningNotice.glance(one))
        assertEquals("Dana: $said", ListeningNotice.opened(one))
    }

    @Test
    fun `with no lines to show it says where captions will appear`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Captions will appear here.", ListeningNotice.glance(LockScreenCaptionContent(emptyList())))
        }
        assertNull(ListeningNotice.opened(LockScreenCaptionContent(emptyList())))
    }
}
