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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.OnThisDay
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.StarredLine
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.EmptyState
import com.arbelonson.ozen.ui.GroupFootnote
import com.arbelonson.ozen.ui.OzenScreen
import com.arbelonson.ozen.ui.ProgressState
import com.arbelonson.ozen.ui.SectionHeader
import com.arbelonson.ozen.ui.SettingRow
import com.arbelonson.ozen.ui.SettingSwitchRow
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.TrailingValue
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
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
    languageCode: String = "he",
) {
    var openID by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var speakerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept while a conversation or the starred lines are open, so Back finds
    // the list as she left it, as on the iPhone, not reloading from the top.
    var loaded by remember { mutableStateOf<HistoryList?>(null) }
    val place = rememberLazyListState()
    val open = openID
    if (open != null) {
        ConversationScreen(conversations, UUID.fromString(open), query, marksUncertain, emphasizeNumbers, onClose = { openID = null }, languageCode = languageCode)
        return
    }
    var showingStarred by rememberSaveable { mutableStateOf(false) }
    if (showingStarred) {
        StarredLinesScreen(conversations, marksUncertain, emphasizeNumbers, onClose = { showingStarred = false }, languageCode = languageCode)
        return
    }
    BackHandler(onBack = onClose)
    var reloads by remember { mutableIntStateOf(0) }
    val searching = query.isNotBlank()
    // Every search reads every saved conversation, so it waits for a pause
    // in typing and runs off the main thread; a newer search cancels this one.
    LaunchedEffect(reloads, query) {
        if (searching) delay(250)
        val list = withContext(Dispatchers.IO) {
            val summaries = if (searching) conversations.search(query) else conversations.list()
            val now = System.currentTimeMillis() / 1_000.0
            HistoryList(
                summaries = summaries,
                now = now,
                size = ByteSize.text(conversations.sizeOnDisk()),
                onThisDate = if (searching) emptyList() else OnThisDay.matches(summaries, now, ::localOffset).take(3),
            )
        }
        loaded = list
        speakerFilter = TranscriptSessionSummary.speakerFilter(speakerFilter, keptAfterLoading = list.summaries, everyConversation = !searching)
    }
    val scope = rememberCoroutineScope()
    var pendingRetentionValue by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletionID by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDeleteAll by rememberSaveable { mutableStateOf(false) }
    var deleteError by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRetention = pendingRetentionValue?.let(HistoryRetention::fromRawValue)
    val pendingDeletion = pendingDeletionID?.let { id -> loaded?.summaries?.firstOrNull { it.id.toString() == id } }
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
            TextButton(onClick = { confirmingDeleteAll = true }, enabled = loaded?.summaries?.isNotEmpty() == true) {
                Text(tr("מחיקת הכול", "Delete all"), color = if (loaded?.summaries?.isNotEmpty() == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        val shown = loaded
        if (shown == null) {
            LoadingNote()
        } else {
            val chosen = speakerFilter
            val days = remember(shown, chosen) {
                val withChosen = if (chosen == null) shown.summaries else shown.summaries.filter { chosen in it.speakerNames }
                HistoryDays.grouped(withChosen, now = shown.now, utcOffsetSeconds = ::localOffset)
            }
            val names = remember(shown, chosen) {
                val top = TranscriptSessionSummary.topSpeakerNames(shown.summaries)
                if (chosen == null || chosen in top) top else listOf(chosen) + top
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                state = place,
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") {
                    SearchField(query, onChange = { query = it })
                }
                // A chosen name keeps filtering while she searches, so its
                // chip stays too: hidden, it would drop every other
                // conversation from the results with no way to clear it.
                if ((!searching || chosen != null) && names.isNotEmpty()) item(key = "speakers") {
                    SpeakerFilterChips(names, chosen, onChoose = { name -> speakerFilter = if (name == chosen) null else name })
                }
                if (!searching) item(key = "saving") {
                    SavingSection(
                        saving = saving,
                        retention = retention,
                        size = shown.size,
                        failure = conversations.lastSaveFailure,
                        onSaving = conversations::switchSaving,
                        onRetention = { choice ->
                            if (choice.expiringCount(shown.summaries, System.currentTimeMillis() / 1_000.0) != 0) pendingRetentionValue = choice.rawValue else keepFor(choice)
                        },
                    )
                }
                val stars = shown.summaries.sumOf { it.starredCount }
                if (!searching && stars > 0) item(key = "starred") {
                    StarredLinesRow(stars, onOpen = { showingStarred = true })
                }
                if (shown.onThisDate.isNotEmpty()) item(key = "on-this-date") {
                    Column(modifier = Modifier.testTag(ON_THIS_DATE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(tr("בתאריך הזה בשנים קודמות", "On this date in earlier years"))
                        for (session in shown.onThisDate) {
                            ConversationRowView(ConversationRow.of(session), onOpen = { openID = session.id.toString() }, onDelete = { pendingDeletionID = session.id.toString() })
                        }
                    }
                }
                if (days.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = if (searching) Icons.Rounded.SearchOff else Icons.Rounded.History,
                            title = tr("שיחות", "Conversations"),
                            body = HistoryTexts.emptyMessage(query, saving, chosen),
                        )
                    }
                }
                for (day in days) {
                    item(key = "day-${day.id}") { SectionHeader(day.title) }
                    items(day.sessions, key = { it.id.toString() }) { session ->
                        // A deleted conversation fades and the rest close up over it.
                        Box(Modifier.animateItem()) {
                            ConversationRowView(ConversationRow.of(session), onOpen = { openID = session.id.toString() }, onDelete = { pendingDeletionID = session.id.toString() })
                        }
                    }
                }
            }
        }
    }
    pendingRetention?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingRetentionValue = null },
            title = { Text(HistoryTexts.expiryWarning(choice.expiringCount(loaded?.summaries, System.currentTimeMillis() / 1_000.0) ?: 0)) },
            text = { Text(tr("שיחות עם שורה מסומנת או עם שם לא יימחקו.", "Conversations with a starred line or a name won’t be deleted.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRetentionValue = null
                        keepFor(choice)
                    },
                ) { Text(tr("למחוק ולהמשיך כך", "Delete and continue this way"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRetentionValue = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    pendingDeletion?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeletionID = null },
            title = { Text(tr("למחוק את השיחה הזו?", "Delete this conversation?")) },
            text = { Text(session.title ?: CaptionLayout.directed(session.preview)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletionID = null
                        deleting { conversations.delete(session.id) }
                    },
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeletionID = null }) { Text(tr("ביטול", "Cancel")) } },
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

private class HistoryList(
    val summaries: List<TranscriptSessionSummary>,
    val now: Double,
    val size: String,
    val onThisDate: List<TranscriptSessionSummary>,
)

/** The people most often in these conversations; a tap shows only the conversations with that person, a second tap everyone again. */
@Composable
private fun SpeakerFilterChips(names: List<String>, chosen: String?, onChoose: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (name in names) {
            FilterChip(
                selected = name == chosen,
                onClick = { onChoose(name) },
                label = { Text(name) },
                shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = if (name == chosen) { { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                // The chip itself is the tap area, so it is a full 48 dp tall, as the iPhone's 44 points.
                modifier = Modifier.testTag("speakerChip:$name").heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * The iPhone's "On this date in earlier years": a year-old conversation
 * with the same person, surfaced without her having to remember it
 * happened. Its rows are also listed under their own day further down,
 * which this tag tells apart.
 */
internal const val ON_THIS_DATE = "onThisDate"
internal const val SPEAKER_SHARE = "speakerShare:"

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(tr("חיפוש במה שנאמר", "Search what was said")) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { onChange("") }) { Text(tr("ניקוי", "Clear")) } }
        } else {
            null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saving && failure != null) {
            Text(
                tr(
                    "השמירה האחרונה של שיחה נכשלה, כנראה כי אין מקום פנוי בטלפון. מה שנאמר מאז אולי לא נשמר.",
                    "The last conversation save failed, probably because the phone is out of space. What was said since then may not have been saved.",
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        var choosing by remember { mutableStateOf(false) }
        SettingsGroup {
            item { SettingSwitchRow(tr("לשמור שיחות", "Save conversations"), checked = saving, onCheckedChange = onSaving) }
            item {
                Box {
                    SettingRow(
                        tr("מחיקה אוטומטית", "Automatic deletion"),
                        onClick = { choosing = true },
                        trailing = { TrailingValue(HistoryTexts.name(retention)) },
                    )
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
            }
        }
        GroupFootnote(HistoryTexts.savingFooter(saving, size, retention))
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onHold(onHold)
                .clickable(onClick = onOpen)
                .semantics { customActions = actions }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (row.title == null) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                row.duration?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            row.speakers?.let { names ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(names, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            // As on the iPhone, the starred lines' count beside the line count,
            // heard as words rather than a star symbol and a number.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.lines, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.starredSpoken?.let { spoken ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                    ) {
                        Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = starColour())
                        Text("${row.starred}", style = MaterialTheme.typography.labelMedium, color = starColour())
                    }
                }
            }
        }
    }
}

private class OpenConversation(
    val record: TranscriptSessionRecord?,
    val lines: List<SavedLine>,
    val matches: List<UUID>,
    val numbers: NumbersMentioned?,
    val starred: List<UUID>,
    val stats: ConversationStats?,
) {
    /** The name, the summary and the numbers come first in the list, before the lines a jump is aimed at. */
    val headerItems: Int get() = (if (record?.title != null) 1 else 0) + (if (stats != null) 1 else 0) + (if (numbers?.listed?.isNotEmpty() == true) 1 else 0) + 1
}

private fun opened(record: TranscriptSessionRecord?, query: String, marksUncertain: Boolean, languageCode: String) = OpenConversation(
    record = record,
    lines = record?.let { SavedLine.of(it, markUncertain = marksUncertain, languageCode = languageCode) } ?: emptyList(),
    matches = if (record == null || query.isBlank()) emptyList() else TranscriptHistoryStore.matchingSegmentIDs(record, query),
    numbers = record?.takeIf { query.isBlank() }?.let { NumbersMentioned.of(it, usesDecimalComma(languageCode)) },
    starred = record?.takeIf { query.isBlank() }?.segments?.filter { it.isStarred }?.map { it.id } ?: emptyList(),
    stats = record?.let { ConversationStats.compute(it) }?.takeIf { it.totalWords > 0 },
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
    languageCode: String = "he",
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
    var arrived by remember { mutableStateOf<UUID?>(null) }
    val marking = rememberCoroutineScope()
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
            val now = withContext(Dispatchers.IO) { opened(conversations.load(id), query, marksUncertain, languageCode) }
            show(now)
            if (!jumped && now.matches.isNotEmpty()) {
                jumped = true
                withFrameNanos {}
                list.scrollToItem(now.headerItems + now.lines.indexOfFirst { it.id == now.matches.first() }.coerceAtLeast(0))
            }
            val held = now.lines.indexOfFirst { it.id == line }
            if (!jumped && held >= 0) {
                jumped = true
                // As on the iPhone: "next starred" goes on from the line she
                // came for, which is marked for a moment so she sees it.
                now.starred.indexOf(line).takeIf { it >= 0 }?.let {
                    starAt = it
                    jumpedToStar = true
                }
                withFrameNanos {}
                list.scrollToItem(now.headerItems + held)
                marking.launch {
                    arrived = line
                    delay(2_000)
                    arrived = null
                }
            }
            if (!conversations.isCurrent(id)) break
            delay(20_000)
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var deleteError by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    val record = loaded?.record
    Screen(title = tr("שיחה", "Conversation"), close = tr("חזרה", "Back"), onClose = onClose) {
        val shown = loaded
        when {
            shown == null -> LoadingNote()
            shown.record == null -> Text(tr("השיחה לא נמצאה", "Conversation not found"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            else -> {
                val numbers = shown.numbers?.takeIf { it.listed.isNotEmpty() }
                val header = shown.headerItems
                val matched = shown.matches.toSet()
                // Held above the lines, unlike the name: opened at a line far
                // down, the conversation can still be shared, named or deleted,
                // and each jump goes on from where the last one left the list.
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(Icons.Rounded.Share, tr("שיתוף", "Share"), onClick = { scope.launch { context.startActivity(withContext(Dispatchers.IO) { shareSheet(context, shown.record, marksUncertain, languageCode) }) } })
                    ActionChip(Icons.Rounded.Edit, tr("מתן שם לשיחה", "Name this conversation"), onClick = { naming = shown.record.title ?: "" })
                    ActionChip(Icons.Rounded.Delete, tr("מחיקה", "Delete"), onClick = { confirmingDelete = true }, colour = MaterialTheme.colorScheme.error)
                    if (shown.matches.size > 1) {
                        ActionChip(
                            Icons.Rounded.KeyboardArrowDown,
                            SearchMatches.nextTitle(current, shown.matches.size),
                            onClick = {
                                current = (current + 1) % shown.matches.size
                                val target = shown.lines.indexOfFirst { it.id == shown.matches[current] }
                                if (target >= 0) scope.launch { list.scrollToItem(header + target) }
                            },
                        )
                    }
                    if (shown.starred.isNotEmpty()) {
                        ActionChip(
                            Icons.Rounded.Star,
                            SearchMatches.starredTitle(starAt, shown.starred.size, jumpedToStar),
                            onClick = {
                                starAt = if (jumpedToStar) (starAt + 1) % shown.starred.size else 0
                                jumpedToStar = true
                                val target = shown.lines.indexOfFirst { it.id == shown.starred[starAt] }
                                if (target >= 0) scope.launch { list.scrollToItem(header + target) }
                            },
                        )
                    }
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Its name scrolls away with the lines: held above them, a
                    // long name at the largest text took half the screen and left
                    // the lines a strip.
                    shown.record.title?.let { name ->
                        item(key = "name") {
                            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
                        }
                    }
                    shown.stats?.let { stats -> item(key = "summary") { SummarySection(stats) } }
                    if (numbers != null) {
                        item(key = "numbers") {
                            NumbersSection(numbers, isUncertain = { marksUncertain && CaptionConfidence.isUncertain(it, shown.record.engine, shown.record.modelVariant) }) { said ->
                                val target = shown.lines.indexOfFirst { it.id == said.id }
                                if (target >= 0) scope.launch { list.scrollToItem(header + target) }
                            }
                        }
                    }
                    item(key = "when") {
                        SectionHeader(conversationHeading(shown.record.startedAt, now = System.currentTimeMillis() / 1_000.0))
                    }
                    items(shown.lines, key = { it.id.toString() }) { line ->
                        SavedLineView(line, isMatch = line.id in matched, arrived = line.id == arrived, emphasizeNumbers = emphasizeNumbers) {
                            scope.launch {
                                conversations.toggleStar(id, line.id)
                                show(withContext(Dispatchers.IO) { opened(conversations.load(id), query, marksUncertain, languageCode) })
                            }
                        }
                    }
                    item(key = "source") {
                        Text(shown.record.sourceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
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
                                    opened(conversations.load(id), query, marksUncertain, languageCode)
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

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit, colour: Color = MaterialTheme.colorScheme.onSurface) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(labelColor = colour, leadingIconContentColor = colour),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
internal fun LoadingNote(icon: ImageVector = Icons.Rounded.History) {
    ProgressState(icon, tr("רק רגע", "Just a moment"), Modifier.padding(top = 48.dp))
}

@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/**
 * At the top, as on the iPhone: how long and how many words, who said how
 * much, the pace and the turns. Every number is a count or a span between
 * the lines' own times.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(stats: ConversationStats) {
    GroupCard(tr("סיכום", "Summary")) {
        Text(stats.hebrewSummary, style = MaterialTheme.typography.titleMedium)
        for (speaker in stats.speakers) {
            val share = stats.wordFraction(speaker)
            Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(speaker.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${ConversationStats.wordsText(speaker.words)} · ${(share * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { share.toFloat() },
                    modifier = Modifier.fillMaxWidth().testTag("$SPEAKER_SHARE${speaker.name}").clearAndSetSemantics {},
                    color = SpeakerColour.of(speaker.clusterID, speaker.name, onLightScreen()),
                )
            }
        }
        if (stats.wordsPerMinute > 0) SummaryRow(tr("קצב דיבור", "Speaking pace"), ConversationStats.paceText(stats.wordsPerMinute))
        SummaryRow(tr("חילופי דוברים", "Speaker turns"), "${stats.totalTurns}")
        stats.longestTurn?.takeIf { stats.speakers.size > 1 }?.let {
            SummaryRow(tr("הדיבור הארוך ביותר", "Longest turn"), "${it.speakerName} · ${ConversationStats.wordsText(it.words)}")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryRow(label: String, value: String) {
    LabelAndValue(
        label = { Text(label) },
        value = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        gap = 16.dp,
    )
}

/** Above the conversation, as on the iPhone: each line with a number in it, a tap away from where it was said. */
@Composable
private fun NumbersSection(numbers: NumbersMentioned, isUncertain: (SavedSegment) -> Boolean, onJump: (SavedSegment) -> Unit) {
    val jump = tr("מעבר לשורה בשיחה", "Jump to this line in the conversation")
    val copy = tr("העתקה", "Copy")
    val unsureLabel = tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ").trim()
    val context = LocalContext.current
    GroupCard(tr("מספרים שנאמרו", "Numbers mentioned")) {
        // As on the iPhone: each copies from a hold or the screen reader, and
        // one that may have been misheard says so, "10:30" or "11:30" being
        // where the doubt matters most.
        for (said in numbers.listed) key(said.id) {
            val unsure = isUncertain(said)
            val copyLine = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(copy, CaptionLayout.copiedText(said.text))) }
            var menu by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = jump) { onJump(said) }
                    .onHold { menu = true }
                    .semantics {
                        customActions = listOf(CustomAccessibilityAction(copy) { copyLine(); true })
                        if (unsure) stateDescription = unsureLabel
                    }
                    .padding(vertical = 6.dp),
            ) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(copy) }, onClick = { menu = false; copyLine() })
                }
                said.speakerName?.takeUnless(TranscriptSessionSummary::isGenericLabel)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    // The row's state says it once; the mark's own label would say it twice.
                    if (unsure) Box(Modifier.clearAndSetSemantics {}) { UnsureMark(MaterialTheme.typography.bodyLarge.fontSize.value.toDouble(), MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        captionText(CaptionLayout.directed(said.text), emphasizeNumbers = true, numberColor = null, decimalComma = LocalDecimalComma.current),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    )
                }
            }
        }
        numbers.moreText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            tr("שעות, כמויות ומספרי טלפון מהשיחה. נגיעה בשורה מובילה אליה.", "Times, amounts, and phone numbers from the conversation. Tap a line to jump to it."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavedLineView(line: SavedLine, isMatch: Boolean, emphasizeNumbers: Boolean, arrived: Boolean = false, onStar: () -> Unit) {
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
            .padding(horizontal = 10.dp)
            .background(if (isMatch || arrived) SEARCH_MATCH else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
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
        LineDirection(line.rightToLeft) {
            Column(modifier = Modifier.fillMaxWidth()) {
                line.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                line.speaker?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = SpeakerColour.of(line.cluster, it, onLightScreen())) }
                Row {
                    if (line.starred) Text("★", color = starColour(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 6.dp))
                    if (line.unsure) UnsureMark(MaterialTheme.typography.bodyLarge.fontSize.value.toDouble(), MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(captionText(line.text, emphasizeNumbers, numberColor = null, linkingPhoneNumbers = true, decimalComma = LocalDecimalComma.current), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A saved line laid out from the side its own words read from, as on the iPhone, whatever the app's language. */
@Composable
internal fun LineDirection(rightToLeft: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)

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
internal fun Screen(title: String, close: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    OzenScreen(title, onClose = onClose, closeLabel = close, actions = { actions() }, scrolls = false, content = content)
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

private fun shareSheet(context: Context, record: TranscriptSessionRecord, marksUncertain: Boolean, languageCode: String): Intent =
    textShareSheet(context, tr("שיחה מאוזן", "Conversation from Ozen"), TranscriptHistoryStore.exportText(record, ::localOffset, marksUncertain, usesDecimalComma(languageCode)))

/** The share sheet for a conversation or the starred lines: as a message, or past [LONGEST_SHARED_TEXT] as a file. */
internal fun textShareSheet(context: Context, subject: String, text: String): Intent {
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
