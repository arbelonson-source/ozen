package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.UILanguage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribePickerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication

    private fun openPicker(fontScale: Float) {
        val model = FileModelChoice.toDownload(UILanguage.English)
        val ready = FileTranscription.Ready("Lecture.wav", UILanguage.English, model, listOf(model), model, 60.0, false)
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    TranscribeScreen(app.transcriber, ready, onClose = {})
                }
            }
        }
        compose.onNodeWithTag("transcribe-language").performClick()
    }

    @Test
    fun theLastLanguageCanBeScrolledToAndPickedAtLargeFont() {
        openPicker(2f)
        val last = compose.onAllNodes(isSelectable())[UILanguage.entries.size - 1]
        last.performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isSelectable()).fetchSemanticsNodes().isEmpty() }
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
