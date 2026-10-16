package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionProgressTest {
    @Test
    fun fractionIsHowMuchOfTheRecordingHasBeenHeard() {
        assertEquals(0.25f, TranscriptionProgress.fraction(30.0, 120.0))
        assertEquals(0f, TranscriptionProgress.fraction(0.0, 120.0))
    }

    @Test
    fun fractionNeverLeavesZeroToOne() {
        assertEquals(1f, TranscriptionProgress.fraction(130.0, 120.0))
        assertEquals(0f, TranscriptionProgress.fraction(-3.0, 120.0))
    }

    @Test
    fun fractionIsUnknownWhenTheLengthIsNot() {
        assertNull(TranscriptionProgress.fraction(30.0, null))
        assertNull(TranscriptionProgress.fraction(30.0, 0.0))
    }

    @Test
    fun percentRoundsDownSoItNeverSaysDoneEarly() {
        assertEquals(0, TranscriptionProgress.percent(0.004f))
        assertEquals(99, TranscriptionProgress.percent(0.9999f))
        assertEquals(100, TranscriptionProgress.percent(1f))
        assertEquals(42, TranscriptionProgress.percent(0.42f))
    }

    @Test
    fun timeLeftScalesTheTimeSoFarByWhatIsStillToCome() {
        assertEquals(300.0, TranscriptionProgress.secondsLeft(elapsedSeconds = 100.0, doneSeconds = 60.0, totalSeconds = 240.0)!!, 1e-9)
    }

    @Test
    fun timeLeftIsUnknownUntilThereIsSomethingToGoOn() {
        assertNull(TranscriptionProgress.secondsLeft(elapsedSeconds = 2.0, doneSeconds = 30.0, totalSeconds = 240.0))
        assertNull(TranscriptionProgress.secondsLeft(elapsedSeconds = 30.0, doneSeconds = 0.5, totalSeconds = 240.0))
        assertNull(TranscriptionProgress.secondsLeft(elapsedSeconds = 30.0, doneSeconds = 30.0, totalSeconds = null))
    }

    @Test
    fun timeLeftIsNothingOnceAllIsHeard() {
        assertEquals(0.0, TranscriptionProgress.secondsLeft(elapsedSeconds = 50.0, doneSeconds = 240.0, totalSeconds = 240.0)!!, 1e-9)
    }

    @Test
    fun timeLeftIsSaidRoughly() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Less than a minute left", TranscriptionProgress.timeLeftText(40.0))
            assertEquals("About 1 min left", TranscriptionProgress.timeLeftText(61.0))
            assertEquals("About 7 min left", TranscriptionProgress.timeLeftText(7 * 60.0 + 10.0))
            assertEquals("About 1 h 5 min left", TranscriptionProgress.timeLeftText(3_900.0))
            assertEquals("About 2 h left", TranscriptionProgress.timeLeftText(7_200.0))
        }
    }

    @Test
    fun timeLeftIsSaidInHebrewToo() {
        Localization.withLanguage(UILanguage.Hebrew) {
            assertTrue(TranscriptionProgress.timeLeftText(7 * 60.0).contains("7"))
        }
    }
}
