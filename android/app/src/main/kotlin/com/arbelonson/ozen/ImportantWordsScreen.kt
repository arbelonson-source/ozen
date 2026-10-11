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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AlertSuggestions
import com.arbelonson.ozen.core.HebrewText
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr

/**
 * The words that make the phone buzz and mark their line when said, as on
 * the iPhone: added at the end, switched off without being lost, and
 * deleted only after asking, since losing the one that buzzes for her name
 * should take a second tap. The ten heard last follow, newest first, as on
 * the iPhone, each with who said it when their voice is known.
 */
@Composable
internal fun ImportantWordsScreen(
    alerts: List<KeywordAlert>,
    vocabulary: List<String>,
    captionLanguage: String,
    spoken: UILanguage,
    heard: List<KeywordHit>,
    onChange: (List<KeywordAlert>) -> Unit,
    onClose: () -> Unit,
    heardBy: (KeywordHit) -> String? = { null },
) {
    BackHandler(onBack = onClose)
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<KeywordAlert?>(null) }
    val listed = ImportantWords.listed(draft, alerts)
    val typedWordIsOn = listed?.isEnabled == true
    val add = {
        if (!typedWordIsOn) {
            onChange(ImportantWords.adding(draft, alerts))
            draft = ""
        }
    }
    val otherLetters = tr("הכתוביות באותיות עבריות, ולכן מילה שנכתבה באותיות אחרות עלולה לא להימצא אף פעם.", "Captions are in Hebrew letters, so a word written in other letters may never be found.")
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("מילים חשובות", "Important words"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text(tr("חזרה", "Back")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(tr("מילה או שם, למשל: סבתא", "A word or name, e.g., grandma")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                val plus = if (listed == null) tr("הוספה", "Add") else tr("הפעלה", "Turn on")
                TextButton(onClick = add, enabled = draft.isNotBlank() && !typedWordIsOn, modifier = Modifier.semantics { contentDescription = plus }) {
                    Text("+", fontSize = 28.sp)
                }
            }
            listed?.let {
                val shown = shown(it.phrase)
                Text(
                    if (it.isEnabled) {
                        tr("\"%1\" כבר ברשימה.", "“%1” is already in the list.", listOf(shown))
                    } else {
                        tr("\"%1\" כבר ברשימה, במצב כבוי. הקישו על הפלוס כדי להפעיל מחדש.", "“%1” is already in the list, turned off. Tap the plus to turn it back on.", listOf(shown))
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (HebrewText.isInOtherLetters(draft, captionLanguage)) Text(otherLetters, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                tr(
                    "כשמילה מהרשימה נאמרת, הטלפון ירטוט והשורה תודגש בצהוב עם פעמון. גם צורות כמו \"לסבתא\" או \"וסבתא\" נחשבות. כדאי לכתוב מילים בלי ה׳ בהתחלה: \"רופא\" ולא \"הרופא\", כדי שגם \"לרופא\" ייחשב.",
                    "When a word from the list is said, the phone will vibrate and the line will be highlighted in yellow with a bell. Forms like “to grandma” or “and grandma” count too. It’s best to write words without a leading “the”: “doctor” instead of “the doctor”, so “to the doctor” counts too.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("הרשימה", "List"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            if (alerts.isEmpty()) {
                Text(
                    tr("עדיין אין מילים. הוסיפו את השם שלך, שמות של נכדים, או מילים כמו \"תרופה\".", "No words yet. Add your name, grandchildren’s names, or words like “medicine”."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (alert in alerts) {
                ImportantWordRow(
                    alert = alert,
                    shown = shown(alert.phrase),
                    note = otherLetters.takeIf { HebrewText.isInOtherLetters(alert.phrase, captionLanguage) },
                    onSwitch = { on -> onChange(ImportantWords.switched(alert.id, on, alerts)) },
                    onDelete = { pendingDelete = alert },
                )
            }
            for (word in ImportantWords.suggestions(vocabulary, alerts, spoken)) {
                TextButton(onClick = { onChange(ImportantWords.adding(word, alerts)) }) {
                    Text(tr("להוסיף: %1", "Add: %1", listOf(AlertSuggestions.label(word, Localization.language))))
                }
            }
            if (heard.isNotEmpty()) {
                Text(tr("נשמעו לאחרונה", "Recently heard"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                for (hit in heard) {
                    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(AlertSuggestions.said(hit.match, Localization.language), style = MaterialTheme.typography.bodyLarge)
                            heardBy(hit)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(clockTime(hit.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    pendingDelete?.let { alert ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("למחוק את \"%1\"?", "Delete “%1”?", listOf(shown(alert.phrase)))) },
            text = { Text(tr("הטלפון יפסיק להתריע כשהמילה נאמרת.", "The phone will stop alerting when this word is said.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onChange(ImportantWords.removing(alert.id, alerts))
                    },
                ) { Text(tr("למחוק", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

private fun shown(phrase: String) = AlertSuggestions.shown(phrase, Localization.language)

@Composable
private fun ImportantWordRow(alert: KeywordAlert, shown: String, note: String?, onSwitch: (Boolean) -> Unit, onDelete: () -> Unit) {
    val delete = tr("מחיקה", "Delete")
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onHold { menu = true }
                .toggleable(value = alert.isEnabled, role = Role.Switch, onValueChange = onSwitch)
                .semantics { customActions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (alert.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(checked = alert.isEnabled, onCheckedChange = null)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(delete) }, onClick = { menu = false; onDelete() })
        }
    }
}
