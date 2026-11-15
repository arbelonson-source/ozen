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
}
