package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.EmptyState
import com.arbelonson.ozen.ui.OzenScreen
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.SettingRow
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.TonalButton
import com.arbelonson.ozen.ui.GroupFootnote
import com.arbelonson.ozen.ui.SectionHeader as GroupHeader

@Composable
internal fun WordsScreen(words: List<String>, speakerNames: List<String>, onChange: (List<String>) -> Unit, wordFixes: List<WordFix>, onWordFixes: (List<WordFix>) -> Unit, onClose: () -> Unit) {
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
    OzenScreen(tr("שמות ומילים", "Names and words"), modifier = Modifier.imePadding(), onClose = onClose, closeLabel = tr("חזרה", "Back"), scrolls = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(tr("שם או מילה", "Name or word")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f).focusRequester(typing),
            )
            TonalButton(tr("הוספה", "Add"), onClick = add, enabled = canAdd)
        }
        val note = when {
            listed != null -> tr("\"%1\" כבר ברשימה.", "“%1” is already in the list.", listOf(listed))
            full -> tr("הרשימה מלאה. מחקו מילה כדי להוסיף אחרת.", "The list is full. Delete a word to add another.")
            else -> null
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
        GroupFootnote(
            tr(
                "שמות של בני משפחה, שכנים, רופאים, תרופות, מקומות — כל מילה שהכתוביות מתקשות איתה. הראשונים ברשימה חשובים ביותר.",
                "Names of family members, neighbors, doctors, medicines, places — any word the captions struggle with. The first ones on the list matter most.",
            ),
        )
        if (VocabularyHints.normalized(words + speakerNames) != words) {
            SettingsGroup(dividerInset = 56.dp) {
                item { SettingRow(tr("להוסיף את שמות הדוברים השמורים", "Add the saved speaker names"), icon = Icons.Rounded.PersonAdd, onClick = { onChange(words + speakerNames) }) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            GroupHeader(tr("הרשימה", "List"), modifier = Modifier.weight(1f))
            Text("${words.size} / ${VocabularyHints.maximumTerms}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        }
        if (words.isEmpty()) {
            EmptyState(Icons.Rounded.Spellcheck, tr("עדיין אין שמות", "No names yet"), tr("הוסיפו את השמות שנאמרים הכי הרבה בבית.", "Add the names said most often at home."))
        } else {
            GroupFootnote(tr("לחיצה ארוכה על שם מאפשרת להזיז אותו או למחוק אותו.", "Press and hold a name to move it or delete it."))
            Surface(modifier = Modifier.weight(1f, fill = false), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyColumn {
                    itemsIndexed(words, key = { _, word -> word }) { at, word ->
                        if (at > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        WordRow(
                            word,
                            onMoveUp = { onChange(movingWord(word, -1, words)) }.takeIf { at > 0 },
                            onMoveDown = { onChange(movingWord(word, 1, words)) }.takeIf { at < words.lastIndex },
                            onDelete = { onChange(words - word) },
                        )
                    }
                }
            }
        }
        WordFixesSection(wordFixes, onWordFixes)
        GroupFootnote(
            tr("השינויים נכנסים לתוקף מהמשפט הבא, בלי להפעיל מחדש.", "Changes take effect from the next sentence, without restarting."),
            modifier = Modifier.padding(bottom = OzenSpacing.Unit),
        )
    }
}

/**
 * The first words on the list matter most to the captions; the iPhone
 * drags one to its place, and here, as with quick phrases, the hold menu
 * and the screen reader move it a place at a time.
 */
@Composable
private fun WordRow(word: String, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onDelete: () -> Unit) {
    val choices = listOfNotNull(
        onMoveUp?.let { tr("להזיז למעלה", "Move up") to it },
        onMoveDown?.let { tr("להזיז למטה", "Move down") to it },
        tr("מחיקה", "Delete") to onDelete,
    )
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            word,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .onHold { menu = true }
                .semantics { customActions = choices.map { (label, act) -> CustomAccessibilityAction(label) { act(); true } } }
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((label, act) in choices) {
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act() })
            }
        }
    }
}

internal fun movingWord(word: String, by: Int, words: List<String>): List<String> {
    val from = words.indexOf(word)
    val to = from + by
    if (from < 0 || to !in words.indices) return words
    return words.toMutableList().apply { add(to, removeAt(from)) }
}
