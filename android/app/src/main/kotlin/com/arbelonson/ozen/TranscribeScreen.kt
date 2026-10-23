package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptFormats
import com.arbelonson.ozen.core.TranscriptionProgress
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.OzenScreen
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.ProgressCard
import com.arbelonson.ozen.ui.TonalButton

const val TRANSCRIBE_START = "transcribe-start"
const val TRANSCRIBE_CANCEL = "transcribe-cancel"
const val TRANSCRIBE_LINE = "transcribe-line"
const val TRANSCRIBE_SHARE = "transcribe-share"
const val TRANSCRIBE_SUBTITLES = "transcribe-subtitles"

private val PRIVACY_LINE get() = tr("ההקלטה נשארת בטלפון. שום דבר לא נשלח לשום מקום.", "The recording stays on this phone. Nothing is sent anywhere.")

@Composable
internal fun TranscribeScreen(transcriber: FileTranscriber, state: FileTranscription, onClose: () -> Unit) {
    var confirmingStop by rememberSaveable { mutableStateOf(false) }
    val working = state is FileTranscription.Working
    BackHandler { if (working) confirmingStop = true else onClose() }
    val close = { if (working) confirmingStop = true else onClose() }
    when (state) {
        FileTranscription.Idle -> Unit
        is FileTranscription.Ready -> ReadyPage(transcriber, state, close)
        is FileTranscription.Working -> WorkingPage(transcriber, state)
        is FileTranscription.Done -> DonePage(state, close)
        is FileTranscription.Failed -> OzenScreen(tr("המרת הקלטה לטקסט", "Transcribe a recording"), onClose = close, closeLabel = tr("סגירה", "Close")) {
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            Text(state.problem.message, modifier = Modifier.testTag("transcribe-problem"))
            Text(PRIVACY_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text(tr("לעצור את ההמרה לטקסט?", "Stop turning the recording into text?")) },
            text = { Text(tr("מה שכבר הומר לא יישמר.", "What was already turned into text won’t be saved.")) },
            confirmButton = { TextButton(onClick = { confirmingStop = false; transcriber.cancel() }) { Text(tr("עצירה", "Stop")) } },
            dismissButton = { TextButton(onClick = { confirmingStop = false }) { Text(tr("להמשיך", "Keep going")) } },
        )
    }
}

