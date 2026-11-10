package com.arbelonson.ozen

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptFormats
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribeDoneDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication

    private fun noteAfter(history: HistorySave) {
        val model = FileModelChoice.toDownload(UILanguage.English)
        val done = FileTranscription.Done("Lecture.wav", UILanguage.English, model, listOf(TranscribedLine(0.0, 2.0, "Good morning")), history)
        compose.runOnUiThread { compose.activity.setContent { TranscribeScreen(app.transcriber, done, onClose = {}) } }
        compose.onNodeWithTag("transcribe-saved").assertIsDisplayed().assertTextEquals(history.message)
    }

    @Test
    fun aRecordingThatWasSavedSaysSoOnTheDonePage() = noteAfter(HistorySave.Saved(UUID.randomUUID()))

    @Test
    fun savingBeingOffIsSaidOnTheDonePage() = noteAfter(HistorySave.Off)

    @Test
    fun aSaveThePhoneRefusedIsShownOnTheDonePageAndNotCalledSavingOff() {
        assertNotEquals(HistorySave.Off.message, HistorySave.Failed.message)
        noteAfter(HistorySave.Failed)
    }

    private fun leaveAfter(history: HistorySave): AtomicInteger {
        val closed = AtomicInteger()
        val model = FileModelChoice.toDownload(UILanguage.English)
        val done = FileTranscription.Done("Lecture.wav", UILanguage.English, model, listOf(TranscribedLine(0.0, 2.0, "Good morning")), history)
        compose.runOnUiThread { compose.activity.setContent { TranscribeScreen(app.transcriber, done, onClose = { closed.incrementAndGet() }) } }
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        return closed
    }

    @Test
    fun textThatHistoryDoesNotHoldAsksBeforeDoneOrBackThrowsItAway() {
        for (history in listOf(HistorySave.Off, HistorySave.Failed)) {
            val closed = leaveAfter(history)
            compose.onNodeWithText("Close without sharing the text?").assertExists()
            compose.onNodeWithText("The text isn’t saved anywhere else, so it will be lost.").assertExists()
            assertEquals(0, closed.get())
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Close without sharing the text?").assertDoesNotExist()
            compose.onNodeWithTag("transcribe-name").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertEquals(0, closed.get())
            compose.onNodeWithText("Close").performClick()
            compose.waitForIdle()
            assertEquals(1, closed.get())
        }
    }

    @Test
    fun textSavedInHistoryClosesOnDoneWithoutAsking() {
        val closed = leaveAfter(HistorySave.Saved(UUID.randomUUID()))
        compose.onNodeWithText("Close without sharing the text?").assertDoesNotExist()
        assertEquals(1, closed.get())
    }

    @Test
    fun eachLineIsOneScreenReaderStopWithItsTimeAndItsWords() {
        val model = FileModelChoice.toDownload(UILanguage.English)
        val done = FileTranscription.Done("Lecture.wav", UILanguage.English, model, listOf(TranscribedLine(65.0, 67.0, "Good morning")), HistorySave.Off)
        compose.runOnUiThread { compose.activity.setContent { TranscribeScreen(app.transcriber, done, onClose = {}) } }
        compose.onNodeWithTag(TRANSCRIBE_LINE).assert(hasText("1:05") and hasText("Good morning", substring = true))
    }

    private fun shareOnAFullPhone(button: String, lines: List<TranscribedLine>) {
        val model = FileModelChoice.toDownload(UILanguage.English)
        val done = FileTranscription.Done("Lecture.wav", UILanguage.English, model, lines, HistorySave.Failed)
        compose.runOnUiThread { compose.activity.setContent { TranscribeScreen(app.transcriber, done, onClose = {}) } }
        compose.onNodeWithTag("transcribe-name").assertIsDisplayed()
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                started += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // A plain file where the shared folder goes makes every file write
        // fail, as a full phone does.
        val blocked = File(app.cacheDir, CaptionSession.SHARED_FOLDER)
        blocked.deleteRecursively()
        assertTrue(blocked.createNewFile())
        instrumentation.addMonitor(sheet)
        try {
            compose.onNodeWithTag(button).performClick()
            val until = System.currentTimeMillis() + 2_000
            compose.waitUntil(5_000) { System.currentTimeMillis() > until }
            assertEquals(emptyList<Intent>(), started.toList())
            compose.onNodeWithTag("transcribe-name").assertIsDisplayed().assertTextEquals("Lecture.wav")
            compose.onNodeWithTag("transcribe-saved").assertIsDisplayed().assertTextEquals(HistorySave.Failed.message)
            compose.onAllNodesWithTag(TRANSCRIBE_LINE).onFirst().assertIsDisplayed().assert(hasText(lines.first().text, substring = true))
        } finally {
            instrumentation.removeMonitor(sheet)
            blocked.delete()
        }
    }

    @Test
    fun subtitlesSharedOnAFullPhoneLeaveTheUnsavedTextOnScreenInsteadOfClosingTheApp() =
        shareOnAFullPhone(TRANSCRIBE_SUBTITLES, listOf(TranscribedLine(0.0, 2.0, "Good morning")))

    @Test
    fun aLongTranscriptSharedAsTextOnAFullPhoneLeavesItOnScreenInsteadOfClosingTheApp() {
        val lines = (0 until 1_000).map { TranscribedLine(it * 3.0, it * 3.0 + 2.0, "Line $it of a long lecture about the history of medicine and the pharmacy") }
        assertTrue(TranscriptFormats.text(lines).length > 60_000)
        shareOnAFullPhone(TRANSCRIBE_SHARE, lines)
    }
}
