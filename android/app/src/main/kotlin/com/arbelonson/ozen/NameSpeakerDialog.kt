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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.tr

@Composable
internal fun NameSpeakerDialog(
    shown: String,
    hasVoice: Boolean,
    savedNames: List<String>,
    onSave: (name: String, onlyThisLine: Boolean) -> Boolean,
    onDismiss: () -> Unit,
    renames: (String) -> String? = { null },
) {
    var name by rememberSaveable { mutableStateOf("") }
    var tooOld by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (hasVoice && savedNames.isEmpty()) typing.requestFocus() }
    val commit = { onlyThisLine: Boolean ->
        asking = null
        tooOld = !onSave(name.trim(), onlyThisLine)
    }
    // A name one letter from the saved person's this voice carries (Noa and
    // Noam) would rename that person in every saved conversation. Only the
    // person typing knows which was meant, so the dialog asks, as on the
    // iPhone, in a question TalkBack reads out.
    val save = {
        if (hasVoice && name.isNotBlank()) {
            val renamed = renames(name.trim())
            if (renamed != null) asking = renamed else commit(false)
        }
    }
    asking?.let { saved ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(tr("לשנות את ״%1״ ל־״%2״ בכל מקום, גם בשיחות השמורות?", "Change “%1” to “%2” everywhere, also in saved conversations?", listOf(saved, name.trim()))) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { commit(false) }) { Text(tr("לשנות בכל מקום", "Change everywhere")) }
                    TextButton(onClick = { commit(true) }) { Text(tr("רק בשורה הזו", "Only this line")) }
                }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
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
                if (tooOld) {
                    Text(
                        tr("השורה הזו ישנה מדי כדי לזהות ממנה את הקול. אפשר ללחוץ לחיצה ארוכה על שורה חדשה יותר של אותו אדם.", "This line is too old to recognize the voice from. Press and hold a newer line by the same person."),
                        style = MaterialTheme.typography.bodyLarge,
                        color = cautionOrange(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Text(
                        if (hasVoice) {
                            tr("מעכשיו כל מה שהקול הזה יגיד יופיע עם השם הזה.", "From now on, everything this voice says will show with this name.")
                        } else {
                            tr("עדיין לא זוהה קול לשורה הזו. נסו שוב אחרי שהאדם ידבר עוד קצת.", "No voice has been identified for this line yet. Try again after the person talks a bit more.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasVoice && savedNames.isNotEmpty()) {
                    Text(tr("דוברים שמורים", "Saved speakers"), style = MaterialTheme.typography.labelLarge)
                    savedNames.forEach { saved ->
                        val chosen = name.trim() == saved
                        FilterChip(
                            selected = chosen,
                            onClick = { name = saved },
                            label = { Text(saved) },
                            leadingIcon = { Icon(if (chosen) Icons.Rounded.Check else Icons.Rounded.Person, contentDescription = null) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = hasVoice && name.isNotBlank()) { Text(tr("שמירה", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}
