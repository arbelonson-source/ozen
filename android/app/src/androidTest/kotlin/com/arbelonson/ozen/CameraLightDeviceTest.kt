package com.arbelonson.ozen

import android.os.SystemClock
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.TorchFlash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class CameraLightDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val availableBefore = CameraLight.isAvailable
    private val switchBefore = CameraLight.switch
    private val watchBefore = CameraLight.watch
    private var lightBefore = false
    private var soundsBefore = SoundAlertPreferences.default
    private val calls = Collections.synchronizedList(mutableListOf<Pair<Long, Boolean>>())

    @Before
    fun remember() {
        lightBefore = app.settings.current.value.flashCameraLight
        soundsBefore = app.settings.current.value.soundAlerts
        app.settings.change {
            it.flashCameraLight = false
            it.soundAlerts = it.soundAlerts.copy(isEnabled = true)
        }
        CameraLight.watch = { _, _, _ -> }
    }

    @After
    fun restore() {
        app.captions.stopCameraLight()
        CameraLight.isAvailable = availableBefore
        CameraLight.switch = switchBefore
        CameraLight.watch = watchBefore
        app.settings.change {
            it.flashCameraLight = lightBefore
            it.soundAlerts = soundsBefore
        }
    }

    private fun fakeLight(available: Boolean, refuses: Boolean = false) {
        CameraLight.isAvailable = { available }
        CameraLight.switch = { _, on ->
            calls += SystemClock.elapsedRealtime() to on
            !(on && refuses)
        }
    }

    private fun openSounds() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Sounds at home").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sound alerts").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun turnOnAndTry() {
        compose.onNodeWithText("Flash the camera light").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.flashCameraLight }
        compose.onNodeWithText("Flash the camera light").assertIsOn()
        compose.onNodeWithText("Try it").performScrollTo().performClick()
    }

    @Test
    fun aPhoneWithoutACameraLightSaysSoAndOffersNothingToTry() {
        fakeLight(available = false)
        openSounds()
        compose.onNodeWithText("This phone has no camera light").performScrollTo()
        assertTrue(compose.onAllNodesWithText("Flash the camera light").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Try it").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("one is enough", substring = true).performScrollTo()
    }

    @Test
    fun tryItBlinksTheLightInTheDoorsRhythmAndEndsDark() {
        fakeLight(available = true)
        openSounds()
        turnOnAndTry()
        val expected = TorchFlash.sample(ReducedMotion.isOn())
        compose.waitUntil(15_000) { synchronized(calls) { calls.count { it.second } == expected.size && calls.size >= expected.size * 2 + 1 } }
        val seen = synchronized(calls) { calls.toList() }
        val onsets = seen.filter { it.second }.map { it.first }
        assertEquals(expected.size, onsets.size)
        val gaps = onsets.zipWithNext { a, b -> b - a }
        val wanted = expected.zipWithNext { a, _ -> ((a.onSeconds + a.offSeconds) * 1_000).toLong() }
        for ((gap, want) in gaps.zip(wanted)) assertTrue("gap $gap, wanted $want", gap in (want - 150)..(want + 400))
        assertEquals(false, seen.last().second)
    }

    @Test
    fun aLightInUseSaysSoPolitely() {
        fakeLight(available = true, refuses = true)
        openSounds()
        turnOnAndTry()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("The camera light is in use right now", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
