package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SayStopDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val look = CaptionTheme.of(Theme.Dark, systemDark = true)

    @Test
    fun stopIsThereDimmedBeforeTheSpeechStartsAndPlayNeverMoves() {
        var speaking by mutableStateOf(false)
        var stops = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                SayScreen(
                    draft = "Hello", onDraft = {}, speaking = speaking, phrases = emptyList(), canSay = { true }, onSpeak = { true }, onStop = { stops++ },
                    onAddPhrase = {}, onRemovePhrase = {}, onRestorePhrase = { _, _ -> }, onMovePhrase = { _, _ -> }, onRestorePhrases = {},
                    phrasesAreDefault = false, look = look, captionSize = 30.0, onClose = {},
                )
            }
        }
        compose.onNodeWithText("Stop").assertIsNotEnabled()
        compose.onNodeWithText("Play").assertIsEnabled()
        val before = compose.onNodeWithText("Play").getUnclippedBoundsInRoot()
        val stopBefore = compose.onNodeWithText("Stop").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(0, stops)
        compose.runOnUiThread { speaking = true }
        compose.waitForIdle()
        compose.onNodeWithText("Stop").assertIsEnabled()
        assertEquals(before, compose.onNodeWithText("Play").getUnclippedBoundsInRoot())
        assertEquals(stopBefore, compose.onNodeWithText("Stop").getUnclippedBoundsInRoot())
        compose.onNodeWithText("Stop").performClick()
        assertTrue(stops == 1)
    }
}
