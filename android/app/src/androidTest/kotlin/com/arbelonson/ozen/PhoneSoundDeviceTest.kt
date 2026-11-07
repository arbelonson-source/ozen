package com.arbelonson.ozen

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.PipelinePhase
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSoundDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication

    @After
    fun stopCaptions() {
        compose.runOnUiThread { app.captions.pipeline.stop() }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun within(millis: Long, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    private fun toneForTwoSeconds() {
        val rate = 16_000
        val samples = ShortArray(rate * 2) { (sin(2 * PI * 1_000 * it / rate) * 3_000).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
    }

    @Test
    fun aToneThePhonePlaysRaisesTheInputLevelAndTheProjectionStoppingBringsTheMicrophoneBack() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        shell("appops set ${app.packageName} PROJECT_MEDIA allow")
        val savedBefore = app.settings.current.value.preferredInputUID
        compose.runOnUiThread { app.captions.listen() }
        assertTrue("captions never listened: ${app.captions.pipeline.phase}", within(90_000) { app.captions.pipeline.phase == PipelinePhase.Listening })
        val microphone = app.captions.pipeline.selectedInputUID
        assertNotEquals(PhoneSound.UID, microphone)
        Thread.sleep(1_000)
        val roomTone = app.captions.pipeline.inputLevel

        compose.onNodeWithContentDescription("Choose microphone").performClick()
        compose.onNode(hasText("Sound playing on this phone") and isSelectable()).performScrollTo().performClick()
        assertTrue(
            "phone sound was not chosen; if the system's sharing dialog is showing, the appops route did not skip it on this image",
            within(15_000) { app.captions.pipeline.selectedInputUID == PhoneSound.UID },
        )
        assertEquals(savedBefore, app.settings.current.value.preferredInputUID)

        var loudest = 0f
        Thread { toneForTwoSeconds() }.start()
        val until = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < until) {
            loudest = maxOf(loudest, app.captions.pipeline.inputLevel)
            Thread.sleep(50)
        }
        assertTrue("level $loudest never rose above the room tone $roomTone", loudest > roomTone + 0.05f)

        compose.runOnUiThread { (app.captions.audio as AndroidAudioCapture).projectionEnded() }
        assertTrue("the microphone did not come back", within(10_000) { app.captions.pipeline.selectedInputUID == microphone })
        assertEquals(savedBefore, app.settings.current.value.preferredInputUID)
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Stopped listening to the phone's sound. Back to the microphone.").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
