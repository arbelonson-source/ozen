package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.RecordingImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPatternsDeviceTest {
    @Test
    fun aCloudTranscriptSqueezesAnyRunOfSpacesOnThePhone() {
        assertEquals(listOf("שלום לכולם"), CloudSpeech.turns("A: שלום\u00A0\u2003לכולם"))
    }

    @Test
    fun aSpeakerLabelWithANoBreakSpaceIsStillALabelOnThePhone() {
        assertEquals(listOf("כן", "לא"), CloudSpeech.turns("Speaker\u00A01: כן\nSpeaker\u00A02: לא"))
    }

    @Test
    fun phoneNumbersAreFoundAsOnTheJvm() {
        assertEquals(listOf("050-1234567"), PhoneNumbers.matches("התקשרו 050-1234567").map { it.textIn("התקשרו 050-1234567") })
        assertTrue(PhoneNumbers.matches("\u06630501234567").isEmpty())
    }

    @Test
    fun starCodesAreKeptWholeAsOnTheJvm() {
        assertEquals("חייגו \u2066*2700\u2069 עכשיו", CaptionLayout.copiedText("חייגו *2700 עכשיו"))
        assertEquals("\u2066*2700\u0663\u2069", CaptionLayout.copiedText("*2700\u0663"))
    }

    @Test
    fun aRecordingsNumberComesOffItsFileNameAsOnTheJvm() {
        assertEquals("Dana", RecordingImport.personName("Dana \u0662.m4a"))
    }
}
