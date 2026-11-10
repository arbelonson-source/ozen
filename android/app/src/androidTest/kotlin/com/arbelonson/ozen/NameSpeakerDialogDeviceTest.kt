package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NameSpeakerDialogDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun chip(name: String) = compose.onNode(hasText(name) and hasClickAction() and !hasSetTextAction())

    @Test
    fun aSavedNameOnlyFillsTheNameAndSaveCommitsIt() {
        val saved = CopyOnWriteArrayList<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                NameSpeakerDialog(shown = "Good evening", hasVoice = true, savedNames = listOf("Dana", "Shira"), onSave = { saved += it }, onDismiss = {})
            }
        }
        chip("Shira").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), saved.toList())
        compose.onNode(hasSetTextAction()).assert(hasText("Shira"))
        chip("Shira").assertIsSelected()
        chip("Dana").performClick()
        compose.waitForIdle()
        chip("Dana").assertIsSelected()
        chip("Shira").assertIsNotSelected()
        assertEquals(emptyList<String>(), saved.toList())
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Dana"), saved.toList())
    }
}
