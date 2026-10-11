package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.HistoryDay
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.StarredLine
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the History screen reads and changes: the saved conversations, and the one still being captioned. */
interface SavedConversations {
    fun list(): List<TranscriptSessionSummary>

    /** The conversations where every word of [query] was said, each previewed by the line that matched. */
    fun search(query: String): List<TranscriptSessionSummary>

    fun load(id: UUID): TranscriptSessionRecord?

    /** Every starred line of every saved conversation, newest conversation first. */
    fun starredLines(): List<StarredLine>

    fun sizeOnDisk(): Long

    val lastSaveFailure: String?

    fun delete(id: UUID)

    /** Deletes every saved conversation, the one still being captioned too. */
    fun deleteAll()

    /** Names a conversation, or takes its name away with a blank one. */
    fun rename(id: UUID, title: String)

    /** Stars a saved line or takes its star away: its new state, or null when it is gone. */
    fun toggleStar(sessionID: UUID, segmentID: UUID): Boolean?

    fun switchSaving(on: Boolean)

    suspend fun keepFor(retention: HistoryRetention)

    fun opened(id: UUID)

    fun doneWith(id: UUID)

    fun isCurrent(id: UUID): Boolean
}

/**
 * Past conversations by day, as on the iPhone: search what was said, open one
 * to read it back, or delete it. Everything stays on the phone.
 */
