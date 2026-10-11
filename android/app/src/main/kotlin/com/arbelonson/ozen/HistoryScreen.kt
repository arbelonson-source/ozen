package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.HistoryDay
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the History screen reads and changes: the saved conversations, and the one still being captioned. */
interface SavedConversations {
    fun list(): List<TranscriptSessionSummary>

    fun load(id: UUID): TranscriptSessionRecord?

    fun delete(id: UUID)

    fun opened(id: UUID)

    fun doneWith(id: UUID)

    fun isCurrent(id: UUID): Boolean
}

/** Past conversations by day, as on the iPhone: open one to read it back, or delete it. Everything stays on the phone. */
@Composable
fun HistoryScreen(conversations: SavedConversations, saving: Boolean, onClose: () -> Unit) {
    var openID by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openID
    if (open != null) {
        ConversationScreen(conversations, UUID.fromString(open), onClose = { openID = null })
        return
    }
    BackHandler(onBack = onClose)
    var days by remember { mutableStateOf<List<HistoryDay>?>(null) }
    LaunchedEffect(Unit) {
        days = withContext(Dispatchers.IO) {
            HistoryDays.grouped(conversations.list(), now = System.currentTimeMillis() / 1_000.0, utcOffsetSeconds = ::localOffset)
        }
    }
    Screen(title = tr("היסטוריה", "History"), close = tr("סגירה", "Close"), onClose = onClose) {
        val shown = days
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            shown.isEmpty() -> {
                Heading(tr("שיחות", "Conversations"))
                Text(
                    if (saving) tr("עדיין אין שיחות שמורות.", "No saved conversations yet.") else tr("שמירת שיחות כבויה.", "Saving conversations is off."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                for (day in shown) {
                    item(key = "day-${day.id}") { Heading(day.title) }
                    items(day.sessions, key = { it.id.toString() }) { session ->
                        ConversationRowView(ConversationRow.of(session)) { openID = session.id.toString() }
                        HorizontalDivider()
                    }
                }
            }
        }
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

/** One saved conversation read back; the one still being captioned is read again every twenty seconds, as it keeps growing on disk. */
@Composable
private fun ConversationScreen(conversations: SavedConversations, id: UUID, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    DisposableEffect(id) {
        conversations.opened(id)
        onDispose { conversations.doneWith(id) }
    }
    var loaded by remember { mutableStateOf<Pair<TranscriptSessionRecord?, List<SavedLine>>?>(null) }
    LaunchedEffect(id) {
        while (true) {
            loaded = withContext(Dispatchers.IO) { conversations.load(id).let { it to (it?.let { record -> SavedLine.of(record) } ?: emptyList()) } }
            if (!conversations.isCurrent(id)) break
            delay(20_000)
        }
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val record = loaded?.first
    Screen(title = record?.title ?: tr("שיחה", "Conversation"), close = tr("חזרה", "Back"), onClose = onClose) {
        val shown = loaded
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            shown.first == null -> Text(tr("השיחה לא נמצאה", "Conversation not found"))
            else -> {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown.second, key = { it.id.toString() }) { line -> SavedLineView(line) }
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

@Composable
private fun SavedLineView(line: SavedLine) {
    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = line.spoken }) {
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

private fun localOffset(at: Double): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli((at * 1_000).toLong())).totalSeconds
