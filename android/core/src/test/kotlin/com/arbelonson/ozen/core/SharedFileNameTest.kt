package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedFileNameTest {
    private val at = 1_700_000_000.0

    private fun name(title: String?, at: Double = this.at, offset: Int = 0, kind: SharedKind = SharedKind.Conversation) =
        TranscriptHistoryStore.sharedFileName(kind, title, at, offset)

    @Test
    fun `a conversation file is named by its title and the minute it started`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Visit to the doctor 2023-11-14 22-13-20.txt", name("Visit to the doctor"))
        }
    }

    @Test
    fun `without a title it says what it is and when`() {
        Localization.withLanguage(UILanguage.Hebrew) {
            assertEquals("שיחה מתוך אוזן 2023-11-14 22-13-20.txt", name(null))
            assertEquals("שיחה מתוך אוזן 2023-11-14 22-13-20.txt", name("   "))
        }
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Conversation from Ozen 2023-11-14 22-13-20.txt", name(null))
        }
    }

    @Test
    fun `the time is the phone's own`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Conversation from Ozen 2023-11-15 01-13-20.txt", name(null, offset = 10_800))
        }
    }

    @Test
    fun `two conversations with the same title get different names`() {
        assertNotEquals(name("Clinic"), name("Clinic", at = at + 5))
    }

    @Test
    fun `characters a file system forbids are replaced`() {
        val made = name("a/b\\c:d*e?f\"g<h>i|j\nk")
        assertTrue(made.none { it in "/\\:*?\"<>|\n" }, made)
        assertTrue(made.endsWith(" 2023-11-14 22-13-20.txt"), made)
    }

    @Test
    fun `a very long title is cut but the time stays`() {
        val made = name("x".repeat(500))
        assertTrue(made.length <= 120, made)
        assertTrue(made.endsWith(" 2023-11-14 22-13-20.txt"), made)
    }

    @Test
    fun `a title of three-byte characters stays under the file system's byte limit`() {
        val made = name("语".repeat(200))
        assertTrue(made.toByteArray(Charsets.UTF_8).size <= 200, made)
        assertTrue(made.endsWith(" 2023-11-14 22-13-20.txt"), made)
    }

    @Test
    fun `starred lines have their own name`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Starred lines from Ozen 2023-11-14 22-13-20.txt", name(null, kind = SharedKind.Starred))
        }
    }

    private val stamp = " 2023-11-14 22-13-20.txt"

    @Test
    fun `a right-to-left override inside a title is dropped`() {
        assertEquals("eviltxt.exe$stamp", name("evil\u202Etxt.exe"))
    }

    @Test
    fun `direction marks, separators and zero-width characters are dropped`() {
        val hidden = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200E\u200F\u061C\u2028\u2029\u200B\u200C\u200D\uFEFF"
        assertEquals("ab$stamp", name("a${hidden}b"))
    }

    @Test
    fun `a title of only invisible characters gets the readable name`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Conversation from Ozen$stamp", name("\u200B\u200D\uFEFF\u202E \u2028"))
        }
    }

    @Test
    fun `hebrew letters with their marks are kept`() {
        val title = "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD"
        assertEquals("$title$stamp", name(title))
    }

    @Test
    fun `a long title with niqqud is cut between whole letters`() {
        val unit = "\u05D1\u05BC\u05B0"
        val made = name(unit.repeat(20))
        assertEquals(unit.repeat(13) + stamp, made)
        assertTrue(made.substringBefore(stamp).toByteArray(Charsets.UTF_8).size <= 80, made)
    }

    @Test
    fun `an emoji family at the cut is not split`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        assertEquals("a".repeat(60) + stamp, name("a".repeat(60) + family))
        assertEquals("a".repeat(50) + family + stamp, name("a".repeat(50) + family))
    }
}
