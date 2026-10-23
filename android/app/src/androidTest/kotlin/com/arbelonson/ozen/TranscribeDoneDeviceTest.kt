package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import org.junit.Assert.assertNotEquals
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
}
