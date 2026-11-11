package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
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
                NameSpeakerDialog(shown = "Good evening", hasVoice = true, savedNames = listOf("Dana", "Shira"), onSave = { name, _ -> saved += name; true }, onDismiss = {})
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

    private val question = "Change “Noa” to “Noam” everywhere, also in saved conversations?"

    private fun inQuestion(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasAnyDescendant(hasText(question))))

    private fun askedToRename(): CopyOnWriteArrayList<Pair<String, Boolean>> {
        val saved = CopyOnWriteArrayList<Pair<String, Boolean>>()
        compose.runOnUiThread {
            compose.activity.setContent {
                NameSpeakerDialog(
                    shown = "Good evening",
                    hasVoice = true,
                    savedNames = listOf("Noam", "Dana"),
                    onSave = { name, onlyThisLine -> saved += name to onlyThisLine; true },
                    onDismiss = {},
                    renames = { if (it == "Noam") "Noa" else null },
                )
            }
        }
        chip("Noam").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), saved.toList())
        compose.onNodeWithText(question).assertExists()
        return saved
    }

    @Test
    fun aNameThatWouldRenameASavedPersonAsksAndOnlyThisLineKeepsThemAsTheyAre() {
        val saved = askedToRename()
        inQuestion("Only this line").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Noam" to true), saved.toList())
        assertEquals(0, compose.onAllNodesWithText("Change everywhere").fetchSemanticsNodes().size)
    }

    @Test
    fun changeEverywhereRenamesTheSavedPerson() {
        val saved = askedToRename()
        inQuestion("Change everywhere").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Noam" to false), saved.toList())
    }

    @Test
    fun cancelSavesNothingAndLeavesTheNameToEdit() {
        val saved = askedToRename()
        inQuestion("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), saved.toList())
        assertEquals(0, compose.onAllNodesWithText("Only this line").fetchSemanticsNodes().size)
        compose.onNode(hasSetTextAction()).assert(hasText("Noam"))
    }

    @Test
    fun aNameThatRenamesNobodySavesWithoutAsking() {
        val saved = askedToRename()
        inQuestion("Cancel").performClick()
        chip("Dana").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Dana" to false), saved.toList())
    }

    private fun askedWhileTheVoiceMayChange(voice: AtomicReference<String?>): CopyOnWriteArrayList<Pair<String, Boolean>> {
        val saved = CopyOnWriteArrayList<Pair<String, Boolean>>()
        compose.runOnUiThread {
            compose.activity.setContent {
                NameSpeakerDialog(
                    shown = "Good evening",
                    hasVoice = true,
                    savedNames = listOf("Noam", "Dana"),
                    onSave = { name, onlyThisLine -> saved += name to onlyThisLine; true },
                    onDismiss = {},
                    renames = { if (it == "Noam") voice.get() else null },
                )
            }
        }
        chip("Noam").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(question).assertExists()
        return saved
    }

    @Test
    fun aVoiceGivenAnotherSavedPersonWhileTheQuestionWasOpenIsAskedAboutAgain() {
        val voice = AtomicReference<String?>("Noa")
        val saved = askedWhileTheVoiceMayChange(voice)
        voice.set("Noah")
        inQuestion("Change everywhere").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), saved.toList())
        val again = "Change “Noah” to “Noam” everywhere, also in saved conversations?"
        compose.onNodeWithText(again).assertExists()
        compose.onNode(hasText("Change everywhere") and hasClickAction() and hasAnyAncestor(hasAnyDescendant(hasText(again)))).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Noam" to false), saved.toList())
    }

    @Test
    fun aVoiceNoLongerCarryingASavedNameSavesWithoutRenamingAnyone() {
        val voice = AtomicReference<String?>("Noa")
        val saved = askedWhileTheVoiceMayChange(voice)
        voice.set(null)
        inQuestion("Change everywhere").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Noam" to false), saved.toList())
    }
}
