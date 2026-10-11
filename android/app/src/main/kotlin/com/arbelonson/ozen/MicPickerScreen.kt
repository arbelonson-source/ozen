package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.tr
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The iPhone's microphone picker: the microphones the phone offers, the
 * one recording marked, and a meter to see that it hears. A choice is kept
 * for next time even when the phone refuses to switch, and the refusal is
 * said, since the marked microphone keeps recording. The tips are the
 * iPhone's, for Android: a USB-C microphone the phone doesn't list is the
 * phone's doing, and a Bluetooth headset records worse than a wire.
 */
@Composable
internal fun MicPickerScreen(
    inputs: List<AudioInputDescriptor>,
    selected: AudioInputDescriptor?,
    listening: Boolean,
    level: () -> Float,
    onChoose: (AudioInputDescriptor) -> Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var refused by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onRefresh() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("בחירת מיקרופון", "Choose microphone"),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onRefresh) { Text(tr("רענון", "Refresh")) }
                TextButton(onClick = onClose) { Text(tr("סגירה", "Close")) }
            }
            if (inputs.isEmpty()) {
                Text(tr("אין מיקרופונים זמינים", "No microphones available"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("אם האפליקציה עדיין מתחילה, חכו רגע. אחרת חברו אוזניות או מיקרופון חיצוני והקישו על רענון.", "If the app is still starting up, wait a moment. Otherwise, connect headphones or an external microphone and tap refresh."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Header(tr("מיקרופונים זמינים", "Available microphones"))
                for (input in inputs) {
                    InputRow(input, isSelected = input.uid == selected?.uid) {
                        refused = if (onChoose(input)) null else input.portName
                    }
                }
                refused?.let { name ->
                    Text(
                        tr("הטלפון לא עבר ל\"%1\", והמיקרופון המסומן עדיין מקליט. נסו לנתק ולחבר אותו שוב.", "The phone did not switch to “%1”, and the marked microphone is still recording. Try unplugging and reconnecting it.", listOf(name)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    tr("המיקרופון המסומן הוא זה שמקליט עכשיו. אוזניות או מיקרופון שיתחברו יופיעו כאן אוטומטית.", "The marked microphone is the one recording now. Headphones or a microphone that connect will appear here automatically."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Header(tr("עוצמת קליטה", "Input level"))
            LevelMeter(level, listening)
            Text(
                if (listening) {
                    tr("דברו ותראו את הפס זז. אם הוא לא זז, המיקרופון שנבחר לא שומע.", "Speak and watch the bar move. If it doesn’t move, the selected microphone isn’t hearing anything.")
                } else {
                    tr("המד פעיל רק בזמן האזנה.", "The meter is only active while listening.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header(tr("טיפים", "Tips"))
            Text(
                tr("מיקרופון USB‑C צריך להיות מסוג \"USB Audio\" (כמו רוב מיקרופוני הדש). אם הוא לא מופיע ברשימה, הטלפון לא מזהה אותו בכלל, ולא רק האפליקציה.", "A USB‑C microphone needs to be of the “USB Audio” type (like most lapel mics). If it doesn’t appear in the list, the phone isn’t recognizing it at all, not just the app."),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tr("אוזניות Bluetooth מופיעות כשהן מחוברות. איכות ההקלטה דרכן נמוכה יותר מאשר מיקרופון חוטי.", "A Bluetooth headset appears once it’s connected. Recording quality through it is lower than a wired microphone."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun InputRow(input: AudioInputDescriptor, isSelected: Boolean, onChoose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onChoose)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(input.portName, style = MaterialTheme.typography.bodyLarge)
            Text(inputTypeName(input.portType), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSelected) Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** Read every 50 ms while captions run; empty, and said so, while they don't. */
@Composable
private fun LevelMeter(level: () -> Float, active: Boolean) {
    var shown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        while (active) {
            shown = level().coerceIn(0f, 1f)
            delay(50)
        }
        shown = 0f
    }
    val label = tr("עוצמת קליטה", "Input level")
    val percent = tr("%1 אחוז", "%1 percent", listOf("${(shown * 100).roundToInt()}"))
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            stateDescription = percent
        },
    )
}

internal fun inputTypeName(type: AudioPortType): String = when (type) {
    AudioPortType.BuiltInMic -> tr("מובנה", "Built-in")
    AudioPortType.Bluetooth -> "Bluetooth"
    AudioPortType.Wired -> tr("חוטי", "Wired")
    AudioPortType.Usb -> "USB"
    AudioPortType.HearingAid -> tr("מכשיר שמיעה", "Hearing aid")
    AudioPortType.RemoteMic -> tr("מיקרופון מרוחק", "Remote microphone")
    AudioPortType.Other -> tr("אחר", "Other")
}

/** What the caption bar's microphone button says: the phone's own is just "Phone". */
internal fun inputShortName(input: AudioInputDescriptor): String =
    if (input.portType == AudioPortType.BuiltInMic) tr("טלפון", "Phone") else input.portName
