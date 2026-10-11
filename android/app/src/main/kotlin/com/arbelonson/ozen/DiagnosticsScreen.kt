package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arbelonson.ozen.core.AudioLevelHistogram
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.tr
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

/**
 * The iPhone's Diagnostics screen, for whoever helps her with the phone:
 * what the captions are doing, what went wrong lately, and the report to
 * send or copy. Its words are technical on purpose. Kept current while it
 * is open, so the report sent is the one on screen.
 */
@Composable
internal fun DiagnosticsScreen(source: DiagnosticsSource, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val facts = source::diagnostics
    var now by remember { mutableStateOf(facts()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = facts()
        }
    }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val stats = now.stats
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("אבחון", "Diagnostics"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text(tr("חזרה", "Back")) }
            }
            Header(tr("מצב", "Status"))
            Value(tr("שלב", "Stage"), DiagnosticsReport.stage(now.phase))
            Value(tr("מנוע פעיל", "Active engine"), now.activeEngine?.displayName ?: "—")
            Value(tr("מודל Whisper", "Whisper model"), now.settings.whisperModelVariant)
            Value(tr("שפה", "Language"), now.settings.languageCode)
            now.phase.failure?.let { Value(tr("פרטי תקלה", "Failure details"), it.detail) }

            Header(tr("אודיו", "Audio"))
            Value(tr("מיקרופון נבחר", "Selected microphone"), now.selectedInput?.portName ?: "—")
            Value(tr("מיקרופונים זמינים", "Available microphones"), "${now.availableInputs.size}")
            for (input in now.availableInputs) {
                Text("${input.portName} · ${inputTypeName(input.portType)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Value(tr("חבילות אודיו", "Audio chunks"), "${stats.audioChunksReceived}")
            Value(tr("שניות אודיו", "Audio seconds"), format("%.1f", stats.audioSecondsReceived))
            Value(tr("החלפות מיקרופון", "Microphone changes"), "${stats.inputChanges}")
            Value(tr("המיקרופון נתקע", "Microphone stalls"), "${stats.audioStalls}")
            Value(tr("רמות קול (dBFS)", "Sound levels (dBFS)"), levels(stats.inputLevels))
            Value(tr("נשמע כדיבור", "Sounded like speech"), stats.speechShare?.let { format("%.0f%%", it * 100) } ?: "—")

            Header(tr("תמלול", "Transcription"))
            Value(tr("עדכונים מהמנוע", "Updates from engine"), "${stats.tokensReceived}")
            Value(tr("שורות שנסגרו", "Lines closed"), "${stats.segmentsCommitted}")
            Value(tr("שורות על המסך", "Lines on screen"), "${now.linesOnScreen}")
            Value(tr("פיגור כתוביות", "Caption lag"), stats.captionLagSeconds?.let { format(tr("%.1f שנ׳", "%.1f s"), it) } ?: "—")
            Value(tr("הפעלות מחדש", "Restarts"), "${stats.engineRestarts}")
            stats.sessionStartedAt?.let { started ->
                Value(tr("ההאזנה התחילה", "Listening started"), TranscriptHistoryStore.formattedClockTime(started, TimeZone.getDefault().getOffset((started * 1_000).toLong()) / 1_000))
            }

            Header(tr("התאוששות", "Recovery"))
            Value(tr("ניסיון חוזר אוטומטי", "Automatic retry"), DiagnosticsReport.retry(now.scheduledRetry, System.currentTimeMillis() / 1_000.0))
            Value(tr("שיחת טלפון תופסת את האודיו", "Phone call is using the audio"), if (now.interruptedBySystem) tr("כן", "Yes") else tr("לא", "No"))
            Value(tr("שמירת הגדרות", "Settings save"), if (now.settingsSaveFailed) tr("נכשלה", "Failed") else tr("תקינה", "OK"))
            Value(tr("שמירת שיחות", "Conversation history save"), if (now.historySaveFailure != null) tr("נכשלה", "Failed") else tr("תקינה", "OK"))
            Value(tr("הודעות בטלפון", "Phone notifications"), if (now.notificationsAllowed) tr("תקינות", "OK") else tr("כבויות בהגדרות של Android", "Turned off in Android settings"))
            Value(tr("רטט להתראות", "Alert vibration"), if (now.canVibrate) tr("תקין", "OK") else tr("לא נתמך במכשיר", "Not supported on this device"))
            Value(tr("חיבור לאינטרנט", "Internet connection"), DiagnosticsReport.network(now.network))

            Header(tr("אירועים אחרונים", "Recent events"))
            if (now.events.isEmpty()) {
                Text(tr("עוד לא קרה כלום", "Nothing has happened yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // English, so left to right even with the app in Hebrew.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(modifier = Modifier.testTag(DIAGNOSTICS_EVENTS)) {
                        for (line in now.events.takeLast(RECENT_EVENTS).reversed()) {
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Text(tr("החדש ביותר למעלה. הדוח המועתק כולל את כל הרשימה.", "Newest at the top. The copied report includes the full list."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Header(tr("יומן, כולל הפעלות קודמות", "Journal, previous runs included"))
            OutlinedButton(
                onClick = {
                    source.markProblem()
                    now = facts()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("לסמן בעיה עכשיו", "Mark a problem now"))
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = Modifier.testTag(DIAGNOSTICS_JOURNAL)) {
                    for (line in now.journal.takeLast(JOURNAL_LINES).reversed()) {
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (now.hasMarkedCaptionLines) {
                TextButton(
                    onClick = {
                        source.deleteMarkedCaptionLines()
                        now = facts()
                    },
                ) {
                    Text(tr("מחיקת שורות הכתוביות שנשמרו כאן", "Delete the caption lines kept here"), color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח. כשמסמנים בעיה נשמרות גם השורות האחרונות של הכתוביות.", "Kept on the phone even when the app closes, and sent only with the report. Marking a problem also keeps the last few caption lines."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (now.problemClips.isNotEmpty()) {
                Header(tr("הקול מהבעיות שסומנו", "Sound from marked problems"))
                for (clip in now.problemClips) {
                    ClipRow(
                        DiagnosticsReport.clipTitle(clip.name),
                        onSend = { context.startActivity(clipSheet(context, clip)) },
                        onDelete = {
                            source.deleteProblemClip(clip)
                            now = facts()
                        },
                    )
                }
                Text(
                    tr("כשמסמנים בעיה נשמרות 30 השניות האחרונות של הקול, רק בטלפון, כדי להשוות מה נאמר למה שנכתב. נשמרות עד 5; שליחה רק כשלוחצים עליהן. לחיצה ארוכה מוחקת.", "Marking a problem keeps the last 30 seconds of sound, on the phone only, to compare what was said with what was shown. Up to 5 are kept, and they’re sent only when you tap one. Hold one to delete it."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Header(tr("מודל ומילים", "Model and words"))
            Value(tr("מצב המודל", "Model state"), if (now.modelInstalled) tr("מותקן (לא אומת)", "Installed (unverified)") else tr("לא הורד", "Not downloaded"))
            Value(tr("שמות ומילים", "Names and words"), "${now.settings.vocabulary.size}")
            Value(tr("התראות מילים", "Word alerts"), "${now.settings.keywordAlerts.count { it.isEnabled }}")

            Header(tr("מכשיר", "Device"))
            Value(tr("חום", "Temperature"), DiagnosticsReport.temperature(now.thermalStatus))
            Value(tr("חיסכון בסוללה", "Battery Saver"), if (now.batterySaver) tr("פעיל", "On") else tr("כבוי", "Off"))
            Value(tr("סוללה", "Battery"), DiagnosticsReport.battery(now.batteryPercent, now.charging))
            Value(tr("מקום פנוי", "Free space"), ByteSize.text(now.freeBytes))
            Value(tr("זיכרון", "Memory"), DiagnosticsReport.memory(now.memoryInUseBytes, now.memoryAvailableBytes))
            Value(tr("דגם", "Model"), "${Build.MANUFACTURER} ${Build.MODEL}")
            Value("Android", Build.VERSION.RELEASE)
            Value(tr("אפליקציה", "App"), now.appVersion)

            OutlinedButton(onClick = { context.startActivity(reportSheet(DiagnosticsReport.text(facts()))) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(tr("שליחת הדוח", "Send report"))
            }
            OutlinedButton(
                onClick = {
                    copyReport(context, DiagnosticsReport.text(facts()))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (copied) tr("הועתק", "Copied") else tr("העתקת הדוח", "Copy report"))
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}

@Composable
private fun Value(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


private fun levels(histogram: AudioLevelHistogram): String {
    val quiet = histogram.decibels(0.1) ?: return "—"
    val middle = histogram.decibels(0.5) ?: return "—"
    val loud = histogram.decibels(0.9) ?: return "—"
    return tr("שקט %1 · אמצע %2 · חזק %3", "quiet %1 · mid %2 · loud %3", listOf("$quiet", "$middle", "$loud"))
}

/** A kept sound: tapped, it goes out through the share sheet; held, or from a screen reader, it can be deleted. */
@Composable
private fun ClipRow(title: String, onSend: () -> Unit, onDelete: () -> Unit) {
    val delete = tr("מחיקה", "Delete")
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            title,
            modifier = Modifier
                .fillMaxWidth()
                .onHold { menu = true }
                .clickable(onClick = onSend)
                .semantics { customActions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }) }
                .padding(vertical = 10.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(delete) }, onClick = { menu = false; onDelete() })
        }
    }
}

private fun clipSheet(context: Context, clip: File): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".share", clip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null)
}

private fun reportSheet(report: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, tr("דוח אבחון מאוזן", "Ozen diagnostics report"))
        .putExtra(Intent.EXTRA_TEXT, report)
    return Intent.createChooser(send, null)
}

private fun copyReport(context: Context, report: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tr("דוח אבחון מאוזן", "Ozen diagnostics report"), report))
}

private fun format(pattern: String, value: Double): String = String.format(Locale.ROOT, pattern, value)

private const val RECENT_EVENTS = 12
private const val JOURNAL_LINES = 15
internal const val DIAGNOSTICS_EVENTS = "diagnosticsEvents"
internal const val DIAGNOSTICS_JOURNAL = "diagnosticsJournal"
