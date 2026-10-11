package com.arbelonson.ozen

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.util.UUID
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

    @After
    fun restore() {
        seeded.forEach(store::delete)
        app.settings.change {
            it.saveHistory = savingBefore
            it.historyRetention = retentionBefore
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

        compose.onNodeWithText("Automatic deletion").performClick()
        compose.onNodeWithText("After a month").performClick()
        compose.onNodeWithText("Delete and continue this way").performClick()
        compose.waitUntil(5_000) { store.load(old) == null }
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
