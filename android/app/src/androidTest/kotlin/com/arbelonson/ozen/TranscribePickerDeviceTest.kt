package com.arbelonson.ozen

import androidx.activity.compose.setContent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribePickerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clip = File(context.cacheDir, "picker-test.wav")

    private fun openPicker(fontScale: Float) {
        DeviceClips.writeWav(clip, FloatArray(16_000 * 2))
        app.transcriber.close()
        assertTrue(app.transcriber.open(Uri.fromFile(clip)))
        compose.waitUntil(30_000) { app.transcriber.state.value is FileTranscription.Ready }
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                val observed by app.transcriber.state.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    (observed as? FileTranscription.Ready)?.let { TranscribeScreen(app.transcriber, it, onClose = {}) }
                }
            }
        }
        compose.onNodeWithTag("transcribe-language").performClick()
    }

    @After
    fun tidy() {
        app.transcriber.close()
        clip.delete()
    }

    @Test
    fun theLastLanguageCanBeScrolledToAndPickedAtLargeFont() {
        openPicker(2f)
        val last = compose.onAllNodes(isSelectable())[UILanguage.entries.size - 1]
        last.performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { (app.transcriber.state.value as? FileTranscription.Ready)?.language == UILanguage.entries.last() }
    }

    @Test
    fun everyLanguageInThePickerIsAtLeastAFingerTallAtLargeFont() {
        openPicker(2f)
        assertAllTall()
    }

    @Test
    fun everyLanguageInThePickerIsAtLeastAFingerTall() {
        openPicker(1f)
        assertAllTall()
    }

    private fun assertAllTall() {
        val choices = compose.onAllNodes(isSelectable())
        choices.assertCountEquals(UILanguage.entries.size)
        for (index in UILanguage.entries.indices) {
            val bounds = choices[index].getUnclippedBoundsInRoot()
            assertTrue("choice $index is ${bounds.bottom - bounds.top} tall", bounds.bottom - bounds.top >= 48.dp)
        }
    }
}
