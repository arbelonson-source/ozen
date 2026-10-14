package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.SettingRow
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.SettingsGroupScope
import com.arbelonson.ozen.ui.TonalButton
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The iPhone's Diagnostics screen, for whoever helps her with the phone:
 * what the captions are doing, what went wrong lately, and the report to
 * send or copy. Its words are technical on purpose. Kept current while it
 * is open, so the report sent is the one on screen.
 */
@Composable
internal fun DiagnosticsScreen(source: DiagnosticsSource, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val facts = { source.diagnostics() }
    var now by remember { mutableStateOf(facts()) }
    // Bumped by a button that changed what the screen shows, so a refresh
    // still reading the journal is dropped instead of putting back the one
    // from before.
    var reread by remember { mutableIntStateOf(0) }
    val refresh = {
        now = facts()
        reread += 1
    }
    // The journal is a file read and most of a refresh's time, a tenth of
    // a second and more once it fills up: read on the main thread every
    // two seconds, the screen stopped drawing that long each time.
    LaunchedEffect(reread) {
        while (true) {
            delay(2_000)
            val journal = withContext(Dispatchers.IO) { source.readJournal() }
            now = source.diagnostics(journal)
        }
    }
    // The iPhone's level row moves about twenty times a second, so it shows
    // the microphone hearing a word or a tap on the phone as it happens.
    var level by remember { mutableFloatStateOf(now.inputLevel) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            level = source.inputLevelNow()
        }
    }
    var copied by remember { mutableStateOf(false) }
    var sendingHome by remember { mutableStateOf(false) }
    var sentHome by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stats = now.stats
    SettingsPageFrame(tr("אבחון", "Diagnostics"), onClose = onClose, closeLabel = tr("חזרה", "Back")) {
        SectionHeader(tr("מצב", "Status"))
        SettingsGroup {
            value(tr("שלב", "Stage"), DiagnosticsReport.stage(now.phase))
            value(tr("מנוע פעיל", "Active engine"), now.activeEngine?.displayName ?: "—")
            value(tr("מודל Whisper", "Whisper model"), now.settings.whisperModelVariant)
            value(tr("שפה", "Language"), now.settings.languageCode)
            now.phase.failure?.let { value(tr("פרטי תקלה", "Failure details"), it.detail) }
        }

        SectionHeader(tr("אודיו", "Audio"))
        SettingsGroup {
            value(tr("מיקרופון נבחר", "Selected microphone"), now.selectedInput?.portName ?: "—")
            value(tr("מיקרופונים זמינים", "Available microphones"), "${now.availableInputs.size}")
            for (input in now.availableInputs) {
                item { Note("${input.portName} · ${inputTypeName(input.portType)}") }
            }
            value(tr("חבילות אודיו", "Audio chunks"), "${stats.audioChunksReceived}")
            value(tr("שניות אודיו", "Audio seconds"), format("%.1f", stats.audioSecondsReceived))
            value(tr("החלפות מיקרופון", "Microphone changes"), "${stats.inputChanges}")
            value(tr("המיקרופון נתקע", "Microphone stalls"), "${stats.audioStalls}")
            value(tr("רמות קול (dBFS)", "Sound levels (dBFS)"), levels(stats.inputLevels))
            value(tr("עוצמה עכשיו", "Level now"), format("%.0f%%", level * 100.0))
            value(tr("נשמע כדיבור", "Sounded like speech"), stats.speechShare?.let { format("%.0f%%", it * 100) } ?: "—")
            value(
                tr("זיהוי צלילים", "Sound detection"),
                when {
                    stats.soundDetectionRunning -> tr("פועל", "Running")
                    now.phase.isListening -> tr("נעצר", "Stopped")
                    else -> "—"
                },
            )
        }

        val misses = DiagnosticsReport.nearMisses(now.soundNearMisses)
        if (misses.isNotEmpty()) {
            SectionHeader(tr("צלילים שנשמעו חלש מדי להתראה", "Sounds heard too faint to alert"))
            SettingsGroup {
                for ((name, heard) in misses) value(name, heard)
            }
            Footnote(DiagnosticsReport.nearMissNote(now.soundAlertConfidence))
        }

        SectionHeader(tr("תמלול", "Transcription"))
        SettingsGroup {
            value(tr("עדכונים מהמנוע", "Updates from engine"), "${stats.tokensReceived}")
            value(tr("שורות שנסגרו", "Lines closed"), "${stats.segmentsCommitted}")
            value(tr("שורות על המסך", "Lines on screen"), "${now.linesOnScreen}")
            value(tr("פיגור כתוביות", "Caption lag"), stats.captionLagSeconds?.let { format(tr("%.1f שנ׳", "%.1f s"), it) } ?: "—")
            value(tr("הפעלות מחדש", "Restarts"), "${stats.engineRestarts}")
            value(tr("דוברים שזוהו", "Speakers identified"), "${now.speakersIdentified}")
            value(tr("דוברים חדשים מאז שהאפליקציה נפתחה", "New speakers since the app opened"), "${stats.speakerClustersOpened}")
            stats.sessionStartedAt?.let { started ->
                value(tr("ההאזנה התחילה", "Listening started"), TranscriptHistoryStore.formattedClockTime(started, TimeZone.getDefault().getOffset((started * 1_000).toLong()) / 1_000))
            }
        }

        SectionHeader(tr("התאוששות", "Recovery"))
        SettingsGroup {
            value(tr("ניסיון חוזר אוטומטי", "Automatic retry"), DiagnosticsReport.retry(now.scheduledRetry, System.currentTimeMillis() / 1_000.0))
            value(tr("שיחת טלפון תופסת את האודיו", "Phone call is using the audio"), if (now.interruptedBySystem) tr("כן", "Yes") else tr("לא", "No"))
            value(tr("שמירת הגדרות", "Settings save"), if (now.settingsSaveFailed) tr("נכשלה", "Failed") else tr("תקינה", "OK"))
            value(tr("שמירת שיחות", "Conversation history save"), if (now.historySaveFailure != null) tr("נכשלה", "Failed") else tr("תקינה", "OK"))
            value(tr("הודעות בטלפון", "Phone notifications"), if (now.notificationsAllowed) tr("תקינות", "OK") else tr("כבויות בהגדרות של Android", "Turned off in Android settings"))
            value(tr("רטט להתראות", "Alert vibration"), if (now.canVibrate) tr("תקין", "OK") else tr("לא נתמך במכשיר", "Not supported on this device"))
            value(tr("חיבור לאינטרנט", "Internet connection"), DiagnosticsReport.network(now.network))
        }

        SectionHeader(tr("אירועים אחרונים", "Recent events"))
        SettingsGroup {
            item {
                if (now.events.isEmpty()) {
                    Note(tr("עוד לא קרה כלום", "Nothing has happened yet"))
                } else {
                    // English, so left to right even with the app in Hebrew.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(modifier = Modifier.testTag(DIAGNOSTICS_EVENTS).padding(16.dp)) {
                            for (line in now.events.takeLast(RECENT_EVENTS).reversed()) {
                                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        Footnote(tr("החדש ביותר למעלה. הדוח המועתק כולל את כל הרשימה.", "Newest at the top. The copied report includes the full list."))

        SectionHeader(tr("יומן, כולל הפעלות קודמות", "Journal, previous runs included"))
        TonalButton(
            tr("לסמן בעיה עכשיו", "Mark a problem now"),
            onClick = {
                source.markProblem()
                refresh()
            },
            icon = Icons.Rounded.Flag,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsGroup {
            item {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(modifier = Modifier.testTag(DIAGNOSTICS_JOURNAL).padding(16.dp)) {
                        for (line in now.journal.takeLast(JOURNAL_LINES).reversed()) {
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (now.hasMarkedCaptionLines) {
                item {
                    TextButton(
                        onClick = {
                            source.deleteMarkedCaptionLines()
                            refresh()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(tr("מחיקת שורות הכתוביות שנשמרו כאן", "Delete the caption lines kept here"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Footnote(
            tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח. כשמסמנים בעיה נשמרות גם השורות האחרונות של הכתוביות.", "Kept on the phone even when the app closes, and sent only with the report. Marking a problem also keeps the last few caption lines."),
        )

        if (now.problemClips.isNotEmpty()) {
            SectionHeader(tr("הקול מהבעיות שסומנו", "Sound from marked problems"))
            SettingsGroup(dividerInset = 56.dp) {
                for (clip in now.problemClips) {
                    item {
                        ClipRow(
                            DiagnosticsReport.clipTitle(clip.name),
                            onSend = { context.startActivity(clipSheet(context, clip)) },
                            onDelete = {
                                source.deleteProblemClip(clip)
                                refresh()
                            },
                        )
                    }
                }
            }
            Footnote(
                tr("כשמסמנים בעיה נשמרות 30 השניות האחרונות של הקול, רק בטלפון, כדי להשוות מה נאמר למה שנכתב. נשמרות עד 5; שליחה רק כשלוחצים עליהן. לחיצה ארוכה מוחקת.", "Marking a problem keeps the last 30 seconds of sound, on the phone only, to compare what was said with what was shown. Up to 5 are kept, and they’re sent only when you tap one. Hold one to delete it."),
            )
        }

        SectionHeader(tr("מודל ומילים", "Model and words"))
        SettingsGroup {
            value(tr("מצב המודל", "Model state"), if (now.modelInstalled) tr("מותקן (לא אומת)", "Installed (unverified)") else tr("לא הורד", "Not downloaded"))
            value(tr("שמות ומילים", "Names and words"), "${now.settings.vocabulary.size}")
            value(tr("התראות מילים", "Word alerts"), "${now.settings.keywordAlerts.count { it.isEnabled }}")
        }

        SectionHeader(tr("מכשיר", "Device"))
        SettingsGroup {
            value(tr("חום", "Temperature"), DiagnosticsReport.temperature(now.thermalStatus))
            value(tr("חיסכון בסוללה", "Battery Saver"), if (now.batterySaver) tr("פעיל", "On") else tr("כבוי", "Off"))
            value(tr("סוללה", "Battery"), DiagnosticsReport.battery(now.batteryPercent, now.charging))
            value(tr("מקום פנוי", "Free space"), ByteSize.text(now.freeBytes))
            value(tr("זיכרון", "Memory"), DiagnosticsReport.memory(now.memoryInUseBytes, now.memoryAvailableBytes))
            value(tr("דגם", "Model"), "${Build.MANUFACTURER} ${Build.MODEL}")
            value("Android", Build.VERSION.RELEASE)
            value(tr("אפליקציה", "App"), now.appVersion)
        }

        Spacer(Modifier.height(OzenSpacing.Unit))
        PrimaryButton(tr("שליחת הדוח", "Send report"), onClick = { context.startActivity(reportSheet(DiagnosticsReport.text(facts()))) }, icon = Icons.Rounded.Share, modifier = Modifier.fillMaxWidth())
        TonalButton(
            if (copied) tr("הועתק", "Copied") else tr("העתקת הדוח", "Copy report"),
            onClick = {
                copyReport(context, DiagnosticsReport.text(facts()))
                copied = true
            },
            icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (HomeServer.url(now.settings.homeServerAddress) != null && now.homeServerCodeSaved) {
            TonalButton(
                when (sentHome) {
                    true -> tr("הדוח נשמר במחשב בבית", "Saved on the home computer")
                    false -> tr("המחשב בבית לא קיבל את הדוח. נסו שוב", "The home computer didn’t get it. Try again")
                    null -> tr("שליחת הדוח למחשב בבית", "Send the report to the home computer")
                },
                onClick = {
                    sendingHome = true
                    val report = DiagnosticsReport.text(facts())
                    scope.launch {
                        try {
                            sentHome = source.sendReportHome(report)
                        } finally {
                            sendingHome = false
                        }
                    }
                },
                icon = Icons.Rounded.Computer,
                enabled = !sendingHome,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun SettingsGroupScope.value(label: String, value: String) {
    item { ValueRow(label, value) }
}

@Composable
private fun ValueRow(label: String, value: String) {
    // A long value, a failure's details say, took the whole row and left
    // its label a letter wide; it goes under the label instead.
    LabelAndValue(
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        value = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        gap = 12.dp,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
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
        SettingRow(
            title,
            modifier = Modifier
                .onHold { menu = true }
                .semantics { customActions = listOf(CustomAccessibilityAction(delete) { onDelete(); true }) },
            icon = Icons.Rounded.AudioFile,
            onClick = onSend,
            trailing = { Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
