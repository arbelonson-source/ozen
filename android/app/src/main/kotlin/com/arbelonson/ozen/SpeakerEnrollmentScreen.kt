package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.arbelonson.ozen.core.tr
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
    val cancel = {
        SpeakerRecordings.stop()
        onClose()
    }
    val record = { SpeakerRecordings.start(screen, speakers, name.trim()) }
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
        if (recording) {
            val seconds = ((progress ?: 0.0) * RECORDING_SECONDS).toInt()
            ProgressCard(
                icon = Icons.Rounded.Mic,
                title = tr("מקליט… %1/%2 שניות", "Recording… %1/%2 seconds", listOf("$seconds", "$RECORDING_SECONDS")),
                percent = ((progress ?: 0.0) * 100).toInt(),
            ) {
                LevelMeter(level = { speakers.inputLevel }, active = true)
                TonalButton(tr("עצירה בלי לשמור", "Stop without saving"), { SpeakerRecordings.stop() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop)
            }
            GroupFootnote(tr("בקשו מהאדם לדבר בטבעיות, במרחק רגיל מהמיקרופון שנבחר. אם הפס לא זז כשמדברים, המיקרופון לא שומע. הכתוביות מושהות בזמן ההקלטה.", "Ask the person to speak naturally, at a normal distance from the selected microphone. If the bar doesn’t move while speaking, the microphone isn’t hearing anything. Captions are paused during recording."))
        } else {
            PrimaryButton(
                tr("הקלטה ושמירה (%1 שניות)", "Record and save (%1 seconds)", listOf("$RECORDING_SECONDS")),
                record,
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Mic,
                enabled = name.isNotBlank(),
            )
        }
        GroupFootnote(tr("לזיהוי טוב יותר, הקליטו כל אדם 3–4 פעמים באותו שם, בימים ובמקומות שונים: חדר שקט, עם טלוויזיה ברקע, מהצד השני של החדר.", "For better recognition, record each person 3–4 times under the same name, on different days and in different places: a quiet room, with the TV on, from across the room."))
        GroupFootnote(tr("ההקלטה עצמה לא נשמרת — רק \"טביעת קול\" מספרית קצרה שממנה אי אפשר לשחזר את הדיבור.", "The recording itself isn’t saved — only a short numeric “voiceprint” that the speech can’t be reconstructed from."))
    }
    if (mine?.saved == false) {
        AlertDialog(
            onDismissRequest = { SpeakerRecordings.outcome.value = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text(tr("ההקלטה קצרה או שקטה מדי", "The recording is too short or too quiet")) },
            text = { Text(tr("לא הצלחנו להפיק טביעת קול. ודאו שהמיקרופון הנכון נבחר ושהאדם מדבר לאורך כל ההקלטה.", "We couldn’t produce a voiceprint. Make sure the right microphone is selected and the person speaks throughout the recording.")) },
            confirmButton = { TextButton(onClick = { SpeakerRecordings.outcome.value = null }) { Text(tr("נסו שוב", "Try again")) } },
        )
    }
}
