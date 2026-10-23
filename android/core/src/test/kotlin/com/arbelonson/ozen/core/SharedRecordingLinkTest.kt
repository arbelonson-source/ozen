package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedRecordingLinkTest {
    private val own = "com.arbelonson.ozen"

    private fun ok(link: String) = SharedRecordingLink.isAcceptable(link, own)

    @Test
    fun aLinkToOzensOwnFilesIsRefused() {
        assertFalse(ok("file:///data/data/com.arbelonson.ozen/files/ozen-settings.json"))
        assertFalse(ok("content://com.arbelonson.ozen.share/x"))
        assertFalse(ok("content://com.arbelonson.ozen.debug.share/x"))
        assertFalse(ok("content://COM.ARBELONSON.OZEN.share/x"))
    }

    @Test
    fun aVoiceNoteFromAnotherAppIsAccepted() {
        assertTrue(ok("content://com.whatsapp.provider.media/item/123"))
    }

    @Test
    fun nothingWithoutASchemeOrAnAuthorityIsAccepted() {
        assertFalse(ok("/data/data/com.arbelonson.ozen/files/x"))
        assertFalse(ok("content:///x"))
        assertFalse(ok("not a link"))
    }
}
