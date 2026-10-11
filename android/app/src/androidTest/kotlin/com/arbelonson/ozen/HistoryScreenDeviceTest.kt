package com.arbelonson.ozen

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CaptionLayout
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
        val sheet = startsHeldBack(started)
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

    private fun startsHeldBack(started: MutableList<Intent>) = object : Instrumentation.ActivityMonitor() {
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
        val sheet = startsHeldBack(started)
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
        val sheet = startsHeldBack(started)
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

    @Test
    fun deleteAllAsksFirstThenDeletesEverySavedConversation() {
        seed("Delete all test first" to null)
        seed("Delete all test second" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Delete all test second")
        compose.onNodeWithText("Delete all").performClick()
        compose.onNodeWithText("Delete all saved conversations?").assertExists()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(2, seeded.count { store.load(it) != null })

        compose.onNodeWithText("Delete all").performClick()
        compose.onNode(hasText("Delete all") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { store.listSummaries().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete all test", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Delete all").assertIsNotEnabled()
    }

    @Test
    fun aConversationIsDeletedFromTheListByHoldingItOrByTheScreenReaderAfterAskingFirst() {
        val kept = seed("Row delete test kept" to null)
        val held = seed("Row delete test held" to null)
        val read = seed("Row delete test read" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Row delete test held")
        compose.onNodeWithText("Row delete test held", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this conversation?").assertExists()
        compose.onNode(hasText("Row delete test held", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertNotNull(store.load(held))

        compose.onNodeWithText("Row delete test held", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { store.load(held) == null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Row delete test held", substring = true).fetchSemanticsNodes().isEmpty() }
        assertNotNull(store.load(kept))
        assertNotNull(store.load(read))

        val row = compose.onNode(hasText("Row delete test read", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        val delete = row.config[SemanticsActions.CustomActions].single { it.label == "Delete" }
        compose.runOnUiThread { delete.action() }
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { store.load(read) == null }
        assertNotNull(store.load(kept))
    }

    @Test
    fun aNameGivenToAConversationIsListedFoundBySearchAndCanBeTakenAway() {
        val id = seed("Naming test opening line" to null)
        seed("Naming test other conversation" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Naming test opening line")
        compose.onNodeWithText("Naming test opening line", substring = true).performClick()
        waitFor("Name this conversation")
        compose.onNodeWithText("Name this conversation").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertNull(store.load(id)!!.title)

        compose.onNodeWithText("Name this conversation").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("  Dentist visit ")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.load(id)?.title == "Dentist visit" }
        waitFor("Dentist visit")

        compose.onNodeWithText("Back").performClick()
        waitFor("Dentist visit")
        compose.onNodeWithText("Search what was said").performTextInput("dentist visit")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Naming test other conversation", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Dentist visit", substring = true).performClick()

        waitFor("Name this conversation")
        compose.onNodeWithText("Name this conversation").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assert(hasText("Dentist visit"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.load(id)?.title == null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dentist visit", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun savedLinesSayWhichWereStarredAndWhichMayBeMisheardWhileTheMarksAreOn() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), "Marks test two pills after dinner", null, null, startedAt, true, confidence = 0.05f),
                SavedSegment(UUID.randomUUID(), "Marks test call the pharmacy", null, null, startedAt + 5, true, isStarred = true, confidence = 0.95f),
            ),
        )
        store.save(record)
        seeded += record.id
        app.settings.change { it.display = it.display.copy(markUncertainLines = true) }
        compose.onNodeWithText("History").performClick()
        waitFor("Marks test two pills")
        compose.onNodeWithText("Marks test two pills", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("May not have been heard correctly. Marks test two pills after dinner") }
        compose.onNodeWithContentDescription("Marked as important. Marks test call the pharmacy", substring = true).assertExists()

        compose.onNodeWithText("Back").performClick()
        app.settings.change { it.display = it.display.copy(markUncertainLines = false) }
        waitFor("Marks test two pills")
        compose.onNodeWithText("Marks test two pills", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("Marks test two pills after dinner") }
        assertTrue(compose.onAllNodesWithContentDescription("May not have been heard", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Marked as important. Marks test call the pharmacy", substring = true).assertExists()
    }

    @Test
    fun aSavedLineIsStarredByHoldingItAndTheStarTakenAwayByTheScreenReaderAction() {
        val id = seed("Star test opening line" to null, "Star test the pharmacy closes at nine" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Star test opening line")
        compose.onNodeWithText("Star test opening line", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("Star test the pharmacy closes at nine") }
        compose.onNodeWithContentDescription("Star test the pharmacy closes at nine", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Mark as important").performClick()
        compose.waitUntil(5_000) { store.load(id)!!.segments.map { it.isStarred } == listOf(false, true) }
        compose.waitUntil(5_000) { shownLine("Marked as important. Star test the pharmacy closes at nine") }

        val line = compose.onNodeWithContentDescription("Star test the pharmacy closes at nine", substring = true).fetchSemanticsNode()
        val remove = line.config[SemanticsActions.CustomActions].single { it.label == "Remove the star" }
        compose.runOnUiThread { remove.action() }
        compose.waitUntil(5_000) { store.load(id)!!.segments.none { it.isStarred } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Marked as important", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun starredConversation(line: String, startedAt: Double): UUID {
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), line.replace("Starred list test", "Opening line of"), null, null, startedAt, true),
                SavedSegment(UUID.randomUUID(), line, null, null, startedAt + 5, true, isStarred = true),
            ),
        )
        store.save(record)
        seeded += record.id
        return record.id
    }

    @Test
    fun everyStarredLineIsGatheredNewestFirstSharedCopiedAndOpenedWhereItWasSaid() {
        val now = System.currentTimeMillis() / 1_000.0
        starredConversation("Starred list test call the pharmacy", now - 3 * 86_400)
        val pills = starredConversation("Starred list test two pills after dinner", now - 120)
        app.settings.change { it.display = it.display.copy(markUncertainLines = false) }
        compose.onNodeWithText("History").performClick()
        waitFor("Opening line of two pills")
        val total = store.listSummaries().sumOf { it.starredCount }
        compose.onNode(hasText("Starred lines") and hasText("$total")).performClick()
        compose.waitUntil(5_000) { shownLine("Starred list test two pills after dinner") }
        val newer = compose.onNodeWithContentDescription("Starred list test two pills after dinner", substring = true)
        val older = compose.onNodeWithContentDescription("Starred list test call the pharmacy", substring = true)
        assertTrue(newer.getUnclippedBoundsInRoot().top < older.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Today").assertExists()

        val started = CopyOnWriteArrayList<Intent>()
        val sheet = startsHeldBack(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            compose.onNodeWithText("Share").performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            @Suppress("DEPRECATION")
            val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("Starred lines from Ozen", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(TranscriptHistoryStore.exportStarredText(store.starredLines(), utcOffsetAt = ::offsetHere, marksUncertain = false), send.getStringExtra(Intent.EXTRA_TEXT))
        } finally {
            instrumentation.removeMonitor(sheet)
        }

        older.performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("Starred list test call the pharmacy") }
        val copy = newer.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Copy" }
        compose.runOnUiThread { copy.action() }
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("Starred list test two pills after dinner") }

        newer.performClick()
        compose.waitUntil(5_000) { shownLine("Marked as important. Starred list test two pills after dinner") }
        val line = compose.onNodeWithContentDescription("Starred list test two pills after dinner", substring = true).fetchSemanticsNode()
        val remove = line.config[SemanticsActions.CustomActions].single { it.label == "Remove the star" }
        compose.runOnUiThread { remove.action() }
        compose.waitUntil(5_000) { store.load(pills)!!.segments.none { it.isStarred } }
        compose.onNodeWithText("Back").performClick()
        compose.waitUntil(5_000) { shownLine("Starred list test call the pharmacy") }
        assertTrue(compose.onAllNodesWithContentDescription("Starred list test two pills", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun withNoSearchTheButtonLeadsToEachStarredLineInTurn() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 600
        val texts = MutableList(120) { "Filler line about the weather" }
        texts[0] = "Starred steps test opening"
        texts[1] = "Come back at 10:30 on Sunday"
        texts[34] = "The line just before the first star"
        texts[35] = "First starred line about the pills"
        texts[69] = "The line just before the second star"
        texts[70] = "Second starred line about the pharmacy"
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 300,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = texts.mapIndexed { index, text ->
                SavedSegment(UUID.randomUUID(), text, null, null, startedAt + index, true, isStarred = index == 35 || index == 70)
            },
        )
        store.save(record)
        seeded += record.id
        compose.onNodeWithText("History").performClick()
        waitFor("Starred steps test opening")
        compose.onNodeWithText("Starred steps test opening", substring = true).performClick()
        waitFor("Numbers mentioned")
        assertFalse(shownLine("First starred line about the pills"))

        compose.onNodeWithText("To starred lines (2)").performClick()
        compose.waitUntil(5_000) { shownLine("First starred line about the pills") }
        assertFalse(shownLine("The line just before the first star"))

        compose.onNodeWithText("Next starred (1 of 2)").performClick()
        compose.waitUntil(5_000) { shownLine("Second starred line about the pharmacy") }
        assertFalse(shownLine("The line just before the second star"))

        compose.onNodeWithText("Next starred (2 of 2)").performClick()
        compose.waitUntil(5_000) { shownLine("First starred line about the pills") }
        compose.onNodeWithText("Next starred (1 of 2)").assertExists()
    }

    @Test
    fun aSavedLineIsCopiedFromItsMenuOrTheScreenReaderAction() {
        seed("Copy test opening line" to null, "תתקשרי למרפאה 050 123 4567" to null, "The address is 12 Herzl Street" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Copy test opening line")
        compose.onNodeWithText("Copy test opening line", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("050 123 4567") }
        compose.onNodeWithContentDescription("050 123 4567", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("תתקשרי למרפאה 050 123 4567") }

        val address = compose.onNodeWithContentDescription("The address is 12 Herzl Street", substring = true).fetchSemanticsNode()
        val copy = address.config[SemanticsActions.CustomActions].single { it.label == "Copy" }
        compose.runOnUiThread { copy.action() }
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("The address is 12 Herzl Street") }
    }

    @Test
    fun aPhoneNumberInASavedLineOpensTheDialerFromATapOrTheScreenReaderAction() {
        seed("Phone test opening line" to null, "050 123 4567" to null)
        val started = CopyOnWriteArrayList<Intent>()
        val dialer = startsHeldBack(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(dialer)
        try {
            compose.onNodeWithText("History").performClick()
            waitFor("Phone test opening line")
            compose.onNodeWithText("Phone test opening line", substring = true).performClick()
            compose.waitUntil(5_000) { shownLine("050 123 4567") }
            compose.onNodeWithContentDescription("050 123 4567", substring = true).performTouchInput { longClick(Offset(40f, centerY)) }
            waitFor("Mark as important")
            assertTrue(started.isEmpty())
            compose.onNodeWithText("Copy").performClick()
            compose.waitForIdle()
            assertTrue(started.isEmpty())

            compose.onNodeWithContentDescription("050 123 4567", substring = true).performTouchInput { click(Offset(40f, centerY)) }
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Intent.ACTION_VIEW, started.single().action)
            assertEquals(Uri.parse("tel:0501234567"), started.single().data)

            started.clear()
            val line = compose.onNodeWithContentDescription("050 123 4567", substring = true).fetchSemanticsNode()
            val call = line.config[SemanticsActions.CustomActions].single { it.label == "Call 0501234567" }
            compose.runOnUiThread { call.action() }
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Uri.parse("tel:0501234567"), started.single().data)
        } finally {
            instrumentation.removeMonitor(dialer)
        }
    }

    private fun copied(): String? {
        var text: String? = null
        compose.runOnUiThread { text = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        return text
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
