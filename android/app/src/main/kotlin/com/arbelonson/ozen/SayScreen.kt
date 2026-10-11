package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
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
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var lastTyped by rememberSaveable { mutableStateOf<String?>(null) }
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
            Text(tr("משפטים מוכנים", "Quick phrases"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
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
