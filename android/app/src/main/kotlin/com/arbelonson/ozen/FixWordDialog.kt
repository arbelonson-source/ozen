package com.arbelonson.ozen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
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
internal fun FixWordDialog(shown: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var word by rememberSaveable { mutableStateOf("") }
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { typing.requestFocus() }
    val add = { if (word.isNotBlank()) onAdd(word) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(tr("תיקון מילה", "Fix a word")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("מה נכתב בכתוביות", "What the captions showed"), style = MaterialTheme.typography.labelLarge)
                Text(shown, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text(tr("המילה או השם הנכונים", "The correct word or name")) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(typing),
                )
                Text(
                    tr("מהמשפט הבא, הכתוביות ינסו לזהות את המילה הזו נכון.", "From the next sentence, the captions will try to recognize this word correctly."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = add, enabled = word.isNotBlank()) { Text(tr("הוספה", "Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}