@Composable
fun HistoryScreen(
    conversations: SavedConversations,
    saving: Boolean,
    retention: HistoryRetention,
    marksUncertain: Boolean,
    emphasizeNumbers: Boolean,
    onClose: () -> Unit,
) {
    var openID by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val open = openID
    if (open != null) {
        ConversationScreen(conversations, UUID.fromString(open), query, marksUncertain, emphasizeNumbers, onClose = { openID = null })
        return
    }
    var showingStarred by rememberSaveable { mutableStateOf(false) }
    if (showingStarred) {
        StarredLinesScreen(conversations, marksUncertain, emphasizeNumbers, onClose = { showingStarred = false })
        return
    }
    BackHandler(onBack = onClose)
    var reloads by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<HistoryList?>(null) }
    val searching = query.isNotBlank()
    // Every search reads every saved conversation, so it waits for a pause
    // in typing and runs off the main thread; a newer search cancels this one.
    LaunchedEffect(reloads, query) {
        if (searching) delay(250)
        loaded = withContext(Dispatchers.IO) {
            val summaries = if (searching) conversations.search(query) else conversations.list()
            HistoryList(
                summaries = summaries,
                days = HistoryDays.grouped(summaries, now = System.currentTimeMillis() / 1_000.0, utcOffsetSeconds = ::localOffset),
                size = ByteSize.text(conversations.sizeOnDisk()),
            )
        }
    }
    val scope = rememberCoroutineScope()
    var pendingRetention by remember { mutableStateOf<HistoryRetention?>(null) }
    var pendingDeletion by remember { mutableStateOf<TranscriptSessionSummary?>(null) }
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val deleting = { delete: () -> Unit ->
        try {
            delete()
        } catch (failure: Exception) {
            deleteError = failure.message ?: failure.toString()
        }
        reloads += 1
    }
    val keepFor = { choice: HistoryRetention ->
        scope.launch {
            conversations.keepFor(choice)
            reloads += 1
        }
        Unit
    }
    Screen(
        title = tr("היסטוריה", "History"),
        close = tr("סגירה", "Close"),
        onClose = onClose,
        actions = {
            TextButton(onClick = { confirmingDeleteAll = true }, enabled = loaded?.let { searching || it.summaries.isNotEmpty() } == true) {
                Text(tr("מחיקת הכול", "Delete all"))
            }
        },
    ) {
        val shown = loaded
        if (shown == null) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "search") {
                    SearchField(query, onChange = { query = it })
                }
                if (!searching) item(key = "saving") {
                    SavingSection(
                        saving = saving,
                        retention = retention,
                        size = shown.size,
                        failure = conversations.lastSaveFailure,
                        onSaving = conversations::switchSaving,
                        onRetention = { choice ->
                            if (choice.expiringCount(shown.summaries, System.currentTimeMillis() / 1_000.0) != 0) pendingRetention = choice else keepFor(choice)
                        },
                    )
                }
                val stars = shown.summaries.sumOf { it.starredCount }
                if (!searching && stars > 0) item(key = "starred") {
                    StarredLinesRow(stars, onOpen = { showingStarred = true })
                }
                if (shown.days.isEmpty()) {
                    item(key = "empty") {
                        Column {
                            Heading(tr("שיחות", "Conversations"))
                            Text(
                                HistoryTexts.emptyMessage(query, saving),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                for (day in shown.days) {
                    item(key = "day-${day.id}") { Heading(day.title) }
                    items(day.sessions, key = { it.id.toString() }) { session ->
                        ConversationRowView(ConversationRow.of(session), onOpen = { openID = session.id.toString() }, onDelete = { pendingDeletion = session })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    pendingRetention?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text(HistoryTexts.expiryWarning(choice.expiringCount(loaded?.summaries, System.currentTimeMillis() / 1_000.0) ?: 0)) },
            text = { Text(tr("שיחות עם שורה מסומנת או עם שם לא יימחקו.", "Conversations with a starred line or a name won’t be deleted.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRetention = null
                        keepFor(choice)
                    },
                ) { Text(tr("למחוק ולהמשיך כך", "Delete and continue this way"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    pendingDeletion?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(tr("למחוק את השיחה הזו?", "Delete this conversation?")) },
            text = { Text(session.title ?: CaptionLayout.directed(session.preview)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        deleting { conversations.delete(session.id) }
                    },
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (confirmingDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text(tr("למחוק את כל השיחות השמורות?", "Delete all saved conversations?")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDeleteAll = false
                        deleting { conversations.deleteAll() }
                    },
                ) { Text(tr("מחיקת הכול", "Delete all"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDeleteAll = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    deleteError?.let { error -> DeletionFailed(error, onClose = { deleteError = null }) }
}

private class HistoryList(val summaries: List<TranscriptSessionSummary>, val days: List<HistoryDay>, val size: String)

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onChange,
            placeholder = { Text(tr("חיפוש במה שנאמר", "Search what was said")) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text(tr("ניקוי", "Clear")) }
    }
}

/** Right above the list, as on the iPhone: whether to save, for how long, and where it is kept. */
@Composable
private fun SavingSection(
    saving: Boolean,
    retention: HistoryRetention,
    size: String,
    failure: String?,
    onSaving: (Boolean) -> Unit,
    onRetention: (HistoryRetention) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (saving && failure != null) {
            Text(
                tr(
                    "השמירה האחרונה של שיחה נכשלה, כנראה כי אין מקום פנוי בטלפון. מה שנאמר מאז אולי לא נשמר.",
                    "The last conversation save failed, probably because the phone is out of space. What was said since then may not have been saved.",
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = saving, role = Role.Switch, onValueChange = onSaving)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("לשמור שיחות", "Save conversations"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = saving, onCheckedChange = null)
        }
        var choosing by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList) { choosing = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("מחיקה אוטומטית", "Automatic deletion"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(HistoryTexts.name(retention), color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                HistoryRetention.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(HistoryTexts.name(choice)) },
                        onClick = {
                            choosing = false
                            if (choice != retention) onRetention(choice)
                        },
                    )
                }
            }
        }
        Text(
            HistoryTexts.savingFooter(saving, size, retention),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConversationRowView(row: ConversationRow, onOpen: () -> Unit, onDelete: () -> Unit) {
    val delete = tr("מחיקה", "Delete")
    var menu by remember { mutableStateOf(false) }
    Box {
        ConversationRowContent(row, onOpen, onHold = { menu = true }, actions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(delete) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun ConversationRowContent(row: ConversationRow, onOpen: () -> Unit, onHold: () -> Unit, actions: List<CustomAccessibilityAction>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onHold(onHold)
            .clickable(onClick = onOpen)
            .semantics { customActions = actions }
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        row.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.time,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.title == null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            row.duration?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        row.speakers?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.lines, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class OpenConversation(
    val record: TranscriptSessionRecord?,
    val lines: List<SavedLine>,
    val matches: List<UUID>,
    val numbers: NumbersMentioned?,
    val starred: List<UUID>,
)

private fun opened(record: TranscriptSessionRecord?, query: String, marksUncertain: Boolean) = OpenConversation(
    record = record,
    lines = record?.let { SavedLine.of(it, markUncertain = marksUncertain) } ?: emptyList(),
    matches = if (record == null || query.isBlank()) emptyList() else TranscriptHistoryStore.matchingSegmentIDs(record, query),
    numbers = record?.takeIf { query.isBlank() }?.let(NumbersMentioned::of),
    starred = record?.takeIf { query.isBlank() }?.segments?.filter { it.isStarred }?.map { it.id } ?: emptyList(),
)

/**
 * One saved conversation read back; the one still being captioned is read
 * again every twenty seconds, as it keeps growing on disk. Opened from a
 * search, it opens at the first line that matched, the matching lines are
 * marked, and a button steps through them, as on the iPhone. Share sends
 * it as text, with the lines that may have been misheard saying so in
 * words while the caption screen marks them. A name given here shows in
 * the list and is found by search. With no search, the button steps
 * through the starred lines instead. Opened from a caption line held on
 * the caption screen, it opens at that line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationScreen(
    conversations: SavedConversations,
    id: UUID,
    query: String,
    marksUncertain: Boolean,
    emphasizeNumbers: Boolean,
    onClose: () -> Unit,
    line: UUID? = null,
) {
    BackHandler(onBack = onClose)
    DisposableEffect(id) {
        conversations.opened(id)
        onDispose { conversations.doneWith(id) }
    }
    var loaded by remember { mutableStateOf<OpenConversation?>(null) }
    val list = rememberLazyListState()
    var current by remember { mutableIntStateOf(0) }
    var starAt by remember { mutableIntStateOf(0) }
    var jumpedToStar by remember { mutableStateOf(false) }
    // Read again, the button stays on the line it showed while that is
    // still one of them; otherwise its next tap starts from the first.
    fun show(now: OpenConversation) {
        val before = loaded
        current = SearchMatches.position(current, before?.matches ?: emptyList(), now.matches) ?: 0
        val star = SearchMatches.position(starAt, before?.starred ?: emptyList(), now.starred)
        starAt = star ?: 0
        if (star == null) jumpedToStar = false
        loaded = now
    }
    LaunchedEffect(id, marksUncertain) {
        var jumped = false
        while (true) {
            val now = withContext(Dispatchers.IO) { opened(conversations.load(id), query, marksUncertain) }
            show(now)
            if (!jumped && now.matches.isNotEmpty()) {
                jumped = true
                withFrameNanos {}
                list.scrollToItem(now.lines.indexOfFirst { it.id == now.matches.first() }.coerceAtLeast(0))
            }
            val held = now.lines.indexOfFirst { it.id == line }
            if (!jumped && held >= 0) {
                jumped = true
                withFrameNanos {}
                list.scrollToItem(held)
            }
            if (!conversations.isCurrent(id)) break
            delay(20_000)
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    val record = loaded?.record
    Screen(title = record?.title ?: tr("שיחה", "Conversation"), close = tr("חזרה", "Back"), onClose = onClose) {
        val shown = loaded
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            shown.record == null -> Text(tr("השיחה לא נמצאה", "Conversation not found"))
            else -> {
                val numbers = shown.numbers?.takeIf { it.listed.isNotEmpty() }
                val header = if (numbers != null) 1 else 0
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { scope.launch { context.startActivity(withContext(Dispatchers.IO) { shareSheet(context, shown.record, marksUncertain) }) } }) {
                        Text(tr("שיתוף", "Share"))
                    }
                    TextButton(onClick = { naming = shown.record.title ?: "" }) {
                        Text(tr("מתן שם לשיחה", "Name this conversation"))
                    }
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                    if (shown.matches.size > 1) {
                        TextButton(
                            onClick = {
                                current = (current + 1) % shown.matches.size
                                val target = shown.lines.indexOfFirst { it.id == shown.matches[current] }
                                if (target >= 0) scope.launch { list.scrollToItem(target) }
                            },
                        ) { Text(SearchMatches.nextTitle(current, shown.matches.size)) }
                    }
                    if (shown.starred.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                starAt = if (jumpedToStar) (starAt + 1) % shown.starred.size else 0
                                jumpedToStar = true
                                val target = shown.lines.indexOfFirst { it.id == shown.starred[starAt] }
                                if (target >= 0) scope.launch { list.scrollToItem(header + target) }
                            },
                        ) { Text(SearchMatches.starredTitle(starAt, shown.starred.size, jumpedToStar)) }
                    }
                }
                val matched = shown.matches.toSet()
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (numbers != null) {
                        item(key = "numbers") {
                            NumbersSection(numbers) { said ->
                                val target = shown.lines.indexOfFirst { it.id == said.id }
                                if (target >= 0) scope.launch { list.scrollToItem(header + target) }
                            }
                        }
                    }
                    items(shown.lines, key = { it.id.toString() }) { line ->
                        SavedLineView(line, isMatch = line.id in matched, emphasizeNumbers = emphasizeNumbers) {
                            scope.launch {
                                conversations.toggleStar(id, line.id)
                                show(withContext(Dispatchers.IO) { opened(conversations.load(id), query, marksUncertain) })
                            }
                        }
                    }
                }
            }
        }
    }
    naming?.let { draft ->
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(tr("שם לשיחה", "Conversation name")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("השם יופיע ברשימת השיחות, ואפשר יהיה לחפש לפיו.", "The name will appear in the conversations list, and you’ll be able to search by it."))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { naming = it },
                        placeholder = { Text(tr("למשל: ביקור אצל הרופא", "For example: doctor’s visit")) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = null
                        scope.launch {
                            show(
                                withContext(Dispatchers.IO) {
                                    conversations.rename(id, draft)
                                    opened(conversations.load(id), query, marksUncertain)
                                },
                            )
                        }
                    },
                ) { Text(tr("שמירה", "Save")) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(tr("למחוק את השיחה הזו?", "Delete this conversation?")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        try {
                            conversations.delete(id)
                            onClose()
                        } catch (failure: Exception) {
                            deleteError = failure.message ?: failure.toString()
                        }
                    },
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    deleteError?.let { error -> DeletionFailed(error, onClose = { deleteError = null }) }
}

@Composable
private fun DeletionFailed(error: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("המחיקה נכשלה", "Deletion failed")) },
        text = {
            Text(
                tr(
                    "מה שלא נמחק עדיין שמור בטלפון. אפשר לנסות שוב.\n%1",
                    "What wasn’t deleted is still saved on the phone. You can try again.\n%1",
                    listOf(error),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("סגירה", "Close")) } },
    )
}

/** Above the conversation, as on the iPhone: each line with a number in it, a tap away from where it was said. */
@Composable
private fun NumbersSection(numbers: NumbersMentioned, onJump: (SavedSegment) -> Unit) {
    val jump = tr("מעבר לשורה בשיחה", "Jump to this line in the conversation")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading(tr("מספרים שנאמרו", "Numbers mentioned"))
        for (said in numbers.listed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = jump) { onJump(said) }
                    .padding(vertical = 6.dp),
            ) {
                said.speakerName?.takeUnless(TranscriptSessionSummary::isGenericLabel)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    captionText(CaptionLayout.directed(said.text), emphasizeNumbers = true, numberColor = null),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                )
            }
        }
        numbers.moreText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            tr("שעות, כמויות ומספרי טלפון מהשיחה. נגיעה בשורה מובילה אליה.", "Times, amounts, and phone numbers from the conversation. Tap a line to jump to it."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SavedLineView(line: SavedLine, isMatch: Boolean, emphasizeNumbers: Boolean, onStar: () -> Unit) {
    val found = tr("מכילה את מה שחיפשת", "Contains what you searched for")
    val star = if (line.starred) tr("הסרת הסימון", "Remove the star") else tr("סימון כחשובה", "Mark as important")
    val copy = tr("העתקה", "Copy")
    val context = LocalContext.current
    // A phone number or an address said, to paste somewhere else.
    val copyLine = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(copy, line.copied)) }
    val uris = LocalUriHandler.current
    val calls = line.phones.map { phone ->
        CustomAccessibilityAction(tr("להתקשר ל־%1", "Call %1", listOf(phone.dialable))) { uris.openUri(phone.url); true }
    }
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isMatch) SEARCH_MATCH else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = if (isMatch) 6.dp else 0.dp, vertical = if (isMatch) 4.dp else 0.dp)
            .clearAndSetSemantics {
                contentDescription = line.spoken
                if (isMatch) stateDescription = found
                customActions = listOf(
                    CustomAccessibilityAction(star) { onStar(); true },
                    CustomAccessibilityAction(copy) { copyLine(); true },
                ) + calls
            }
            .onHold { menu = true },
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(star) }, onClick = { menu = false; onStar() })
            DropdownMenuItem(text = { Text(copy) }, onClick = { menu = false; copyLine() })
        }
        line.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        line.speaker?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Row {
            if (line.starred) Text("★", color = starColour(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 6.dp))
            if (line.unsure) UnsureMark(MaterialTheme.typography.bodyLarge.fontSize.value.toDouble(), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(captionText(line.text, emphasizeNumbers, numberColor = null, linkingPhoneNumbers = true), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A finger held still on the line. Watched before the text inside sees the
 * touch: a phone number there takes the touch as its own, so a hold that
 * began on it never opened the menu, and lifting the finger dialed it.
 * Once held, the rest of the touch is kept from the number.
 */
internal fun Modifier.onHold(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (ended == null) {
            action()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun starColour() = starColour(light = MaterialTheme.colorScheme.background.luminance() > 0.5f)

// The iPhone's yellow star, deepened on a light screen to stay visible.
internal fun starColour(light: Boolean) = if (light) Color(red = 0.6f, green = 0.42f, blue = 0f) else Color(red = 1f, green = 0.84f, blue = 0.04f)

@Composable
internal fun Screen(title: String, close: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                actions()
                TextButton(onClick = onClose) { Text(close) }
            }
            content()
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

// The iPhone's mark for a line a search found: its yellow at 30%.
private val SEARCH_MATCH = Color(red = 1f, green = 0.84f, blue = 0.04f, alpha = 0.3f)

/**
 * Android hands the share sheet everything in one message of about a
 * megabyte: a 3,000-line conversation sent as text never reached it, and
 * 8,000 lines closed Ozen. A conversation longer than a chat message takes
 * goes as a text file instead, the last one shared replacing the one before.
 */
private const val LONGEST_SHARED_TEXT = 50_000

private fun shareSheet(context: Context, record: TranscriptSessionRecord, marksUncertain: Boolean): Intent {
    val text = TranscriptHistoryStore.exportText(record, ::localOffset, marksUncertain)
    val subject = tr("שיחה מאוזן", "Conversation from Ozen")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
    if (text.length <= LONGEST_SHARED_TEXT) {
        send.putExtra(Intent.EXTRA_TEXT, text)
    } else {
        val folder = File(context.cacheDir, CaptionSession.SHARED_FOLDER)
        folder.deleteRecursively()
        folder.mkdirs()
        val file = File(folder, "$subject.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        send.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
    }
    return Intent.createChooser(send, null)
}

internal fun localOffset(at: Double): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli((at * 1_000).toLong())).totalSeconds
