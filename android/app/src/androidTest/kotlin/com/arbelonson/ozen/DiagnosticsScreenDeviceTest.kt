package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.ProblemAudioStore
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication

    @Test
    fun markingAProblemFromDiagnosticsSaysWhatWasKeptAndThatNoSoundWas() {
        val savingBefore = app.settings.current.value.saveHistory
        var marks = 0
        val source = object : DiagnosticsSource by app.captions {
            override fun markProblem(): Boolean {
                marks += 1
                return false
            }
        }
        try {
            for (saving in listOf(false, true)) {
                app.settings.change { it.saveHistory = saving }
                compose.runOnUiThread { compose.activity.setContent { DiagnosticsScreen(source, onClose = {}) } }
                compose.onNodeWithText("Mark a problem now").performScrollTo().performClick()
                compose.onNode(hasText("Problem marked") and hasAnyAncestor(isDialog())).assertExists()
                compose.onNode(hasText(ProblemNotes.marked(keptSound = false, keptCaptions = saving)) and hasAnyAncestor(isDialog())).assertExists()
                compose.onNode(hasText("OK") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Problem marked").fetchSemanticsNodes().isEmpty() }
            }
            assertEquals(2, marks)
        } finally {
            app.settings.change { it.saveHistory = savingBefore }
        }
    }

    @Test
    fun sendReportIsOnScreenWhenDiagnosticsOpensAboveTheTechnicalRows() {
        compose.runOnUiThread { compose.activity.setContent { DiagnosticsScreen(app.captions, onClose = {}) } }
        compose.onNodeWithText("Send report").assertIsDisplayed()
        val status = compose.onNodeWithText("Status").fetchSemanticsNode().positionInRoot.y
        for (button in listOf("Send report", "Copy report")) {
            val at = compose.onNodeWithText(button).fetchSemanticsNode().positionInRoot.y
            assertTrue("$button at $at is below Status at $status", at < status)
        }
    }

    @Test
    fun aKeptSoundTellsAScreenReaderThatActivatingItSharesItAndThatHoldingItOffersDelete() {
        val folder = File(app.filesDir, CaptionSession.PROBLEM_AUDIO_FOLDER)
        val aside = File(app.filesDir, "${CaptionSession.PROBLEM_AUDIO_FOLDER}-aside")
        moveAll(aside, folder)
        moveAll(folder, aside)
        val store = ProblemAudioStore(folder)
        val clip = store.save(floatArrayOf(0.1f, 0.2f), 16_000, Instant.parse("2026-03-04T05:06:07Z"))!!
        val title = DiagnosticsReport.clipLabel(clip.name)
        try {
            compose.runOnUiThread { compose.activity.setContent { DiagnosticsScreen(app.captions, onClose = {}) } }
            // "Send report" shares the text only: beside it, while a sound is
            // kept, the page says so, above the technical rows.
            val note = compose.onNodeWithText("Sound clips aren’t sent with the report. To send one, tap it under “Sound from marked problems”.")
            assertTrue(note.fetchSemanticsNode().positionInRoot.y < compose.onNodeWithText("Status").fetchSemanticsNode().positionInRoot.y)
            assertEquals("Recording from ${DiagnosticsReport.clipTitle(clip.name)}", title)
            val row = compose.onNodeWithText(title).performScrollTo()
            assertEquals("Share", row.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            assertEquals("Delete", row.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            row.performSemanticsAction(SemanticsActions.OnLongClick)
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
            assertFalse(clip.exists())
            compose.onNodeWithText("Sound clips aren’t sent", substring = true).assertDoesNotExist()
        } finally {
            store.deleteAll()
            moveAll(aside, folder)
            aside.delete()
        }
    }

    /**
     * The phone's own kept sounds wait in an aside folder while the test
     * runs, so running it never deletes them; a run cut short leaves them
     * there, and the next run moves them back first.
     */
    private fun moveAll(from: File, to: File) {
        val files = from.listFiles() ?: return
        to.mkdirs()
        for (file in files) assertTrue("couldn't move ${file.name}", file.renameTo(File(to, file.name)))
    }
}
