package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.HistoryDay
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
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

    fun sizeOnDisk(): Long

    val lastSaveFailure: String?

    fun delete(id: UUID)

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
fun HistoryScreen(conversations: SavedConversations, saving: Boolean, retention: HistoryRetention, onClose: () -> Unit) {
    var openID by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val open = openID
    if (open != null) {
        ConversationScreen(conversations, UUID.fromString(open), query, onClose = { openID = null })
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
    val keepFor = { choice: HistoryRetention ->
        scope.launch {
            conversations.keepFor(choice)
            reloads += 1
        }
        Unit
    }
    Screen(title = tr("היסטוריה", "History"), close = tr("סגירה", "Close"), onClose = onClose) {
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
                        ConversationRowView(ConversationRow.of(session)) { openID = session.id.toString() }
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
private fun ConversationRowView(row: ConversationRow, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
)

/**
 * One saved conversation read back; the one still being captioned is read
 * again every twenty seconds, as it keeps growing on disk. Opened from a
 * search, it opens at the first line that matched, the matching lines are
 * marked, and a button steps through them, as on the iPhone.
 */
@Composable
private fun ConversationScreen(conversations: SavedConversations, id: UUID, query: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    DisposableEffect(id) {
        conversations.opened(id)
        onDispose { conversations.doneWith(id) }
    }
    var loaded by remember { mutableStateOf<OpenConversation?>(null) }
    val list = rememberLazyListState()
    var current by remember { mutableIntStateOf(0) }
    LaunchedEffect(id) {
        var jumped = false
        while (true) {
            val before = loaded?.matches ?: emptyList()
            val now = withContext(Dispatchers.IO) {
                val record = conversations.load(id)
                OpenConversation(
                    record = record,
                    lines = record?.let { SavedLine.of(it) } ?: emptyList(),
                    matches = if (record == null || query.isBlank()) emptyList() else TranscriptHistoryStore.matchingSegmentIDs(record, query),
                    numbers = record?.takeIf { query.isBlank() }?.let(NumbersMentioned::of),
                )
            }
            current = SearchMatches.position(current, before, now.matches) ?: 0
            loaded = now
            if (!jumped && now.matches.isNotEmpty()) {
                jumped = true
                withFrameNanos {}
                list.scrollToItem(now.lines.indexOfFirst { it.id == now.matches.first() }.coerceAtLeast(0))
            }
            if (!conversations.isCurrent(id)) break
            delay(20_000)
        }
    }
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val record = loaded?.record
    Screen(title = record?.title ?: tr("שיחה", "Conversation"), close = tr("חזרה", "Back"), onClose = onClose) {
        val shown = loaded
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            shown.record == null -> Text(tr("השיחה לא נמצאה", "Conversation not found"))
            else -> {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (shown.matches.size > 1) {
                        TextButton(
                            onClick = {
                                current = (current + 1) % shown.matches.size
                                val target = shown.lines.indexOfFirst { it.id == shown.matches[current] }
                                if (target >= 0) scope.launch { list.scrollToItem(target) }
                            },
                        ) { Text(SearchMatches.nextTitle(current, shown.matches.size)) }
                    }
                }
                val matched = shown.matches.toSet()
                val numbers = shown.numbers?.takeIf { it.listed.isNotEmpty() }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (numbers != null) {
                        item(key = "numbers") {
                            NumbersSection(numbers) { said ->
                                val target = shown.lines.indexOfFirst { it.id == said.id }
                                if (target >= 0) scope.launch { list.scrollToItem(1 + target) }
                            }
                        }
                    }
                    items(shown.lines, key = { it.id.toString() }) { line -> SavedLineView(line, isMatch = line.id in matched) }
                }
            }
        }
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
    deleteError?.let { error ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
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
            confirmButton = { TextButton(onClick = { deleteError = null }) { Text(tr("סגירה", "Close")) } },
        )
    }
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
private fun SavedLineView(line: SavedLine, isMatch: Boolean) {
    val found = tr("מכילה את מה שחיפשת", "Contains what you searched for")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isMatch) SEARCH_MATCH else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = if (isMatch) 6.dp else 0.dp, vertical = if (isMatch) 4.dp else 0.dp)
            .clearAndSetSemantics {
                contentDescription = line.spoken
                if (isMatch) stateDescription = found
            },
    ) {
        line.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        line.speaker?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Text(line.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Screen(title: String, close: String, onClose: () -> Unit, content: @Composable () -> Unit) {
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
                TextButton(onClick = onClose) { Text(close) }
            }
            content()
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

// The iPhone's mark for a line a search found: its yellow at 30%.
private val SEARCH_MATCH = Color(red = 1f, green = 0.84f, blue = 0.04f, alpha = 0.3f)

private fun localOffset(at: Double): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli((at * 1_000).toLong())).totalSeconds
