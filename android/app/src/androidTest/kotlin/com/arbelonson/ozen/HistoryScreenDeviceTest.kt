package com.arbelonson.ozen

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
    private val seeded = mutableListOf<UUID>()
    private val savingBefore = app.settings.current.value.saveHistory
    private val retentionBefore = app.settings.current.value.historyRetention
    private val displayBefore = app.settings.current.value.display

    @After
    fun restore() {
        seeded.forEach(store::delete)
        app.settings.change {
            it.saveHistory = savingBefore
            it.historyRetention = retentionBefore
            it.display = displayBefore
        }
    }

    private fun seed(vararg lines: Pair<String, String?>, startedAt: Double = System.currentTimeMillis() / 1_000.0 - 60): UUID {
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = lines.mapIndexed { index, (text, speaker) ->
                SavedSegment(UUID.randomUUID(), text, speaker, null, startedAt + index, true)
            },
        )
        store.save(record)
        seeded += record.id
        return record.id
    }

    @Test
    fun searchingFindsTheConversationWhereTheWordsWereSaidAndShowsTheLineThatMatched() {
        seed("Search test opening line" to null, "The dentist moved it to Thursday" to null)
        seed("Search test other conversation" to null)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Search test other conversation", substring = true).assertExists()

        compose.onNodeWithText("Search what was said").performTextInput("dentist thursday")
        waitFor("The dentist moved it to Thursday")
        compose.onNodeWithText("Search test other conversation", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Automatic deletion").assertDoesNotExist()

        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Search what was said").performTextInput("zebra crossing")
        waitFor("Nothing found for “zebra crossing”.")

        compose.onNodeWithText("Clear").performClick()
        waitFor("Search test other conversation")
        compose.onNodeWithText("Automatic deletion").assertExists()
    }

    @Test
    fun aSearchResultOpensAtTheFirstLineThatMatchedAndStepsToTheNext() {
        val lines = (1..60).map { "Filler line number $it" to null as String? }.toMutableList()
        lines[0] = "The very first line" to null
        lines[20] = "The dentist said Thursday" to null
        lines[50] = "Remind me about the dentist" to null
        seed(*lines.toTypedArray())
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Search what was said").performTextInput("dentist")
        waitFor("The dentist said Thursday")
        compose.onNodeWithText("The dentist said Thursday", substring = true).performClick()

        compose.waitUntil(5_000) { shownLine("The dentist said Thursday") }
        assertFalse(shownLine("The very first line"))
        assertFalse(shownLine("Remind me about the dentist"))
        compose.onNodeWithContentDescription("The dentist said Thursday", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Contains what you searched for"))

        compose.onNodeWithText("Next match (1 of 2)").performClick()
        compose.waitUntil(5_000) { shownLine("Remind me about the dentist") }
        compose.onNodeWithText("Next match (2 of 2)").assertExists()
    }

    @Test
    fun numbersMentionedGathersTheTimesAtTheTopAndATapLeadsToWhereItWasSaid() {
        val lines = (1..40).map { "Nice weather we are having" to null as String? }.toMutableList()
        lines[35] = "Come back at 10:30 on Sunday" to null
        seed(*lines.toTypedArray())
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Nice weather we are having", substring = true).performClick()

        waitFor("Numbers mentioned")
        assertFalse(shownLine("Come back at 10:30 on Sunday"))
        compose.onNodeWithText("Come back at 10:30", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("Come back at 10:30 on Sunday") }
    }

    @Test
    fun shareOffersTheWholeConversationAsTextAndSaysWhichLinesMayBeMisheard() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), "Pharmacy test it closes at nine", "Dana", null, startedAt, true),
                SavedSegment(UUID.randomUUID(), "Pharmacy test two pills after dinner", null, null, startedAt + 5, true, confidence = 0.05f),
            ),
        )
        store.save(record)
        seeded += record.id
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = shareSheetMonitor(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            app.settings.change { it.display = it.display.copy(markUncertainLines = true) }
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Pharmacy test it closes", substring = true).performClick()
            compose.waitUntil(5_000) { shownLine("Pharmacy test two pills") }
            compose.onNodeWithText("Share").performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            val chooser = started.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("Conversation from Ozen", send.getStringExtra(Intent.EXTRA_SUBJECT))
            val marked = send.getStringExtra(Intent.EXTRA_TEXT)!!
            assertEquals(TranscriptHistoryStore.exportText(record, ::offsetHere, marksUncertain = true), marked)
            assertTrue(marked, marked.contains("Dana: Pharmacy test it closes at nine"))
            assertTrue(marked, marked.contains("May not have been heard correctly. Pharmacy test two pills after dinner"))

            started.clear()
            app.settings.change { it.display = it.display.copy(markUncertainLines = false) }
            compose.waitForIdle()
            compose.onNodeWithText("Share").performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            @Suppress("DEPRECATION")
            val plain = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)!!
            assertFalse(plain, plain.contains("May not have been heard correctly"))
            assertTrue(plain, plain.contains("Pharmacy test two pills after dinner"))
        } finally {
            instrumentation.removeMonitor(sheet)
        }
    }

    private fun longConversation(opening: String): TranscriptSessionRecord {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 3_600
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 3_000,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = (0 until 1_000).map {
                val text = if (it == 0) opening else "A long lecture about the history of medicine and the pharmacy"
                SavedSegment(UUID.randomUUID(), text, null, null, startedAt + it * 3, true)
            },
        )
        store.save(record)
        seeded += record.id
        return record
    }

    private fun shareSheetMonitor(started: MutableList<Intent>) = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            started += intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private fun openAndShare(opening: String, started: List<Intent>) {
        compose.onNodeWithText("History").performClick()
        waitFor(opening)
        compose.onNodeWithText(opening, substring = true).performClick()
        waitFor("Share")
        compose.onNodeWithText("Share").performClick()
        compose.waitUntil(5_000) { started.isNotEmpty() }
    }

    @Test
    fun aConversationTooLongForAChatMessageIsSharedAsATextFile() {
        val record = longConversation("Long share test opening")
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = shareSheetMonitor(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            openAndShare("Long share test opening", started)
            @Suppress("DEPRECATION")
            val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("Conversation from Ozen", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
            @Suppress("DEPRECATION")
            val file = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", file.scheme)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(file, send.clipData!!.getItemAt(0).uri)
            val expected = TranscriptHistoryStore.exportText(record, ::offsetHere, marksUncertain = app.settings.current.value.display.markUncertainLines)
            assertTrue(expected.length > 60_000)
            assertEquals(expected, app.contentResolver.openInputStream(file)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            instrumentation.removeMonitor(sheet)
        }
    }

    @Test
    fun deletingAConversationAlsoDeletesTheCopyLastSharedAsAFile() {
        val record = longConversation("Shared then deleted opening")
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = shareSheetMonitor(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            openAndShare("Shared then deleted opening", started)
            assertEquals(1, sharedCopies().size)

            compose.onNodeWithText("Delete").performClick()
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { store.load(record.id) == null }
            assertEquals(emptyList<File>(), sharedCopies())
        } finally {
            instrumentation.removeMonitor(sheet)
        }
    }

    private fun sharedCopies() = File(app.cacheDir, CaptionSession.SHARED_FOLDER).listFiles()?.toList() ?: emptyList()

    private fun offsetHere(at: Double) = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli((at * 1_000).toLong())).totalSeconds

    private fun shownLine(text: String) =
        compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithContentDescription(text, substring = true).isDisplayed()

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aSavedConversationIsListedUnderTodayAndReadsBackLineByLine() {
        seed("Device history opening line" to "Dana", "Device history second line" to "Dana")
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Device history opening line", substring = true).performClick()
        compose.onNodeWithContentDescription("Dana: Device history opening line", substring = true).assertExists()
        compose.onNodeWithContentDescription("Device history second line", substring = true).assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Device history opening line", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun savingConversationsSwitchesOffAndOnAndTheFooterSaysWhichItIs() {
        app.settings.change { it.saveHistory = true }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Conversations are saved on this phone", substring = true).assertExists()
        compose.onNodeWithText("Save conversations").performClick()
        compose.waitForIdle()
        assertFalse(app.settings.current.value.saveHistory)
        compose.onNodeWithText("New conversations are not being saved.", substring = true).assertExists()
        compose.onNodeWithText("Save conversations").performClick()
        compose.waitForIdle()
        assertTrue(app.settings.current.value.saveHistory)
    }

    @Test
    fun aShorterKeepingTimeAsksBeforeDeletingOldConversations() {
        app.settings.change { it.historyRetention = HistoryRetention.Forever }
        val old = seed("Device history from long ago" to null, startedAt = System.currentTimeMillis() / 1_000.0 - 40 * 86_400)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Automatic deletion").performClick()
        compose.onNodeWithText("After a month").performClick()
        compose.onNodeWithText("old conversation", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(HistoryRetention.Forever, app.settings.current.value.historyRetention)
        assertNotNull(store.load(old))

        val shared = File(app.cacheDir, CaptionSession.SHARED_FOLDER).also { it.mkdirs() }.resolve("Conversation from Ozen.txt")
        shared.writeText("Device history from long ago")
        compose.onNodeWithText("Automatic deletion").performClick()
        compose.onNodeWithText("After a month").performClick()
        compose.onNodeWithText("Delete and continue this way").performClick()
        compose.waitUntil(5_000) { store.load(old) == null }
        compose.waitUntil(5_000) { !shared.exists() }
        assertEquals(HistoryRetention.Month, app.settings.current.value.historyRetention)
        compose.onNodeWithText("Device history from long ago", substring = true).assertDoesNotExist()
    }

    @Test
    fun deletingAConversationAsksFirstThenRemovesItFromTheListAndThePhone() {
        val id = seed("Device history line to delete" to null)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Device history line to delete", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this conversation?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(store.load(id))

        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertNull(store.load(id))
        compose.onNodeWithText("History").assertExists()
        compose.onNodeWithText("Device history line to delete", substring = true).assertDoesNotExist()
    }
}
