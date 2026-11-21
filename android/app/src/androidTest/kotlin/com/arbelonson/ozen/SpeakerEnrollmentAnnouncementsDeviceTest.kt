package com.arbelonson.ozen

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbelonson.ozen.core.RecordingImport
import com.arbelonson.ozen.core.SavedSpeaker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerEnrollmentAnnouncementsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val readerBefore = ScreenReader.isOn
    private val announceBefore = ScreenReader.announce
    private val microphoneBefore = SpeakerRecordings.microphoneAllowed
    private val announced = CopyOnWriteArrayList<String>()
    private val finish = CompletableDeferred<Boolean>()

    @Volatile
    private var reportProgress: ((Double) -> Unit)? = null

    private val book = object : SpeakerBook {
        override val inputLevel = 0.3f
        override fun needsNewRecording(speaker: SavedSpeaker) = false
        override fun renameSpeaker(name: String, to: String) {}
        override fun removeSpeaker(name: String) {}
        override fun setSpeakerSeparation(threshold: Float) {}
        override suspend fun recordSpeaker(name: String, seconds: Double, onProgress: (Double) -> Unit): Boolean {
            reportProgress = onProgress
            return finish.await()
        }
        override suspend fun addRecordings(recordings: List<Uri>): RecordingImport.Result = throw UnsupportedOperationException()
    }

    private fun voiceScreenSaid() = announced.filter { it == "Recording, speak now" || it.startsWith("Voice saved") }

    private fun keyboardAway() {
        val window = compose.activity.window
        compose.runOnUiThread { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5_000) {
            compose.runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0 }
        }
        compose.waitForIdle()
    }

    @Before
    fun theVoiceScreen() {
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
        SpeakerRecordings.microphoneAllowed = { true }
    }

    @After
    fun putBack() {
        compose.runOnUiThread { SpeakerRecordings.stop() }
        ScreenReader.isOn = readerBefore
        ScreenReader.announce = announceBefore
        SpeakerRecordings.microphoneAllowed = microphoneBefore
    }

    @Test
    fun recordingTellsTalkBackToSpeakOnceAndASavedVoiceIsAnnouncedAsTheScreenCloses() {
        val closed = AtomicBoolean(false)
        var showing by mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                val saved = rememberSaveableStateHolder()
                if (showing) saved.SaveableStateProvider("new speaker") { SpeakerEnrollmentScreen(book, onClose = { closed.set(true) }) }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Dana")
        keyboardAway()
        assertEquals(emptyList<String>(), voiceScreenSaid())
        compose.onNodeWithText("Record and save (30 seconds)").performClick()
        compose.waitUntil(5_000) { "Recording, speak now" in announced && reportProgress != null }
        compose.runOnUiThread { reportProgress?.invoke(0.5) }
        compose.onNodeWithText("Recording… 15/30 seconds").assertExists()
        compose.runOnUiThread { showing = false }
        compose.waitForIdle()
        compose.runOnUiThread { showing = true }
        compose.waitForIdle()
        compose.onNodeWithText("Recording… 15/30 seconds").assertExists()
        assertEquals(listOf("Recording, speak now"), voiceScreenSaid())
        finish.complete(true)
        compose.waitUntil(5_000) { closed.get() }
        compose.waitForIdle()
        assertEquals(listOf("Recording, speak now", "Voice saved: Dana"), voiceScreenSaid())
    }
}
