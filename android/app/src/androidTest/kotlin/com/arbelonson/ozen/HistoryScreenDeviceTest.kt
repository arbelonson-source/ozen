package com.arbelonson.ozen

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithTag
import com.arbelonson.ozen.core.DisplayPreferences
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
        compose.onNodeWithText("Delete all").assertIsNotEnabled()

        compose.onNodeWithText("Clear").performClick()
        waitFor("Search test other conversation")
        compose.onNodeWithText("Automatic deletion").assertExists()
    }

    @Test
    fun aNameAboveTheListShowsOnlyThatPersonsConversationsAndStaysChosenWhileSearchingAndReading() {
        seed("Chip test soup recipe" to "Grandma Chip", "Chip test more soup" to null)
        seed("Chip test football scores" to "Uncle Chip")
        seed("Chip test nobody named" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Chip test nobody named")
        val grandma = compose.onNode(hasTestTag("speakerChip:Grandma Chip"))
        grandma.assertIsNotSelected()
        val bounds = grandma.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)

        grandma.performClick()
        grandma.assertIsSelected()
        compose.onNodeWithText("Chip test soup recipe", substring = true).assertExists()
        compose.onNodeWithText("Chip test football scores", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Chip test nobody named", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Search what was said").performTextInput("football")
        waitFor("Nothing found for “football”.")
        grandma.assertIsSelected()

        compose.onNodeWithText("Clear").performClick()
        waitFor("Chip test soup recipe")
        compose.onNodeWithText("Chip test soup recipe", substring = true).performClick()
        waitFor("Share")
        compose.onNodeWithText("Back").performClick()
        waitFor("Chip test soup recipe")
        compose.onNodeWithText("Chip test football scores", substring = true).assertDoesNotExist()

        grandma.performClick()
        waitFor("Chip test football scores")
        grandma.assertIsNotSelected()
    }

    @Test
    fun aChosenNameWhoseLastConversationWasDeletedLetsGoInsteadOfHidingEveryConversation() {
        seed("Chip gone soup recipe" to "Grandma Gone")
        seed("Chip gone other talk" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Chip gone other talk")
        compose.onNode(hasTestTag("speakerChip:Grandma Gone")).performClick()
        compose.onNodeWithText("Chip gone other talk", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Chip gone soup recipe", substring = true).performClick()
        waitFor("Share")
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        waitFor("Chip gone other talk")
        compose.onNode(hasTestTag("speakerChip:Grandma Gone")).assertDoesNotExist()
        compose.onNodeWithText("No conversations with", substring = true).assertDoesNotExist()
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
    fun aNumberMentionedCopiesFromItsRowWhichSaysWhenItMayHaveBeenMisheard() {
        app.settings.change { it.display = it.display.copy(markUncertainLines = true) }
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), "Numbers row test at 10:30 on Sunday", null, null, startedAt, true, confidence = 0.05f),
                SavedSegment(UUID.randomUUID(), "Numbers row test bring 3 forms", null, null, startedAt + 5, true),
            ),
        )
        store.save(record)
        seeded += record.id
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Numbers row test at 10:30", substring = true).performClick()
        waitFor("Numbers mentioned")
        val row = { text: String -> compose.onNode(hasText(text, substring = true) and hasClickAction()) }
        val unsure = "May not have been heard correctly."
        row("at 10:30 on Sunday").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, unsure))
        // Said once, as on the iPhone: the question mark's own label is not read too.
        row("at 10:30 on Sunday").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        row("bring 3 forms").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))

        val copy = row("bring 3 forms").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Copy" }
        compose.runOnUiThread { copy.action() }
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("Numbers row test bring 3 forms") }
        row("at 10:30 on Sunday").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("Numbers row test at 10:30 on Sunday") }
    }

    @Test
    fun aSavedConversationOpensWithASummaryOfWhoSaidHowMuchAndTheTurns() {
        seed(
            "Summary test weather is lovely" to "Dana",
            "Yes it is lovely" to "Yossi",
            "Nice weather we are having" to "Dana",
            "Nice weather we are having" to "Dana",
        )
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Summary test weather is lovely", substring = true).performClick()

        waitFor("Summary")
        compose.onNodeWithText("2 speakers", substring = true).assertExists()
        compose.onNodeWithText("19 words", substring = true).assertExists()
        compose.onNodeWithText("15 words · 79%").assertExists()
        compose.onNodeWithText("4 words · 21%").assertExists()
        compose.onNodeWithText("Speaking pace").assertExists()
        compose.onNode(hasText("Speaker turns") and hasText("3")).assertExists()
        compose.onNode(hasText("Longest turn") and hasText("Dana · 10 words")).assertExists()
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
    fun starredLinesTooLongForAChatMessageAreSharedAsATextFile() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 3_600
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 3_000,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = (0 until 600).map {
                SavedSegment(UUID.randomUUID(), "Starred share test line $it about the history of medicine and the pharmacy", null, null, startedAt + it * 3, true, isStarred = true)
            },
        )
        store.save(record)
        seeded += record.id
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = startsHeldBack(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            compose.onNodeWithText("History").performClick()
            waitFor("Starred share test line 0")
            val total = store.listSummaries().sumOf { it.starredCount }
            compose.onNode(hasText("Starred lines") and hasText("$total")).performClick()
            waitFor("Share")
            compose.onNodeWithText("Share").performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            @Suppress("DEPRECATION")
            val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("Starred lines from Ozen", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
            @Suppress("DEPRECATION")
            val file = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val shared = app.contentResolver.openInputStream(file)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(shared.length > 50_000)
            assertTrue(shared.contains("Starred share test line 599 about the history"))
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
    fun deleteAllAsksFirstThenDeletesEverySavedConversationAndTheCaptionLinesAMarkedProblemKept() {
        val journal = SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE))
        val said = "  line (final, sure 0.88, 09:15:00): Delete all test, a line kept with a marked problem"
        val note = "delete all test: not a caption line"
        journal.append(note, System.currentTimeMillis() / 1_000.0)
        journal.append(said, System.currentTimeMillis() / 1_000.0)
        seed("Delete all test first" to null)
        seed("Delete all test second" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Delete all test second")
        compose.onNodeWithText("Delete all").performClick()
        compose.onNodeWithText("Delete all saved conversations?").assertExists()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(2, seeded.count { store.load(it) != null })
        assertTrue(journal.entries().any { it.text == said })

        compose.onNodeWithText("Delete all").performClick()
        compose.onNode(hasText("Delete all") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { store.listSummaries().isEmpty() }
        compose.waitUntil(5_000) { journal.entries().none { it.text == said } }
        assertTrue("delete all took the rest of the journal with it", journal.entries().any { it.text == note })
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
    fun turningThePhoneKeepsTheConversationsNameBeingTyped() {
        val id = seed("Turning test opening line" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Turning test opening line")
        compose.onNodeWithText("Turning test opening line", substring = true).performClick()
        waitFor("Name this conversation")
        compose.onNodeWithText("Name this conversation").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Eye doctor")
        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assert(hasText("Eye doctor"))
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.load(id)?.title == "Eye doctor" }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun turnThePhone() {
        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()
    }

    @Test
    fun turningThePhoneKeepsTheDeleteAllQuestionOpen() {
        seed("Turning delete all line" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Turning delete all line")
        compose.onNodeWithText("Delete all").performClick()
        compose.onNodeWithText("Delete all saved conversations?").assertExists()
        turnThePhone()
        waitForText("Delete all saved conversations?")
        compose.onNodeWithText("Delete all saved conversations?").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
    }

    @Test
    fun turningThePhoneKeepsTheDeleteOneQuestionOpen() {
        seed("Turning delete one line" to null)
        compose.onNodeWithText("History").performClick()
        waitFor("Turning delete one line")
        compose.onNodeWithText("Turning delete one line", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this conversation?").assertExists()
        turnThePhone()
        waitForText("Delete this conversation?")
        compose.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        waitFor("Turning delete one line")
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
    }

    @Test
    fun turningThePhoneKeepsTheShorterKeepingTimeQuestionOpen() {
        app.settings.change { it.historyRetention = HistoryRetention.Forever }
        val old = seed("Turning retention old line" to null, startedAt = System.currentTimeMillis() / 1_000.0 - 40 * 86_400)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Automatic deletion").performClick()
        compose.onNodeWithText("After a month").performClick()
        compose.onNodeWithText("Delete and continue this way").assertExists()
        turnThePhone()
        waitForText("Delete and continue this way")
        compose.onNodeWithText("Delete and continue this way").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(HistoryRetention.Forever, app.settings.current.value.historyRetention)
        assertNotNull(store.load(old))
    }

    @Test
    fun turningThePhoneKeepsTheShorterKeepingTimeQuestionTrueAboutWhatConfirmingDeletes() {
        app.settings.change { it.historyRetention = HistoryRetention.Forever }
        val longAgo = System.currentTimeMillis() / 1_000.0 - 40 * 86_400
        val oldOne = seed("Rotating retention old one" to null, startedAt = longAgo)
        val oldTwo = seed("Rotating retention old two" to null, startedAt = longAgo - 60)
        val recent = seed("Rotating retention recent line" to null)
        repeat(400) { n -> seed(*Array(20) { "Rotating retention filler $n line $it" to null }) }
        compose.onNodeWithText("History").performClick()
        waitForText("Delete all")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rotating retention filler", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Automatic deletion").performClick()
        compose.onNodeWithText("After a month").performClick()
        compose.onNodeWithText("2 old conversations will be deleted now").assertExists()
        turnThePhone()
        var firstSeen = ""
        compose.waitUntil(10_000) {
            val buttons = compose.onAllNodesWithText("Delete and continue this way").fetchSemanticsNodes()
            if (buttons.isNotEmpty() && firstSeen.isEmpty()) {
                firstSeen = compose.onAllNodesWithText("will be deleted now", substring = true).fetchSemanticsNodes()
                    .joinToString { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() }
                    .ifEmpty { "(no warning text)" }
            }
            buttons.isNotEmpty()
        }
        assertEquals("2 old conversations will be deleted now", firstSeen)
        compose.onNodeWithText("Delete and continue this way").performClick()
        compose.waitUntil(10_000) { store.load(oldOne) == null && store.load(oldTwo) == null }
        assertNotNull(store.load(recent))
        assertEquals(HistoryRetention.Month, app.settings.current.value.historyRetention)
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
    fun aSavedLineSitsOnTheSideItsOwnWordsReadFromWhateverTheAppsLanguage() {
        val spokenBefore = app.settings.current.value.languageCode
        app.settings.change { it.languageCode = "en" }
        try {
            linesOnTheirOwnSides()
        } finally {
            app.settings.change { it.languageCode = spokenBefore }
        }
    }

    private fun linesOnTheirOwnSides() {
        val english = "Side test how did you sleep"
        val hebrew = "בדיקת צד שלום לכולם"
        val digits = "4417 2209"
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 30,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(
                SavedSegment(UUID.randomUUID(), english, null, null, startedAt, true, isStarred = true),
                SavedSegment(UUID.randomUUID(), hebrew, null, null, startedAt + 5, true, isStarred = true),
                SavedSegment(UUID.randomUUID(), digits, null, null, startedAt + 10, true, isStarred = true),
            ),
        )
        store.save(record)
        seeded += record.id
        compose.onNodeWithText("History").performClick()
        waitFor(english)
        compose.onNodeWithText(english, substring = true).performClick()
        compose.waitUntil(5_000) { shownLine(digits) }
        assertSides(english, hebrew, digits)

        compose.onNodeWithText("Back").performClick()
        waitFor(english)
        val total = store.listSummaries().sumOf { it.starredCount }
        compose.onNode(hasText("Starred lines") and hasText("$total")).performClick()
        compose.waitUntil(5_000) { shownLine(digits) }
        assertSides(english, hebrew, digits)
    }

    private fun assertSides(english: String, hebrew: String, digits: String) {
        val left = inkMiddle(english)
        val right = inkMiddle(hebrew)
        val spokenSide = inkMiddle(digits)
        assertTrue("English ink centred at $left of the line", left < 0.4f)
        assertTrue("Hebrew ink centred at $right of the line", right > 0.6f)
        assertTrue("digits said in English centred at $spokenSide of the line", spokenSide < 0.4f)
    }

    private fun inkMiddle(line: String): Float {
        val pixels = compose.onNodeWithContentDescription(line, substring = true).captureToImage().toPixelMap()
        val background = pixels[0, 0]
        val inked = (0 until pixels.width).filter { x ->
            (0 until pixels.height).any { y ->
                val pixel = pixels[x, y]
                maxOf(abs(pixel.red - background.red), abs(pixel.green - background.green), abs(pixel.blue - background.blue)) > 0.3f
            }
        }
        assertTrue("no ink drawn for $line", inked.isNotEmpty())
        return (inked.first() + inked.last()) / 2f / pixels.width
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

    // As on the iPhone, a conversation's row shows how many of its lines are
    // starred, so the ones that matter stand out without opening each.
    @Test
    fun aConversationRowShowsHowManyOfItsLinesAreStarredAndAScreenReaderHearsIt() {
        starredConversation("Starred list test row count", System.currentTimeMillis() / 1_000.0 - 60)
        compose.onNodeWithText("History").performClick()
        waitFor("Opening line of row count")
        compose.onNode(hasClickAction() and hasText("Opening line of row count", substring = true) and hasContentDescription("1 starred line")).assertExists()
        compose.onNodeWithText("★ 1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("1 starred line", useUnmergedTree = true).assertIsDisplayed()
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
        compose.onNodeWithText("Today at ${clockTime(now - 120)}").assertExists()

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
    fun aConversationOpenedAtAStarredLineMarksItForAMomentAndNextStarredGoesOnFromIt() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 300
        val texts = listOf("Arrival test the first star", "Arrival test the second star", "Arrival test the third star")
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 60,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = texts.mapIndexed { index, text -> SavedSegment(UUID.randomUUID(), text, null, null, startedAt + index * 10, true, isStarred = true) },
        )
        store.save(record)
        seeded += record.id
        app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Dark, markUncertainLines = false) }
        compose.onNodeWithText("History").performClick()
        waitFor("Arrival test the first star")
        val total = store.listSummaries().sumOf { it.starredCount }
        compose.onNode(hasText("Starred lines") and hasText("$total")).performClick()
        compose.waitUntil(5_000) { shownLine("Arrival test the second star") }
        compose.onNodeWithContentDescription("Arrival test the second star", substring = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Next starred (2 of 3)").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { highlighted("Arrival test the second star") }
        assertFalse(highlighted("Arrival test the first star"))
        compose.waitUntil(5_000) { !highlighted("Arrival test the second star") }

        compose.onNodeWithText("Next starred (2 of 3)").performClick()
        compose.onNodeWithText("Next starred (3 of 3)").assertExists()
    }

    // Beside the words, where only the line's background shows: the star is yellow too.
    private fun highlighted(text: String): Boolean {
        val image = compose.onNodeWithContentDescription(text, substring = true).captureToImage().toPixelMap()
        val edge = image[image.width - 6, image.height / 2]
        return minOf(edge.red, edge.green) - edge.blue > 0.15f
    }

    @Test
    fun conversationsFromThisDateInEarlierYearsComeFirstNewestFirstThreeAtMost() {
        val now = ZonedDateTime.now()
        assumeFalse(MonthDay.from(now) == MonthDay.of(2, 29))
        fun ago(years: Long, days: Long = 0) = now.minusYears(years).minusDays(days).toEpochSecond().toDouble()
        seed("Anniversary test one year ago" to null, startedAt = ago(1))
        seed("Anniversary test two years ago" to null, startedAt = ago(2))
        seed("Anniversary test three years ago" to null, startedAt = ago(3))
        seed("Anniversary test four years ago" to null, startedAt = ago(4))
        seed("Anniversary test a year and a day ago" to null, startedAt = ago(1, days = 1))
        compose.onNodeWithText("History").performClick()
        waitFor("On this date in earlier years")
        val section = hasAnyAncestor(hasTestTag(ON_THIS_DATE))
        val tops = listOf("one year", "two years", "three years").map {
            compose.onNode(hasText("Anniversary test $it ago", substring = true) and section).getUnclippedBoundsInRoot().top
        }
        assertEquals(tops.sorted(), tops)
        compose.onNode(hasText("Anniversary test four years ago", substring = true) and section).assertDoesNotExist()
        compose.onNode(hasText("Anniversary test a year and a day ago", substring = true) and section).assertDoesNotExist()

        compose.onNode(hasText("Anniversary test two years ago", substring = true) and section).performClick()
        compose.waitUntil(5_000) { shownLine("Anniversary test two years ago") }
        compose.onNodeWithText("Back").performClick()
        waitFor("On this date in earlier years")
        compose.onNodeWithText("Search what was said").performTextInput("Anniversary")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("On this date in earlier years").fetchSemanticsNodes().isEmpty() }
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
        // A line of digits alone sits on the spoken language's side, and the
        // taps below land near the left edge, where an English line starts.
        val spokenBefore = app.settings.current.value.languageCode
        app.settings.change { it.languageCode = "en" }
        try {
            aSavedNumberOpensTheDialer()
        } finally {
            app.settings.change { it.languageCode = spokenBefore }
        }
    }

    private fun aSavedNumberOpensTheDialer() {
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
    fun backFromAConversationFarDownTheListFindsTheListWhereItWas() {
        val now = System.currentTimeMillis() / 1_000.0
        (1..30).forEach { seed("Row $it of the scroll test" to null, startedAt = now - it * 3_600.0) }
        compose.onNodeWithText("History").performClick()
        waitFor("Row 1 of the scroll test")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Row 25 of the scroll test", substring = true))
        compose.onNodeWithText("Row 25 of the scroll test", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Row 25 of the scroll test", substring = true).assertIsDisplayed()
    }

    @Test
    fun savedLinesAndTheSummaryGiveEachSpeakerTheColourTheyHadLive() {
        app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Dark) }
        seed("Colour test good morning" to "Dana", "Colour test how did you sleep" to "Avi", "Colour test fine thanks" to "Dana")
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Colour test good morning", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("Colour test fine thanks") }
        // A saved line is one node to the screen reader, its name inside it
        // with no node of its own, so the name's colour is read from what
        // the line draws.
        val list = compose.onNode(hasScrollToNodeAction())
        for (name in listOf("Dana", "Avi")) {
            val expected = SpeakerColour.of(null, name, light = false)
            // The bar is part of its speaker's row to the screen reader, so
            // the row is scrolled to and the bar found in the unmerged tree.
            list.performScrollToNode(hasText(name) and hasText("%", substring = true))
            val bar = compose.onNodeWithTag("$SPEAKER_SHARE$name", useUnmergedTree = true).captureToImage().toPixelMap()
            assertTrue("$name's share of the talk not drawn in their colour", drawsIn(bar, expected))
            list.performScrollToNode(hasContentDescription("$name: Colour test", substring = true))
            val line = compose.onAllNodesWithContentDescription("$name: Colour test", substring = true)[0].captureToImage().toPixelMap()
            assertTrue("$name's lines not in their colour", drawsIn(line, expected))
        }
    }

    private fun drawsIn(pixels: PixelMap, colour: Color) = (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> near(pixels[x, y], colour) } }

    private fun near(a: Color, b: Color) = abs(a.red - b.red) < 0.03f && abs(a.green - b.green) < 0.03f && abs(a.blue - b.blue) < 0.03f

    @Test
    fun backFromAStarredLineFarDownFindsTheStarredListWhereItWas() {
        val now = System.currentTimeMillis() / 1_000.0
        (1..30).forEach { starredConversation("Starred place test line $it of thirty", now - it * 3_600.0) }
        compose.onNodeWithText("History").performClick()
        waitFor("Starred lines")
        val total = store.listSummaries().sumOf { it.starredCount }
        compose.onNode(hasText("Starred lines") and hasText("$total")).performClick()
        compose.waitUntil(5_000) { shownLine("Starred place test line 1 of thirty") }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Starred place test line 25 of thirty", substring = true))
        compose.onNodeWithContentDescription("Starred place test line 25 of thirty", substring = true).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { shownLine("Marked as important. Starred place test line 25 of thirty") }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("Starred place test line 25 of thirty", substring = true).assertIsDisplayed()
    }

    @Test
    fun anOpenedConversationIsHeadedByItsDayAndTimeAndEndsWithWhatTranscribedIt() {
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        seed("Heading test opening line" to null, "Heading test last line" to null, startedAt = startedAt)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Heading test opening line", substring = true).performClick()
        compose.waitUntil(5_000) { shownLine("Heading test last line") }
        compose.onNodeWithText("Today at ${clockTime(startedAt)}").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Home computer"))
        compose.onNodeWithText("Home computer").assertIsDisplayed()
        val heading = compose.onNodeWithText("Today at ${clockTime(startedAt)}").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithContentDescription("Heading test opening line", substring = true).getUnclippedBoundsInRoot()
        assertTrue("the heading is not above the lines", heading.bottom <= first.top)
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
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Device history from long ago", substring = true).fetchSemanticsNodes().isEmpty() }
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
