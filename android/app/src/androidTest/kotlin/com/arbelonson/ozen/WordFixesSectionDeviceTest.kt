package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbelonson.ozen.core.WordFix
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordFixesSectionDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun box(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun aGreyAddFixSaysWhyAndTheReasonGoesOnceTheFixCanBeAdded() {
        var fixes by mutableStateOf(emptyList<WordFix>())
        compose.runOnUiThread {
            compose.activity.setContent { Column { WordFixesSection(fixes) { fixes = it } } }
        }
        box("Heard").performTextInput("nasa")
        box("Written").performTextInput("NASA")
        compose.onNodeWithText("Add fix").assertIsNotEnabled()
        compose.onNodeWithText("“Heard” and “Written” are the same. A fix can’t change only capital letters.").assertExists()

        box("Heard").performTextClearance()
        box("Heard").performTextInput("one two three four five")
        compose.onNodeWithText("Add fix").assertIsNotEnabled()
        compose.onNodeWithText("In “Heard”: up to 4 words, with only spaces between them.").assertExists()

        box("Heard").performTextClearance()
        box("Heard").performTextInput("nassa")
        compose.onNodeWithText("Add fix").assertIsEnabled()
        compose.onNodeWithText("In “Heard”", substring = true).assertDoesNotExist()
        compose.onNodeWithText("are the same", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Add fix").performClick()
        compose.waitForIdle()
        assertEquals(listOf(WordFix("nassa", "NASA")), fixes)
    }
}
