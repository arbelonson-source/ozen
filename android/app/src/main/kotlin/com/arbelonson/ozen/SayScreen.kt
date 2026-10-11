package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.tr

/**
 * The other direction of the conversation, as on the iPhone: she types a
 * reply, or taps one she uses all the time, and the phone says it. What
 * she typed stays in the field after it is said, so a typo caught right
 * after is fixed without retyping, and the sentence said last can be said
 * again when the other person didn't catch it.
 */
@Composable
internal fun SayScreen(
    draft: String,
    onDraft: (String) -> Unit,
    speaking: Boolean,
    phrases: List<String>,
    canSay: (String) -> Boolean,
    onSpeak: (String) -> Boolean,
    onStop: () -> Unit,
    onAddPhrase: (String) -> Unit,
    onRemovePhrase: (String) -> Unit,
    onRestorePhrases: () -> Unit,
    phrasesAreDefault: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var lastTyped by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var newPhrase by rememberSaveable { mutableStateOf("") }
    var confirmingRestore by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val addPhrase = {
        onAddPhrase(newPhrase)
        newPhrase = ""
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val speakTyped = {
        val phrase = draft.trim()
        if (phrase.isNotEmpty() && onSpeak(phrase)) lastTyped = phrase
    }
    val play = tr("להשמיע", "Play")
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("להגיד משהו", "Say something"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text(tr("סגירה", "Close")) }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(tr("הקלידו מה להגיד…", "Type what to say…")) },
                textStyle = MaterialTheme.typography.titleLarge,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { speakTyped() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (speaking) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text(tr("עצירה", "Stop")) }
                }
                Button(onClick = speakTyped, enabled = draft.isNotBlank() && canSay(draft), modifier = Modifier.weight(1f)) { Text(play) }
            }
            lastTyped?.let { phrase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onSpeak(phrase) },
                        enabled = canSay(phrase),
                        modifier = Modifier.weight(1f).semantics { contentDescription = tr("להשמיע שוב: %1", "Play again: %1", listOf(phrase)) },
                    ) {
                        Text("↻  $phrase", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    }
                    if (phrase !in phrases) {
                        TextButton(onClick = { onAddPhrase(phrase) }, modifier = Modifier.semantics { contentDescription = tr("להוסיף למשפטים המוכנים", "Add to quick phrases") }) {
                            Text("+", fontSize = 28.sp)
                        }
                    }
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("משפטים מוכנים", "Quick phrases"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(
                    onClick = {
                        editing = !editing
                        // The keyboard covered the editor that just opened.
                        if (editing) focusManager.clearFocus()
                    },
                ) { Text(if (editing) tr("סיום", "Done") else tr("עריכה", "Edit")) }
            }
            if (editing) {
                // Instead of the phrases to tap, not beside them as on the
                // iPhone: the same phrase twice on one screen, once to say
                // and once to delete, is one too many.
                Text(tr("עריכת המשפטים", "Edit phrases"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPhrase,
                        onValueChange = { newPhrase = it },
                        placeholder = { Text(tr("משפט חדש", "New phrase")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newPhrase.isNotBlank()) addPhrase() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = addPhrase, enabled = newPhrase.isNotBlank(), modifier = Modifier.semantics { contentDescription = tr("הוספה", "Add") }) {
                        Text("+", fontSize = 28.sp)
                    }
                }
                for (phrase in phrases) {
                    EditablePhraseRow(phrase, onDelete = { onRemovePhrase(phrase) })
                    HorizontalDivider()
                }
                TextButton(onClick = { confirmingRestore = true }, enabled = !phrasesAreDefault) {
                    Text(tr("לשחזר את ברירת המחדל", "Restore defaults"), color = if (phrasesAreDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            } else {
                for (phrase in phrases) {
                    val sayable = canSay(phrase)
                    Text(
                        phrase,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sayable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = sayable, onClickLabel = play) { onSpeak(phrase) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text(tr("לשחזר את המשפטים המוכנים?", "Restore the default phrases?")) },
            text = { Text(tr("המשפטים שנוספו או שונו יימחקו.", "Phrases that were added or changed will be deleted.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        onRestorePhrases()
                    },
                ) { Text(tr("לשחזר", "Restore"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

/** Held, or through the screen reader's action, a phrase is deleted at once, as the iPhone's swipe does. */
@Composable
private fun EditablePhraseRow(phrase: String, onDelete: () -> Unit) {
    val delete = tr("מחיקה", "Delete")
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            phrase,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .onHold { menu = true }
                .semantics { customActions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }) }
                .padding(vertical = 12.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(delete) }, onClick = { menu = false; onDelete() })
        }
    }
}
