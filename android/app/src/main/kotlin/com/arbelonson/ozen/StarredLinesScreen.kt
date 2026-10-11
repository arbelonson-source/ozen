package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.StarredLine
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.tr
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** History's way in, shown only when some saved line has a star, as on the iPhone. */
@Composable
internal fun StarredLinesRow(count: Int, onOpen: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("★", color = starColour(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics { })
            Text(tr("השורות המסומנות", "Starred lines"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

/**
 * Every starred line from every saved conversation, the newest conversation
 * first, as on the iPhone: the quick way back to "what did the doctor say
 * about the pills". A line opens its conversation where it was said, and
 * the list is read again on coming back, since a star taken away or a
 * conversation deleted there takes its lines off this list too.
 */
@Composable
internal fun StarredLinesScreen(conversations: SavedConversations, marksUncertain: Boolean, emphasizeNumbers: Boolean, onClose: () -> Unit, languageCode: String = "he") {
    var opened by rememberSaveable { mutableStateOf<Pair<UUID, UUID>?>(null) }
    val open = opened
    if (open != null) {
        ConversationScreen(conversations, open.first, query = "", marksUncertain = marksUncertain, emphasizeNumbers = emphasizeNumbers, onClose = { opened = null }, line = open.second, languageCode = languageCode)
        return
    }
    BackHandler(onBack = onClose)
    var lines by remember { mutableStateOf<List<StarredLine>?>(null) }
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) { conversations.starredLines() }
    }
    val context = LocalContext.current
    val shown = lines
    Screen(
        title = tr("שורות מסומנות", "Starred lines"),
        close = tr("חזרה", "Back"),
        onClose = onClose,
        actions = {
            if (!shown.isNullOrEmpty()) {
                TextButton(onClick = { context.startActivity(starredShareSheet(shown, marksUncertain)) }) { Text(tr("שיתוף", "Share")) }
            }
        },
    ) {
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            shown.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 24.dp)) {
                Text(tr("אין שורות מסומנות", "No starred lines"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("לחיצה ארוכה על שורה בזמן השיחה מסמנת אותה כחשובה.", "Press and hold a line during the conversation to mark it as important."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val now = System.currentTimeMillis() / 1_000.0
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    for ((session, starred) in shown.groupBy { it.sessionID }) {
                        item(key = "conversation-$session") {
                            Heading(HistoryDays.title(of = starred.first().sessionStartedAt, now = now, utcOffsetSeconds = ::localOffset))
                        }
                        items(starred, key = { it.id.toString() }) { line ->
                            StarredLineView(SavedLine.of(line, markUncertain = marksUncertain, languageCode = languageCode), emphasizeNumbers, onOpen = { opened = line.sessionID to line.id })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StarredLineView(line: SavedLine, emphasizeNumbers: Boolean, onOpen: () -> Unit) {
    val copy = tr("העתקה", "Copy")
    val context = LocalContext.current
    val copyLine = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(copy, line.copied)) }
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onHold { menu = true }
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = line.spoken
                customActions = listOf(CustomAccessibilityAction(copy) { copyLine(); true })
            }
            .padding(vertical = 8.dp),
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(copy) }, onClick = { menu = false; copyLine() })
        }
        LineDirection(line.rightToLeft) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    line.speaker?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                }
                Row {
                    if (line.unsure) UnsureMark(MaterialTheme.typography.bodyLarge.fontSize.value.toDouble(), MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(captionText(line.text, emphasizeNumbers, numberColor = null), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun starredShareSheet(lines: List<StarredLine>, marksUncertain: Boolean): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, tr("שורות מסומנות מאוזן", "Starred lines from Ozen"))
        .putExtra(Intent.EXTRA_TEXT, TranscriptHistoryStore.exportStarredText(lines, utcOffsetAt = ::localOffset, marksUncertain = marksUncertain))
    return Intent.createChooser(send, null)
}
