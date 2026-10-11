package com.arbelonson.ozen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.RecordingImport
import com.arbelonson.ozen.core.SavedSpeaker
import com.arbelonson.ozen.core.SpeakerProfile
import com.arbelonson.ozen.core.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What Settings asks of the captions about the people saved by voice, as the iPhone's view model answers it. */
interface SpeakerBook {
    /** The microphone's level now, 0 to 1, for the meter while a voice is recorded. */
    val inputLevel: Float

    /** Every voice print saved under this name predates the current voice model, so the person is never named until recorded again. */
    fun needsNewRecording(speaker: SavedSpeaker): Boolean

    /** Every voice print of [name] takes the new name, and so do the lines on screen and in saved conversations. */
    fun renameSpeaker(name: String, to: String)

    /** Every voice print of [name] is deleted, and the lines on screen stop showing the name. */
    fun removeSpeaker(name: String)

    fun setSpeakerSeparation(threshold: Float)

    /**
     * Records [seconds] of the person talking, captions paused meanwhile,
     * and saves the voice print under [name]. False when the recording was
     * too short or too quiet for a print, or was stopped.
     */
    suspend fun recordSpeaker(name: String, seconds: Double, onProgress: (Double) -> Unit): Boolean

    /**
     * Saves one voice print per recording, under the name in its file
     * name ("Savta 2.m4a" is Savta), and says who was added and which
     * files were not: no name, or too little speech.
     */
    suspend fun addRecordings(recordings: List<Uri>): RecordingImport.Result
}

/**
 * The saved people, each once however many recordings they have, as on
 * the iPhone: a tap renames the person everywhere, Delete asks first, and
 * a person only an older version recorded says how to fix it.
 */
@Composable
internal fun SavedSpeakersSection(profiles: List<SpeakerProfile>, speakers: SpeakerBook, onAdd: () -> Unit) {
    SectionHeader(tr("דוברים שמורים", "Saved speakers"))
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<RecordingImport.Result?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                imported = speakers.addRecordings(picked)
            } finally {
                importing = false
            }
        }
    }
    for (speaker in SavedSpeaker.grouping(profiles)) {
        Row(modifier = Modifier.fillMaxWidth().testTag("savedSpeaker:${speaker.name}"), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = tr("שינוי שם", "Rename")) { renaming = speaker.name }
                    .padding(vertical = 8.dp),
            ) {
                Text(speaker.name, style = MaterialTheme.typography.bodyLarge)
                // Voices saved before the September 2026 voice model are
                // never matched again, silently.
                if (speakers.needsNewRecording(speaker)) {
                    Text(
                        tr("נשמר בגרסה ישנה ולא מזוהה. כדי לתקן: \"הוספת דובר\" באותו שם.", "Saved by an older version and not recognized. To fix: “Add speaker” with the same name."),
                        style = MaterialTheme.typography.bodySmall,
                        color = cautionOrange(),
                    )
                }
            }
            TextButton(onClick = { removing = speaker.name }) { Text(tr("מחיקה", "Delete")) }
        }
    }
    TextButton(onClick = onAdd) { Text(tr("הוספת דובר", "Add speaker")) }
    TextButton(onClick = { pick.launch(arrayOf("audio/*")) }, enabled = !importing) {
        Text(tr("הוספה מהקלטות קיימות", "Add from existing recordings"))
        if (importing) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(16.dp), strokeWidth = 2.dp)
    }
    Footnote(
        tr(
            "דובר שמור מזוהה בשמו מהמשפט הראשון. אפשר גם להשאיר אצבע על שורה בכתוביות, לבחור \"מי מדבר?\" ולתת שם אחרי שהאדם כבר דיבר. בהוספה מהקלטות, כל קובץ נשמר בשם שבשם הקובץ: \"סבתא 1\" ו-\"סבתא 2\" הם שתי הקלטות של סבתא. בכל קובץ רק אדם אחד מדבר.",
            "A saved speaker is recognized by name from the first sentence. You can also press and hold a line in the captions, choose “Who is speaking?”, and give a name after the person has already spoken. When adding from recordings, each file is saved under the name in its file name: “Savta 1” and “Savta 2” are two recordings of Savta. Only one person should speak in each file.",
        ),
    )
    imported?.let { result ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text(tr("הקלטות", "Recordings")) },
            text = { Text(result.summary) },
            confirmButton = { TextButton(onClick = { imported = null }) { Text(tr("אישור", "OK")) } },
        )
    }
    renaming?.let { name ->
        RenameSpeakerDialog(
            name,
            onRename = { newName ->
                renaming = null
                speakers.renameSpeaker(name, newName)
            },
            onDismiss = { renaming = null },
        )
    }
    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr("למחוק את %1 מהדוברים השמורים?", "Delete %1 from saved speakers?", listOf(name))) },
            text = { Text(tr("כדי שיזוהו שוב בשמם צריך להקליט את הקול מחדש.", "To be recognized by name again, their voice needs to be recorded again.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        speakers.removeSpeaker(name)
                    },
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

@Composable
private fun RenameSpeakerDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("שינוי שם", "Rename")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("השם החדש יופיע גם על השורות שכבר בכתוביות.", "The new name will also appear on lines already in the captions."))
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(tr("שם", "Name")) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(draft) }) { Text(tr("שמירה", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } },
    )
}

/**
 * As on the iPhone: the buzz when talk resumes, and how readily two
 * voices count as one person. The separation is kept when the slider is
 * let go, as two decimals, the steps it moves in.
 */
@Composable
internal fun BehaviourSection(buzzWhenTalkResumes: Boolean, onBuzz: (Boolean) -> Unit, separation: Float, onSeparation: (Float) -> Unit) {
    SectionHeader(tr("התנהגות", "Behavior"))
    SwitchRow(tr("רטט כשמישהו מתחיל לדבר אחרי שקט", "Vibrate when someone starts talking after silence"), buzzWhenTalkResumes, onChange = onBuzz)
    var shown by remember(separation) { mutableFloatStateOf(separation) }
    val label = tr("רגישות הפרדת דוברים", "Speaker separation sensitivity")
    val value = String.format(Locale.ROOT, "%.2f", shown)
    Row(modifier = Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = shown,
        onValueChange = { shown = it },
        onValueChangeFinished = { onSeparation((shown * 100).roundToInt() / 100f) },
        valueRange = 0.2f..0.95f,
        steps = 74,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = value
        },
    )
    Row {
        Text(tr("מאחד יותר", "More merging"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(tr("מפריד יותר", "More separating"), style = MaterialTheme.typography.bodySmall)
    }
    val usual = AppSettings.default.speakerSimilarityThreshold
    if (abs(shown - usual) > 0.005f) {
        TextButton(onClick = { onSeparation(usual) }) { Text(tr("חזרה להגדרה הרגילה", "Back to the usual setting")) }
    }
    Footnote(
        tr(
            "אם האפליקציה ממציאה \"דובר 3\" לאדם שכבר דיבר, הזיזו לכיוון \"מאחד יותר\". אם היא מאחדת שני אנשים, הזיזו לכיוון \"מפריד יותר\".",
            "If the app invents “Speaker 3” for someone who already spoke, move toward “More merging”. If it merges two people, move toward “More separating”.",
        ),
    )
}