@Composable
private fun ReadyPage(transcriber: FileTranscriber, ready: FileTranscription.Ready, close: () -> Unit) {
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var stoppingCaptions by rememberSaveable { mutableStateOf(false) }
    val model = ready.model
    OzenScreen(tr("המרת הקלטה לטקסט", "Transcribe a recording"), onClose = close, closeLabel = tr("סגירה", "Close")) {
        Text(ready.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }.testTag("transcribe-name"))
        ready.durationSeconds?.let { Text(tr("אורך: %1", "Length: %1", listOf(TranscriptFormats.clock(it)))) }
        ChoiceRow(tr("שפה", "Language"), ready.language.nativeName, "transcribe-language") { choosingLanguage = true }
        if (model != null) {
            ChoiceRow(tr("מודל", "Model"), model.displayName, "transcribe-model", enabled = ready.downloaded.size > 1) { choosingModel = true }
            Text(model.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                tr(
                    "אין בטלפון מודל לשפה %1. אוזן יוריד את \"%2\" (%3 MB) לפני ההמרה לטקסט.",
                    "There is no model for %1 on this phone. Ozen will download “%2” (%3 MB) before turning the recording into text.",
                    listOf(ready.language.nativeName, ready.toDownload.displayName, "${ready.toDownload.megabytes}"),
                ),
                modifier = Modifier.testTag("transcribe-needs-model"),
            )
        }
        Text(
            tr("הקלטה ארוכה לוקחת זמן. כדאי להשאיר את אוזן פתוח ואת הטלפון מחובר לטעינה.", "A long recording takes a while. Keep Ozen open, and the phone charging."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(PRIVACY_LINE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("transcribe-privacy"))
        PrimaryButton(
            if (model != null) tr("המרה לטקסט", "Transcribe") else tr("הורדה והמרה לטקסט", "Download and transcribe"),
            onClick = { if (ready.captionsRunning) stoppingCaptions = true else transcriber.start() },
            modifier = Modifier.fillMaxWidth().testTag(TRANSCRIBE_START),
        )
    }
    if (choosingLanguage) {
        PickDialog(
            tr("שפת ההקלטה", "Language of the recording"),
            UILanguage.entries.map { it.nativeName to (it == ready.language) },
            onPick = { choosingLanguage = false; transcriber.chooseLanguage(UILanguage.entries[it]) },
            onDismiss = { choosingLanguage = false },
        )
    }
    if (choosingModel) {
        PickDialog(
            tr("מודל להמרה", "Model to use"),
            ready.downloaded.map { it.displayName to (it.file == model?.file) },
            onPick = { choosingModel = false; transcriber.chooseModel(ready.downloaded[it].file) },
            onDismiss = { choosingModel = false },
        )
    }
    if (stoppingCaptions) {
        AlertDialog(
            onDismissRequest = { stoppingCaptions = false },
            title = { Text(tr("הכתוביות פועלות עכשיו", "Captions are on right now")) },
            text = { Text(tr("מודל אחד לא יכול להפעיל גם כתוביות חיות וגם המרת הקלטה. לעצור את הכתוביות?", "One model can’t run live captions and a recording at the same time. Stop the captions?")) },
            confirmButton = {
                TextButton(onClick = { stoppingCaptions = false; transcriber.stopCaptions(); transcriber.start() }, modifier = Modifier.testTag("transcribe-stop-captions")) {
                    Text(tr("עצירת הכתוביות", "Stop captions"))
                }
            },
            dismissButton = { TextButton(onClick = { stoppingCaptions = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp).testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickDialog(title: String, choices: List<Pair<String, Boolean>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEachIndexed { index, (label, chosen) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(index) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen, onClick = null)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}

@Composable
private fun WorkingPage(transcriber: FileTranscriber, working: FileTranscription.Working) {
    val percent = working.fraction?.let(TranscriptionProgress::percent)
    val title = when (working.stage) {
        FileTranscription.Working.Stage.Downloading -> tr("מורידים את המודל", "Downloading the model")
        FileTranscription.Working.Stage.Loading -> tr("מכינים את המודל", "Getting the model ready")
        FileTranscription.Working.Stage.Transcribing -> tr("ממירים את ההקלטה לטקסט", "Turning the recording into text")
    }
    val left = working.timeLeftSeconds?.let(TranscriptionProgress::timeLeftText)
    val detail = listOfNotNull(working.model.displayName, left).joinToString(" · ")
    OzenScreen(working.name, closeLabel = "", scrolls = false) {
        ProgressCard(
            Icons.Rounded.GraphicEq,
            title,
            modifier = Modifier.testTag("transcribe-progress"),
            percent = percent,
            detail = detail,
        ) {
            TonalButton(tr("ביטול", "Cancel"), onClick = transcriber::cancel, modifier = Modifier.testTag(TRANSCRIBE_CANCEL))
        }
        Text(PRIVACY_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinesList(working.lines, working.language.languageCode, follow = true)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DonePage(done: FileTranscription.Done, close: () -> Unit) {
    val context = LocalContext.current
    OzenScreen(tr("ההקלטה הומרה לטקסט", "Recording turned into text"), onClose = close, closeLabel = tr("סיום", "Done"), scrolls = false) {
        Text(done.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }.testTag("transcribe-name"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { context.startActivity(transcriptShareSheet(context, done.name, done.lines)) },
                label = { Text(tr("שיתוף כטקסט", "Share as text")) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                modifier = Modifier.testTag(TRANSCRIBE_SHARE),
            )
            AssistChip(
                onClick = { context.startActivity(subtitleShareSheet(context, done.name, done.lines, SubtitleFormat.Srt)) },
                label = { Text(tr("כתוביות (SRT)", "Subtitles (SRT)")) },
                leadingIcon = { Icon(Icons.Rounded.Subtitles, contentDescription = null) },
                modifier = Modifier.testTag(TRANSCRIBE_SUBTITLES),
            )
        }
        val notSaved = done.history == HistorySave.Failed
        Text(
            done.history.message,
            style = if (notSaved) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = if (notSaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("transcribe-saved"),
        )
        if (done.endMissing) {
            Text(
                tr("סוף ההקלטה לא נקרא, ולכן הטקסט נעצר מוקדם.", "The end of this recording couldn’t be read, so the text stops early."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("transcribe-end-missing"),
            )
        }
        Text(tr("מודל: %1", "Model: %1", listOf(done.model.displayName)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(PRIVACY_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinesList(done.lines, done.language.languageCode, follow = false)
    }
}

@Composable
private fun LinesList(lines: List<TranscribedLine>, languageCode: String, follow: Boolean) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (follow && lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lines) { line ->
            Row(Modifier.fillMaxWidth().testTag(TRANSCRIBE_LINE), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(TranscriptFormats.clock(line.startSeconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                Text(
                    captionText(CaptionLayout.directed(line.text, languageCode), emphasizeNumbers = true, numberColor = null),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
