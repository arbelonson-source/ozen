package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PipelinePhase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun clear() {
        compose.runOnUiThread { CaptionState.clear() }
    }

    private fun show(phase: PipelinePhase) = compose.runOnUiThread {
        CaptionState.show(phase, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), listeningStartedAt = System.currentTimeMillis() / 1_000.0)
    }

    private fun screenKeptOn(): Boolean {
        val dump = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window windows")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        val window = dump.substringAfter("com.arbelonson.ozen/com.arbelonson.ozen.MainActivity}:", "").substringBefore("Window #")
        return window.lineSequence().any { it.trim().startsWith("fl=") && "KEEP_SCREEN_ON" in it }
    }

    @Test
    fun theScreenStaysOnWhileCaptionsListenAndMayTurnOffOncePaused() {
        show(PipelinePhase.Listening)
        compose.waitUntil(5_000) { screenKeptOn() }
        show(PipelinePhase.Paused)
        compose.waitUntil(5_000) { !screenKeptOn() }
    }
}
