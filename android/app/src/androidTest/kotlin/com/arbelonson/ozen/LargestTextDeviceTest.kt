package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CloudHTTP
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.FileModelChoice
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargestTextDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val model = FileModelChoice.toDownload(UILanguage.English)
    private val lines = (0 until 30).map { TranscribedLine(it * 4.0, it * 4.0 + 4.0, "Line $it of the talk at the community centre") }
    private val longName = "The talk at the community centre on Tuesday evening, part two.m4a"

    private fun onASmallPhoneAtTheLargestText(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.assertWhollyOnScreen() {
        val node = assertIsDisplayed().fetchSemanticsNode()
        assertEquals("only part of it is on screen", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    @Test
    fun aRecordingTurnedIntoTextStillShowsItsLinesAtTheLargestTextOnASmallPhone() {
        val done = FileTranscription.Done(longName, UILanguage.English, model, lines, HistorySave.Off)
        onASmallPhoneAtTheLargestText { TranscribeScreen(app.transcriber, done, onClose = {}) }
        compose.onAllNodesWithTag(TRANSCRIBE_LINE)[0].assertWhollyOnScreen()
    }

    @Test
    fun whileARecordingIsTurnedIntoTextCancelAndTheNewestLineCanBeReachedAtTheLargestText() {
        val working = FileTranscription.Working(longName, UILanguage.English, model, FileTranscription.Working.Stage.Transcribing, 0.5f, 90.0, lines)
        onASmallPhoneAtTheLargestText { TranscribeScreen(app.transcriber, working, onClose = {}) }
        val cancel = compose.onNode(hasTestTag(TRANSCRIBE_CANCEL))
        val fingerSize = compose.activity.resources.displayMetrics.density * 48
        assertTrue("Cancel was squeezed to ${cancel.fetchSemanticsNode().size.height} px", cancel.fetchSemanticsNode().size.height >= fingerSize)
        cancel.performScrollTo().assertWhollyOnScreen()
        val newest = hasText("Line 29 of the talk", substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(newest)
        compose.onNode(newest).assertWhollyOnScreen()
    }

    @Test
    fun aSavedConversationKeepsRoomForItsLinesAtTheLargestTextAndNextMatchStillLandsOnTheLine() {
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val said = (1..40).map { if (it == 10 || it == 30) "Remember the dentist, line $it" else "Filler line number $it" }
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 40,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = said.mapIndexed { index, text -> SavedSegment(UUID.randomUUID(), text, null, null, startedAt + index, true) },
        )
        store.save(record)
        try {
            val settings = app.settings.current.value
            onASmallPhoneAtTheLargestText {
                HistoryScreen(app.captions.savedConversations, true, settings.historyRetention, false, false, onClose = {}, languageCode = "en")
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Search what was said").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Search what was said").performTextInput("dentist")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Remember the dentist, line 10", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Remember the dentist, line 10", substring = true).performClick()
            val first = hasContentDescription("Remember the dentist, line 10", substring = true)
            compose.waitUntil(5_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(first).assertWhollyOnScreen()
            val whole = compose.onAllNodes(hasContentDescription("Filler line number", substring = true) or hasContentDescription("Remember the dentist", substring = true))
                .fetchSemanticsNodes().count { it.boundsInRoot.height > 0f && it.size.height - it.boundsInRoot.height < 1f }
            assertTrue("only $whole whole lines are on screen", whole >= 3)
            compose.onNodeWithText("Next match (1 of 2)").performScrollTo().performClick()
            val next = hasContentDescription("Remember the dentist, line 30", substring = true)
            compose.waitUntil(5_000) { compose.onAllNodes(next).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(next).assertWhollyOnScreen()
        } finally {
            store.delete(record.id)
        }
    }

    @Test
    fun theCloudConsentCanBeReadToTheEndAtTheLargestText() {
        val keyCheck = app.cloudKeyCheckHTTP
        val engine = app.settings.current.value.engine
        val service = app.settings.current.value.cloudProvider
        val acknowledged = app.settings.current.value.cloudSoundAcknowledged
        val key = app.cloudKeys.read(CloudProvider.Deepgram)
        val scale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        try {
            app.cloudKeyCheckHTTP = CloudHTTP { throw IOException("device tests never reach a real service") }
            app.cloudKeys.remove(CloudProvider.Deepgram)
            app.settings.change {
                it.engine = TranscriptionEngineKind.WhisperKit
                it.cloudSoundAcknowledged = emptySet()
            }
            // A small phone, 360 by 640 points. On a tall phone the consent
            // only just overflows at the largest text (by 64 pixels on a
            // Pixel 7, keyboard up or down), and on CI's Pixel 6, a little
            // taller in points, it sometimes fit: the check below then
            // failed with nothing wrong.
            shell("settings put system font_scale 2.0")
            shell("wm size 720x1280")
            shell("wm density 320")
            compose.waitUntil(10_000) {
                val configuration = compose.activity.resources.configuration
                configuration.fontScale == 2f && configuration.screenWidthDp == 360
            }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Cloud transcription").performScrollTo().performClick()
            compose.onNodeWithText("Deepgram").performScrollTo().performClick()
            compose.onNodeWithText("Paste your Deepgram key here").performScrollTo().performTextInput("dg-largest")
            compose.onNodeWithText("Paste your Deepgram key here").performImeAction()
            val consent = hasText("While captions come from Deepgram", substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            compose.waitUntil(5_000) { compose.onAllNodes(consent).fetchSemanticsNodes().isNotEmpty() }
            val range = compose.onNode(consent).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("the consent fits, so this test proves nothing: ${range.maxValue()}", range.maxValue() > 0f)
            compose.onNodeWithText("Allow and save").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            shell("wm size reset")
            shell("wm density reset")
            shell("settings put system font_scale $scale")
            app.cloudKeyCheckHTTP = keyCheck
            key?.let { app.cloudKeys.save(it, CloudProvider.Deepgram) } ?: app.cloudKeys.remove(CloudProvider.Deepgram)
            app.settings.change {
                it.engine = engine
                it.cloudProvider = service
                it.cloudSoundAcknowledged = acknowledged
            }
        }
    }
}
