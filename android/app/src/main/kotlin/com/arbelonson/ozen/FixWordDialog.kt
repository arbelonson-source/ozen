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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixes
import com.arbelonson.ozen.core.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FixWordDialog(shown: String, languageCode: String, error: String? = null, onSave: (heard: String, written: String, everyTime: Boolean) -> Unit, onDismiss: () -> Unit) {
    val words = WordFixes.wordsOf(shown)
    var first by rememberSaveable { mutableStateOf(-1) }
    var last by rememberSaveable { mutableStateOf(-1) }
    var typed by rememberSaveable { mutableStateOf("") }
    var written by rememberSaveable { mutableStateOf("") }
    var everyTime by rememberSaveable { mutableStateOf(false) }
    val picked = if (first >= 0) words.subList(first, minOf(last + 1, words.size)).joinToString(" ") else ""
    val heard = if (typed.isNotBlank()) typed.trim() else picked
    val ready = FixWordForm.changes(shown, heard, written)
    val note = FixWordForm.note(shown, typed, heard, written)
    val direction = if (CaptionLayout.readsRightToLeft(shown, languageCode)) LayoutDirection.Rtl else LayoutDirection.Ltr
    val everyTimeLabel = tr("תיקון בכל פעם", "Fix it every time")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(tr("תיקון מילה", "Fix a word")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("הקישו על המילים שנכתבו לא נכון", "Tap the words that came out wrong"), style = MaterialTheme.typography.labelLarge)
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
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
                                    val now = WordFixes.tapped(if (first >= 0) first..last else null, index) { WordFixes.joinedBySpace(shown, it) }
                                    first = now?.first ?: -1
                                    last = now?.last ?: -1
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .clearAndSetSemantics { contentDescription = word; selected = on; role = Role.Checkbox },
                        )
                    }
                }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(tr("או הקלידו את המילה השגויה", "Or type the wrong word")) },
                    isError = typed.trim().length > WordFixes.MAXIMUM_LENGTH,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let {
                    Text(
                        when (it) {
                            FixWordForm.Note.NotInLine -> tr("המילה הזו לא מופיעה בשורה", "This word is not in the line")
                            FixWordForm.Note.NowWrite -> tr("עכשיו כתבו איך זה צריך להיכתב", "Now type how it should be written")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it == FixWordForm.Note.NotInLine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                OutlinedTextField(
                    value = written,
                    onValueChange = { written = it },
                    label = { Text(tr("איך זה צריך להיכתב", "How it should be written")) },
                    isError = written.trim().length > WordFixes.MAXIMUM_LENGTH,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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

/** What Save and the note under the first box go by, apart from the drawing. */
internal object FixWordForm {
    enum class Note { NotInLine, NowWrite }

    /**
     * Whether saving changes the line. A typed word that is not in it
     * changed nothing: Save closed the dialog as if it had worked, and the
     * word still went into the names list.
     */
    fun changes(shown: String, heard: String, written: String): Boolean {
        val fits = heard.length <= WordFixes.MAXIMUM_LENGTH && written.trim().length <= WordFixes.MAXIMUM_LENGTH
        return fits && WordFixes.normalized(listOf(WordFix(heard, written))).isNotEmpty() &&
            WordFixes.apply(listOf(WordFix(heard, written.trim())), shown) != shown
    }

    /**
     * The line under the first box: a typed word the line does not have,
     * often the right word typed in the wrong box, or, once the wrong word
     * is known, where the right one goes.
     */
    fun note(shown: String, typed: String, heard: String, written: String): Note? = when {
        heard.isBlank() || heard.length > WordFixes.MAXIMUM_LENGTH -> null
        typed.isNotBlank() && WordFixes.apply(listOf(WordFix(heard, "_")), shown) == shown -> Note.NotInLine
        written.isBlank() -> Note.NowWrite
        else -> null
    }
}
