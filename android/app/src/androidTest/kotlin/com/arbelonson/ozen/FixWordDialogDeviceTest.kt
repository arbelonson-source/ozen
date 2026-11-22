package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixWordDialogDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aLineThatGetsShorterWhileItsLastWordIsPickedKeepsTheDialogOpen() {
        var shown by mutableStateOf("we will meet at the station at nine and then go home")
        compose.runOnUiThread {
            compose.activity.setContent { FixWordDialog(shown, languageCode = "en", fixes = emptyList(), onSave = { _, _, _ -> }, onDismiss = {}) }
        }
        compose.onNodeWithContentDescription("home").performClick()
        compose.onNodeWithText("Now type how it should be written").assertExists()
        compose.runOnUiThread { shown = "we will meet at the station" }
        compose.waitForIdle()
        compose.onNodeWithText("Fix a word").assertExists()
        compose.onNodeWithContentDescription("home").assertDoesNotExist()
        compose.onNodeWithText("Now type how it should be written").assertDoesNotExist()
        compose.onNodeWithContentDescription("station").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("station").assertIsSelected()
        compose.onNodeWithText("Now type how it should be written").assertExists()
    }
}
