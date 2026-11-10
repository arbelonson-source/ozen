package com.arbelonson.ozen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.DialogText
import com.arbelonson.ozen.ui.GroupFootnote
import com.arbelonson.ozen.ui.OzenScreen
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.ProgressCard
import com.arbelonson.ozen.ui.SectionHeader
import com.arbelonson.ozen.ui.TonalButton
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val RECORDING_SECONDS = 30

/**
 * A voice being recorded, held by the app rather than the screen, as the
 * iPhone's recording task: turning the phone partway through used to stop
 * it, nothing saved and nothing said. Its outcome names the screen that
 * started it, so a later "New speaker" isn't closed by an earlier save.
 */
internal object SpeakerRecordings {
    class Outcome(val screen: String, val saved: Boolean)

    private val scope = MainScope()
    private var job: Job? = null

    /** How far the recording is, 0 to 1, or null while none runs. */
    val progress = MutableStateFlow<Double?>(null)
    val outcome = MutableStateFlow<Outcome?>(null)

    fun start(screen: String, speakers: SpeakerBook, name: String) {
        job?.cancel()
        outcome.value = null
        progress.value = 0.0
        job = scope.launch {
            val mine = coroutineContext.job
            try {
                val saved = speakers.recordSpeaker(name, RECORDING_SECONDS.toDouble()) { if (job === mine) progress.value = it }
                if (saved || isActive) outcome.value = Outcome(screen, saved)
            } finally {
                if (job === mine) {
                    job = null
                    progress.value = null
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Whether Ozen may use the microphone; device tests stand in for a refusal. */
    var microphoneAllowed: (Context) -> Boolean = { it.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    /** Asks the phone for the microphone; device tests give the answer themselves. */
    var askForMicrophone: (ask: () -> Unit, answer: (Boolean) -> Unit) -> Unit = { ask, _ -> ask() }
}

@Composable
fun SpeakerEnrollmentScreen(speakers: SpeakerBook, onClose: () -> Unit) {
    val screen = rememberSaveable { UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf("") }
    val progress by SpeakerRecordings.progress.collectAsState()
    val outcome by SpeakerRecordings.outcome.collectAsState()
    val recording = progress != null
    val mine = outcome?.takeIf { it.screen == screen }
    LaunchedEffect(mine) {
        if (mine?.saved == true) {
            SpeakerRecordings.outcome.value = null
            onClose()
        }
    }
    // Once the half minute is in, closing leaves the voiceprint to be saved:
    // stopping then threw away a finished recording, nothing said.
    val cancel = {
        if ((SpeakerRecordings.progress.value ?: 0.0) < 1.0) SpeakerRecordings.stop()
        onClose()
    }
    val start = { SpeakerRecordings.start(screen, speakers, name.trim()) }
    // Without the microphone the half minute recorded nothing and ended as
    // "too short or too quiet". The phone is asked first, and a refusal
    // says what is missing and where to allow it.
    val context = LocalContext.current
    var noMicrophone by rememberSaveable { mutableStateOf(false) }
    // A second tap while the phone's question is on its way would ask again
    // and start a second recording over the first once both are answered.
    var askingMicrophone by rememberSaveable { mutableStateOf(false) }
    val answered = { granted: Boolean ->
        askingMicrophone = false
        if (granted) start() else noMicrophone = true
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), answered)
    val record = {
        if (SpeakerRecordings.microphoneAllowed(context)) {
            start()
        } else if (!askingMicrophone) {
            askingMicrophone = true
            SpeakerRecordings.askForMicrophone({ askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }, answered)
        }
    }
    // As the iPhone's sheet, which can't be swiped away mid-recording: a
    // stray back gesture would throw away half a minute of her voice. Read
    // as it is now, not as last drawn: a gesture right after the tap on
    // Record came before the screen showed the recording.
    BackHandler { if (SpeakerRecordings.progress.value == null) cancel() }
    OzenScreen(tr("דובר חדש", "New speaker"), onClose = cancel, closeLabel = tr("ביטול", "Cancel")) {
        SectionHeader(tr("שם", "Name"))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(tr("לדוגמה: סבתא", "For example: Grandma")) },
            singleLine = true,
            enabled = !recording,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionHeader(tr("הקלטה", "Recording"))
        // Read before Record is pressed, not after: the half minute is
        // running by then, and a screen reader reading it out goes into the
        // recording.
        GroupFootnote(tr("בקשו מהאדם לדבר בטבעיות, במרחק רגיל מהמיקרופון שנבחר. אם הפס לא זז כשמדברים, המיקרופון לא קולט כלום. הכתוביות מושהות בזמן ההקלטה.", "Ask the person to speak naturally, at a normal distance from the selected microphone. If the bar doesn’t move while the person speaks, the microphone isn’t hearing anything. Captions are paused during recording."))
        if (recording) {
            val seconds = ((progress ?: 0.0) * RECORDING_SECONDS).toInt()
            // At 30/30 the microphone is done and the voiceprint is being
            // made, which no longer is a recording to stop without saving.
            val saving = (progress ?: 0.0) >= 1.0
            ProgressCard(
                icon = Icons.Rounded.Mic,
                title = if (saving) {
                    tr("שומרים את טביעת הקול…", "Saving the voiceprint…")
                } else {
                    tr("מקליטים… %1/%2 שניות", "Recording… %1/%2 seconds", listOf("$seconds", "$RECORDING_SECONDS"))
                },
                percent = ((progress ?: 0.0) * 100).toInt(),
            ) {
                if (!saving) {
                    LevelMeter(level = { speakers.inputLevel }, active = true)
                    TonalButton(tr("עצירה בלי לשמור", "Stop without saving"), { SpeakerRecordings.stop() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop)
                }
            }
        } else {
            PrimaryButton(
                tr("הקלטה ושמירה (%1 שניות)", "Record and save (%1 seconds)", listOf("$RECORDING_SECONDS")),
                record,
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Mic,
                enabled = name.isNotBlank(),
            )
        }
        GroupFootnote(tr("כדי לשפר את הזיהוי, הקליטו כל אדם 3–4 פעמים תחת אותו שם, בימים שונים ובמקומות שונים: בחדר שקט, כשהטלוויזיה פועלת ברקע ומהצד השני של החדר.", "For better recognition, record each person 3–4 times under the same name, on different days and in different places: a quiet room, with the TV on, from across the room."))
        GroupFootnote(tr("ההקלטה עצמה לא נשמרת — רק ״טביעת קול״ מספרית קצרה שממנה אי אפשר לשחזר את הדיבור.", "The recording itself isn’t saved — only a short numeric “voiceprint” that the speech can’t be reconstructed from."))
    }
    if (noMicrophone) {
        AlertDialog(
            onDismissRequest = { noMicrophone = false },
            icon = { Icon(Icons.Rounded.MicOff, contentDescription = null) },
            title = { Text(tr("אין גישה למיקרופון", "No microphone access")) },
            text = { DialogText(tr("כדי להקליט קול צריך גישה למיקרופון. אפשר לאשר אותה בהגדרות הטלפון.", "Recording a voice needs microphone access. You can allow it in the phone’s Settings.")) },
            confirmButton = {
                TextButton(onClick = {
                    noMicrophone = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(tr("פתיחת הגדרות הטלפון", "Open the phone’s settings")) }
            },
            dismissButton = { TextButton(onClick = { noMicrophone = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (mine?.saved == false) {
        AlertDialog(
            onDismissRequest = { SpeakerRecordings.outcome.value = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text(tr("ההקלטה קצרה או שקטה מדי", "The recording is too short or too quiet")) },
            text = { DialogText(tr("לא הצלחנו להפיק טביעת קול. ודאו שהמיקרופון הנכון נבחר ושהאדם מדבר לאורך כל ההקלטה.", "A voiceprint couldn’t be made. Make sure the right microphone is selected and the person speaks throughout the recording.")) },
            confirmButton = { TextButton(onClick = { SpeakerRecordings.outcome.value = null }) { Text(tr("אישור", "OK")) } },
        )
    }
}
