package com.arbelonson.ozen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var word by remember { mutableStateOf("") }
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { typing.requestFocus() }
    val add = { if (word.isNotBlank()) onAdd(word) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.focusRequester(typing),
                )
                Text(
                    tr("מהמשפט הבא, הכתוביות ינסו לזהות את המילה הזו נכון.", "From the next sentence, the captions will try to recognize this word correctly."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = add, enabled = word.isNotBlank()) { Text(tr("הוספה", "Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}
