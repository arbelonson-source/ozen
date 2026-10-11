package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.tr

@Composable
internal fun WordsScreen(words: List<String>, onChange: (List<String>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var draft by rememberSaveable { mutableStateOf("") }
    val listed = VocabularyHints.listedEntry(draft, words)
    val full = words.size >= VocabularyHints.maximumTerms
    val canAdd = draft.isNotBlank() && listed == null && !full
    val add = {
        if (canAdd) {
            onChange(listOf(draft) + words)
            draft = ""
        }
    }
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (words.isEmpty()) typing.requestFocus() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("שמות ומילים", "Names and words"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text(tr("חזרה", "Back")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(tr("שם או מילה", "Name or word")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f).focusRequester(typing),
                )
                TextButton(onClick = add, enabled = canAdd) { Text(tr("הוספה", "Add")) }
            }
            val note = when {
                listed != null -> tr("\"%1\" כבר ברשימה.", "“%1” is already in the list.", listOf(listed))
                full -> tr("הרשימה מלאה. מחקו מילה כדי להוסיף אחרת.", "The list is full. Delete a word to add another.")
                else -> null
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                tr(
                    "שמות של בני משפחה, שכנים, רופאים, תרופות, מקומות — כל מילה שהכתוביות מתקשות איתה. הראשונים ברשימה חשובים ביותר.",
                    "Names of family members, neighbors, doctors, medicines, places — any word the captions struggle with. The first ones on the list matter most.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text(tr("הרשימה", "List"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { heading() })
                Text("${words.size} / ${VocabularyHints.maximumTerms}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (words.isEmpty()) {
                Text(tr("עדיין אין שמות", "No names yet"), style = MaterialTheme.typography.titleMedium)
                Text(tr("הוסיפו את השמות שנאמרים הכי הרבה בבית.", "Add the names said most often at home."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(words, key = { it }) { word -> WordRow(word, onDelete = { onChange(words - word) }) }
                }
            }
            Text(
                tr("השינויים נכנסים לתוקף מהמשפט הבא, בלי להפעיל מחדש.", "Changes take effect from the next sentence, without restarting."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordRow(word: String, onDelete: () -> Unit) {
    val delete = tr("מחיקה", "Delete")
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            word,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .onHold { menu = true }
                .semantics { customActions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }) }
                .padding(vertical = 10.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(delete) }, onClick = { menu = false; onDelete() })
        }
    }
}
