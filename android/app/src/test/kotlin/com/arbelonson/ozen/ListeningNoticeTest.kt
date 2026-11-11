package com.arbelonson.ozen

import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptionLine
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `closed to one row it keeps the newest words, the line opened stays whole`() {
        val said = "So please take the bus at two and bring the paper from the pharmacy with the drops you use at night"
        val content = LockScreenCaptionContent(listOf(line("The clinic is closed", "Dana"), line(said, "Yossi")))
        val glance = ListeningNotice.glance(content, rowCharacters = 37)
        assertEquals("Yossi: …the drops you use at night", glance)
        assertTrue(glance.length <= 37)
        assertEquals("Dana: The clinic is closed\nYossi: $said", ListeningNotice.opened(content))
    }

    @Test
    fun `a line that fits the row is shown whole`() {
        val content = LockScreenCaptionContent(listOf(line("Fine thanks", "Dana")))
        assertEquals("Dana: Fine thanks", ListeningNotice.glance(content, rowCharacters = 37))
    }

    @Test
    fun `a name that would leave the row too few letters gives way to the words`() {
        val content = LockScreenCaptionContent(listOf(line("We meet again next Tuesday at the clinic downtown", "Speaker 12")))
        assertEquals("…clinic downtown", ListeningNotice.glance(content, rowCharacters = 18))
        assertEquals("Speaker 12: …clinic downtown", ListeningNotice.glance(content, rowCharacters = 28))
    }

    @Test
    fun `the row holds fewer letters as the phone's text gets bigger, measured on its narrow side`() {
        assertEquals(37, ListeningNotice.rowCharacters(shortSideDp = 411, fontScale = 1f))
        assertEquals(28, ListeningNotice.rowCharacters(shortSideDp = 411, fontScale = 1.3f))
        assertEquals(18, ListeningNotice.rowCharacters(shortSideDp = 411, fontScale = 2f))
        assertEquals(31, ListeningNotice.rowCharacters(shortSideDp = 360, fontScale = 1f))
    }

    @Test
    fun `wide letters take two places in the row, so a Chinese line still ends with its newest words`() {
        val said = "今天下午三点我们在医院门口见面然后一起去药店买新的眼药水"
        val content = LockScreenCaptionContent(listOf(line(said, "张伟")))
        assertEquals("张伟: …" + said.takeLast(15), ListeningNotice.glance(content, rowCharacters = 37))
    }

    @Test
    fun `an Amharic line keeps fewer of its wider letters`() {
        val said = "ነገ ጠዋት በሶስት ሰዓት ወደ ሆስፒታሉ እንሄዳለን እና መድሃኒቱን እንገዛለን"
        val glance = ListeningNotice.glance(LockScreenCaptionContent(listOf(line(said))), rowCharacters = 37)
        assertEquals("…እና መድሃኒቱን እንገዛለን", glance)
    }
}
