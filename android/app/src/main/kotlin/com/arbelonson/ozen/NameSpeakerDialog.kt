package com.arbelonson.ozen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.tr

@Composable
internal fun NameSpeakerDialog(shown: String, hasVoice: Boolean, savedNames: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (hasVoice && savedNames.isEmpty()) typing.requestFocus() }
    val save = { if (hasVoice && name.isNotBlank()) onSave(name.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
        title = { Text(tr("שם לדובר", "Name the speaker")) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("מי אמר את זה?", "Who said this?"), style = MaterialTheme.typography.labelLarge)
                Text(shown, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("שם", "Name")) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(typing),
                )
                Text(
                    if (hasVoice) {
                        tr("מעכשיו כל מה שהקול הזה יגיד יופיע עם השם הזה.", "From now on, everything this voice says will show with this name.")
                    } else {
                        tr("עדיין לא זוהה קול לשורה הזו. נסו שוב אחרי שידברו עוד קצת.", "No voice has been identified for this line yet. Try again after the person talks a bit more.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasVoice && savedNames.isNotEmpty()) {
                    Text(tr("דוברים שמורים", "Saved speakers"), style = MaterialTheme.typography.labelLarge)
                    savedNames.forEach { saved -> AssistChip(onClick = { onSave(saved) }, label = { Text(saved) }, leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = hasVoice && name.isNotBlank()) { Text(tr("שמירה", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}
