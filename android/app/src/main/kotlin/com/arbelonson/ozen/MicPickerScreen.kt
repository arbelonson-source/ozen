package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.EmptyState
import com.arbelonson.ozen.ui.SettingsGroup
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
    SettingsPageFrame(
        tr("בחירת מיקרופון", "Choose microphone"),
        onClose = onClose,
        closeLabel = tr("סגירה", "Close"),
        actions = {
            TextButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(tr("רענון", "Refresh"))
            }
        },
    ) {
        if (inputs.isEmpty()) {
            EmptyState(
                Icons.Rounded.MicOff,
                tr("אין מיקרופונים זמינים", "No microphones available"),
                tr("אם האפליקציה עדיין מתחילה, חכו רגע. אחרת חברו אוזניות או מיקרופון חיצוני והקישו על רענון.", "If the app is still starting up, wait a moment. Otherwise, connect headphones or an external microphone and tap refresh."),
            )
        } else {
            SectionHeader(tr("מיקרופונים זמינים", "Available microphones"))
            SettingsGroup(modifier = Modifier.selectableGroup()) {
                for (input in inputs) {
                    item {
                        ChoiceRow(input.portName, inputTypeName(input.portType), selected = input.uid == selected?.uid) {
                            refused = if (onChoose(input)) null else input.portName
                        }
                    }
                }
            }
            refused?.let { name ->
                Text(
                    tr("הטלפון לא עבר ל\"%1\", והמיקרופון המסומן עדיין מקליט. נסו לנתק ולחבר אותו שוב.", "The phone did not switch to “%1”, and the marked microphone is still recording. Try unplugging and reconnecting it.", listOf(name)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Footnote(tr("המיקרופון המסומן הוא זה שמקליט עכשיו. אוזניות או מיקרופון שיתחברו יופיעו כאן אוטומטית.", "The marked microphone is the one recording now. Headphones or a microphone that connect will appear here automatically."))
        }

        SectionHeader(tr("עוצמת קליטה", "Input level"))
        SettingsGroup {
            item { Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) { LevelMeter(level, listening) } }
        }
        Footnote(
            if (listening) {
                tr("דברו ותראו את הפס זז. אם הוא לא זז, המיקרופון שנבחר לא שומע.", "Speak and watch the bar move. If it doesn’t move, the selected microphone isn’t hearing anything.")
            } else {
                tr("המד פעיל רק בזמן האזנה.", "The meter is only active while listening.")
            },
        )

        SectionHeader(tr("טיפים", "Tips"))
        SettingsGroup {
            item {
                Tip(
                    Icons.Rounded.Usb,
                    tr("מיקרופון USB‑C צריך להיות מסוג \"USB Audio\" (כמו רוב מיקרופוני הדש). אם הוא לא מופיע ברשימה, הטלפון לא מזהה אותו בכלל, ולא רק האפליקציה.", "A USB‑C microphone needs to be of the “USB Audio” type (like most lapel mics). If it doesn’t appear in the list, the phone isn’t recognizing it at all, not just the app."),
                )
            }
            item {
                Tip(
                    Icons.Rounded.Bluetooth,
                    tr("אוזניות Bluetooth מופיעות כשהן מחוברות. איכות ההקלטה דרכן נמוכה יותר מאשר מיקרופון חוטי.", "A Bluetooth headset appears once it’s connected. Recording quality through it is lower than a wired microphone."),
                )
            }
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** Read every 50 ms while captions run; empty, and said so, while they don't. */
@Composable
internal fun LevelMeter(level: () -> Float, active: Boolean) {
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
