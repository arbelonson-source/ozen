package com.arbelonson.ozen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.WordFixes
import com.arbelonson.ozen.core.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FixWordDialog(shown: String, onSave: (heard: String, written: String, everyTime: Boolean) -> Unit, onDismiss: () -> Unit) {
    val words = WordFixes.wordsOf(shown)
    var first by rememberSaveable { mutableStateOf(-1) }
    var last by rememberSaveable { mutableStateOf(-1) }
    var typed by rememberSaveable { mutableStateOf("") }
    var written by rememberSaveable { mutableStateOf("") }
    var everyTime by rememberSaveable { mutableStateOf(false) }
    val picked = if (first >= 0) words.subList(first, minOf(last + 1, words.size)).joinToString(" ") else ""
    val heard = if (typed.isNotBlank()) typed.trim() else picked
    val ready = heard.isNotBlank() && written.isNotBlank()
    val everyTimeLabel = tr("תיקון בכל פעם", "Fix it every time")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(tr("תיקון מילה", "Fix a word")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("נגעו במילים שנשמעו לא נכון", "Tap the words that came out wrong"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    words.forEachIndexed { index, word ->
                        val on = typed.isBlank() && first >= 0 && index in first..last
                        Text(
                            word,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Checkbox) {
                                    typed = ""
                                    val now = WordFixes.tapped(if (first >= 0) first..last else null, index)
                                    first = now?.first ?: -1
                                    last = now?.last ?: -1
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .clearAndSetSemantics { contentDescription = word; selected = on; role = Role.Checkbox },
                        )
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(WordFixes.MAXIMUM_LENGTH) },
                    label = { Text(tr("או הקלידו מה נשמע", "Or type what was heard")) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = written,
                    onValueChange = { written = it.take(WordFixes.MAXIMUM_LENGTH) },
                    label = { Text(tr("איך זה צריך להיכתב", "How it should be written")) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Switch) { everyTime = !everyTime }
                        .clearAndSetSemantics {
                            contentDescription = everyTimeLabel
                            role = Role.Switch
                            toggleableState = if (everyTime) ToggleableState.On else ToggleableState.Off
                        },
                ) {
                    Text(everyTimeLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(checked = everyTime, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(heard, written.trim(), everyTime) }, enabled = ready) { Text(tr("שמירה", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}
