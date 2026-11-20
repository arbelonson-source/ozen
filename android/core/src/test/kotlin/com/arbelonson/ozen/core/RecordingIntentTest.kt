package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingIntentTest {
    private val own = "com.arbelonson.ozen"
    private val note = "content://com.example.files/voice/1"

    private fun pick(action: String?, type: String?, data: String? = null, stream: String? = null) =
        RecordingIntent.source(action, type, data, stream, own)

    @Test
    fun aSharedVoiceNoteIsReadFromTheStream() {
        assertEquals(note, pick(RecordingIntent.SEND, "audio/ogg", stream = note))
    }

    @Test
    fun aRecordingOpenedWithOzenIsReadFromTheIntentData() {
        assertEquals(note, pick(RecordingIntent.VIEW, "audio/mp4", data = note))
        assertEquals(note, pick(RecordingIntent.VIEW, "video/mp4", data = note))
    }

    @Test
    fun anOpenedRecordingIgnoresAStreamExtra() {
        assertNull(pick(RecordingIntent.VIEW, "audio/mp4", stream = note))
        assertNull(pick(RecordingIntent.SEND, "audio/mp4", data = note))
    }

    @Test
    fun anOpenedFilePathIsNeverRead() {
        assertNull(pick(RecordingIntent.VIEW, "audio/mp4", data = "file:///sdcard/Download/a.m4a"))
    }

    @Test
    fun anOpenedFileOfOzensOwnIsRefused() {
        assertNull(pick(RecordingIntent.VIEW, "audio/mp4", data = "content://com.arbelonson.ozen.share/x"))
    }

    @Test
    fun otherTypesAndActionsGiveNothing() {
        assertNull(pick(RecordingIntent.VIEW, "text/plain", data = note))
        assertNull(pick(RecordingIntent.VIEW, null, data = note))
        assertNull(pick("android.intent.action.MAIN", "audio/mp4", data = note))
        assertNull(pick(RecordingIntent.VIEW, "audio/mp4", data = null))
    }
}
