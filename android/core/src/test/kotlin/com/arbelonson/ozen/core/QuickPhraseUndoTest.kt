package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickPhraseUndoTest {
    @Test
    fun `without a screen reader Undo goes after a few seconds`() {
        assertEquals(8_000L, QuickPhraseUndo.keepMillis(screenReaderOn = false))
    }

    @Test
    fun `with a screen reader Undo stays until the next edit or the screen closes`() {
        assertNull(QuickPhraseUndo.keepMillis(screenReaderOn = true))
    }

    @Test
    fun `with a screen reader the message says that Undo is available`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Deleted: Thanks. Undo is available until you leave this screen.", QuickPhraseUndo.deletedMessage("Thanks", screenReaderOn = true))
    }

    @Test
    fun `without a screen reader the message stays short`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Deleted: Thanks", QuickPhraseUndo.deletedMessage("Thanks", screenReaderOn = false))
    }
}
