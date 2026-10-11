package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.tr
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(engineSettings: EngineSettings, settings: SettingsHolder, onClose: () -> Unit) {
    val current by settings.current.collectAsStateWithLifecycle()
    val cloudKeyDraft = rememberSaveable(current.cloudProvider) { mutableStateOf("") }
    val addressDraft = rememberSaveable(current.homeServerAddress) { mutableStateOf(current.homeServerAddress) }
    val codeDraft = rememberSaveable { mutableStateOf("") }
    // As on the iPhone, what was typed but never saved with Done or its
    // button is kept as Settings closes instead of silently dropped.
    val close = {
        engineSettings.saveUnsavedEntries(cloudKeyDraft.value, addressDraft.value, codeDraft.value)
        onClose()
    }
    BackHandler(onBack = close)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("הגדרות", "Settings"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = close) { Text(tr("סגירה", "Close")) }
            }
            EngineSection(engineSettings, current)
            if (current.engine == TranscriptionEngineKind.Cloud) CloudSection(engineSettings, current, cloudKeyDraft)
            if (current.engine == TranscriptionEngineKind.HomeServer) HomeComputerSection(engineSettings, current, addressDraft, codeDraft)
        }
    }
}

@Composable
private fun EngineSection(engineSettings: EngineSettings, current: AppSettings) {
    SectionHeader(tr("מנוע תמלול", "Transcription engine"))
    val chosen = if (current.engine == TranscriptionEngineKind.AppleSpeech) TranscriptionEngineKind.WhisperKit else current.engine
    Column(Modifier.selectableGroup()) {
        for (kind in engineSettings.engines) {
            ChoiceRow(kind.displayName, engineSummary(kind), selected = chosen == kind) { engineSettings.chooseEngine(kind) }
        }
    }
    Footnote(tr("שינוי המנוע מפעיל מחדש את ההאזנה. הכתוביות שכבר על המסך נשארות.", "Changing the engine restarts listening. Captions already on screen stay."))
}

// The iPhone's summary of its own model offers a choice of model size,
// which Android doesn't have yet, so the phone's model goes without one.
private fun engineSummary(kind: TranscriptionEngineKind): String? = when (kind) {
    TranscriptionEngineKind.Cloud -> tr(
        "מודל באינטרנט, לטלפון שאיטי מדי למודל שבתוכו. בעברית הוא טועה יותר מהמודל שבטלפון. צריך אינטרנט ומפתח של שירות ענן.",
        "A model online, for a phone too slow for the one inside it. It gets more Hebrew wrong than the phone’s own model. Needs internet and a key for a cloud service.",
    )
    TranscriptionEngineKind.HomeServer -> tr(
        "מחשב של המשפחה עם כרטיס מסך כותב את הכתוביות: אותו מודל עברית, מהר בהרבה, והטלפון לא מתחמם. כשאין אליו חיבור, הטלפון ממשיך לבד אם יש בו גיבוי.",
        "A family computer with a graphics card writes the captions: the same Hebrew model, much faster, and the phone stays cool. When it can’t be reached, the phone carries on by itself if it has the backup.",
    )
    TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.AppleSpeech -> null
}

@Composable
private fun CloudSection(engineSettings: EngineSettings, current: AppSettings, keyDraft: MutableState<String>) {
    val service = current.cloudProvider
    var hasKey by remember(service) { mutableStateOf(engineSettings.hasCloudKey()) }
    var draft by keyDraft
    var saveFailed by remember(service) { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val save = {
        if (draft.isNotBlank()) {
            val saved = engineSettings.saveCloudKey(draft)
            saveFailed = !saved
            if (saved) {
                draft = ""
                hasKey = true
            }
        }
    }

    SectionHeader(tr("תמלול בענן", "Cloud transcription"))
    Text(tr("שירות", "Service"), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        for (provider in CloudProvider.entries) {
            ChoiceRow(provider.displayName, null, selected = provider == service) { engineSettings.chooseCloudService(provider) }
        }
    }
    if (!service.covers(current.languageCode)) {
        Warning(tr("השירות הזה לא יודע לכתוב כתוביות בשפה שמדברים בה. בחרו שירות אחר.", "This service can’t caption the language being spoken. Choose another service."))
    }
    if (hasKey) Text(tr("מפתח שמור בטלפון", "Key saved on the phone"), color = SAVED_GREEN)
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            saveFailed = false
        },
        label = {
            Text(
                if (hasKey) {
                    tr("מפתח חדש במקום השמור", "New key instead of the saved one")
                } else {
                    tr("הדביקו כאן מפתח %1", "Paste your %1 key here", listOf(service.displayName))
                },
            )
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.isNotBlank()) Button(onClick = save) { Text(tr("שמירת המפתח", "Save key")) }
    if (saveFailed) Warning(tr("המפתח לא נשמר. נסו שוב.", "The key wasn’t saved. Try again."))
    if (hasKey) {
        TextButton(onClick = { confirmingDelete = true }) {
            Text(tr("מחיקת המפתח", "Delete key"), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(tr("למחוק את מפתח הענן?", "Delete the cloud key?")) },
            text = { Text(tr("בלי המפתח הכתוביות לא יגיעו מהענן, עד שיכניסו אותו שוב.", "Without the key, captions can’t come from the cloud until it’s entered again.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        engineSettings.deleteCloudKey()
                        hasKey = false
                        confirmingDelete = false
                    },
                ) { Text(tr("למחוק", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (service == CloudProvider.OpenRouter) {
        Text(tr("מודל", "Model"), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            for (model in CloudSpeech.models) {
                ChoiceRow(EngineSettings.cloudModelName(model), null, selected = model == current.chosenCloudModel) { engineSettings.chooseCloudModel(model) }
            }
        }
    }
    Footnote(cloudServiceFooter(service))
}

// The iPhone's footer for this section speaks of its camera, the backup
// model and the speed slider, none of which Android has yet, so there is
// none here.
@Composable
private fun HomeComputerSection(
    engineSettings: EngineSettings,
    current: AppSettings,
    addressDraft: MutableState<String>,
    codeDraft: MutableState<String>,
) {
    val saved = current.homeServerAddress
    var address by addressDraft
    var hasCode by remember { mutableStateOf(engineSettings.hasPairingCode()) }
    var code by codeDraft
    var codeSaveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<HomeServerCheck?>(null) }
    var checkGeneration by remember { mutableIntStateOf(0) }
    var confirmingCodeDelete by remember { mutableStateOf(false) }
    val forgetCheck = {
        check = null
        checkGeneration += 1
    }
    val saveAddress = {
        forgetCheck()
        engineSettings.saveHomeComputerAddress(address)
    }
    val saveCode = {
        if (code.isNotBlank()) {
            val stored = engineSettings.savePairingCode(code)
            codeSaveFailed = !stored
            if (stored) {
                code = ""
                hasCode = true
                forgetCheck()
            }
        }
    }
    // As on the iPhone, what is on screen is what gets tested: a code or
    // address typed but not saved yet is saved first.
    val testConnection = {
        checking = true
        saveCode()
        forgetCheck()
        val generation = checkGeneration
        val typed = address
        scope.launch {
            val result = engineSettings.testConnection(typed)
            checking = false
            if (generation == checkGeneration) check = result
        }
    }

    SectionHeader(tr("המחשב בבית", "Home computer"))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(tr("כתובת המחשב", "Computer address")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { saveAddress() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (address.trim() != saved) Button(onClick = saveAddress) { Text(tr("שמירת הכתובת", "Save address")) }
    if (HomeServer.needsEncryptedAddress(address)) {
        Caution(tr("מחוץ לרשת הביתית, צריך את הכתובת שמתחילה ב-wss://", "Outside the home network, use the address that starts with wss://"))
    } else if (address.isNotBlank() && HomeServer.url(address) == null) {
        Caution(tr("הכתובת לא נראית תקינה", "That address doesn’t look right"))
    }
    if (hasCode) Text(tr("קוד צימוד שמור בטלפון", "Pairing code saved on the phone"), color = SAVED_GREEN)
    OutlinedTextField(
        value = code,
        onValueChange = {
            code = it
            codeSaveFailed = false
        },
        label = {
            Text(if (hasCode) tr("קוד חדש במקום השמור", "New code instead of the saved one") else tr("קוד הצימוד מהמחשב", "The pairing code from the computer"))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { saveCode() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (code.isNotBlank()) Button(onClick = saveCode) { Text(tr("שמירת הקוד", "Save code")) }
    if (codeSaveFailed) Warning(tr("הקוד לא נשמר. נסו שוב.", "The code wasn’t saved. Try again."))
    if (engineSettings.canTestConnection(address, code, hasCode)) {
        val checkingText = tr("בודק…", "Checking…")
        Button(
            onClick = { testConnection() },
            enabled = !checking,
            modifier = Modifier.semantics { if (checking) stateDescription = checkingText },
        ) {
            Text(tr("בדיקת חיבור", "Test connection"))
            if (checking) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
    check?.let { HomeComputerCheckLabel(it) }
    HomeComputerBeam(engineSettings, current.homeServerBeam, onMoved = forgetCheck)
    if (hasCode) {
        TextButton(onClick = { confirmingCodeDelete = true }) {
            Text(tr("מחיקת הקוד", "Delete code"), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmingCodeDelete) {
        AlertDialog(
            onDismissRequest = { confirmingCodeDelete = false },
            title = { Text(tr("למחוק את קוד המחשב?", "Delete the computer’s code?")) },
            text = { Text(tr("עד שיסרקו שוב את קוד ה‑QR של המחשב, הכתוביות יגיעו מהגיבוי שבטלפון אם הוא הורד, ויפסיקו אם לא.", "Until the computer’s QR code is scanned again, captions come from the backup on the phone if it’s downloaded, and stop if it isn’t.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        engineSettings.deletePairingCode()
                        hasCode = false
                        forgetCheck()
                        confirmingCodeDelete = false
                    },
                ) { Text(tr("למחוק", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingCodeDelete = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

@Composable
private fun HomeComputerBeam(engineSettings: EngineSettings, beam: Int, onMoved: () -> Unit) {
    val range = AppSettings.homeServerBeamRange
    val description = EngineSettings.beamDescription(beam)
    val label = tr("מהירות מול דיוק במחשב", "Speed or accuracy on the computer")
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Text(tr("מהירות מול דיוק", "Speed or accuracy"), modifier = Modifier.weight(1f))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = beam.toFloat(),
        onValueChange = {
            val step = it.roundToInt()
            if (step != beam) {
                engineSettings.chooseBeam(step)
                onMoved()
            }
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = description
        },
    )
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Text(tr("מהיר יותר", "Faster"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(tr("מדויק יותר", "More accurate"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Footnote(tr("ההבדל בזמן ההמתנה קטן מאוד, חלקיק שנייה. עדיף להשאיר על 5, אלא אם אתם יודעים מה אתם עושים.", "The difference in waiting time is very small, a fraction of a second. Leave it at 5 unless you know what you’re doing."))
    if (beam != AppSettings.default.homeServerBeam) {
        TextButton(
            onClick = {
                engineSettings.chooseBeam(AppSettings.default.homeServerBeam)
                onMoved()
            },
        ) { Text(tr("חזרה להגדרה הרגילה", "Back to the usual setting")) }
    }
}

@Composable
private fun HomeComputerCheckLabel(check: HomeServerCheck) {
    val (text, color) = when (check) {
        is HomeServerCheck.Connected -> tr("מחובר: המחשב ענה תוך %1 אלפיות שנייה", "Connected: the computer answered in %1 ms", listOf("${check.milliseconds}")) to SAVED_GREEN
        HomeServerCheck.CodeRefused -> tr("המחשב ענה, אבל לא קיבל את הקוד. סרקו שוב את קוד ה‑QR או הקלידו את הקוד מחדש.", "The computer answered but didn’t accept the code. Scan the QR code again or retype the code.") to MaterialTheme.colorScheme.error
        HomeServerCheck.Unreachable -> tr("אין תשובה מהמחשב. בדקו שהוא דלוק, ער (לא במצב שינה) ומחובר לאינטרנט.", "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet.") to CAUTION_ORANGE
        HomeServerCheck.NotSetUp -> tr("חסרים כתובת או קוד", "The address or code is missing") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun cloudServiceFooter(service: CloudProvider): String = when (service) {
    CloudProvider.Deepgram -> tr(
        "הקול נשלח דרך האינטרנט ל‑Deepgram, שכותבת את הכתוביות, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון; Deepgram מתבקשת לא להשתמש בו לשיפור המודלים שלה. רק כשמישהו מדבר; כל משפט נשלח שוב כל כמה שניות עד שהוא נגמר. שעת דיבור עולה בערך 65 סנט מהקרדיט של המפתח, ודיבור רצוף בלי הפסקות, כמו חדשות או הרצאה, עד פי שלושה; חשבון Deepgram חדש מקבל 200 דולר קרדיט חינם. בלי Wi‑Fi זה משתמש בגלישה סלולרית: כמה מאות MB לשעת דיבור, ועד כ‑1 GB. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to Deepgram, which writes the captions, together with the names and special words and the important words, so it can spell them; Deepgram is asked not to use it to improve its models. Only while someone is speaking; each sentence is sent again every few seconds until it ends. An hour of speech costs about 65 cents from the key’s credit, and unbroken talk like the news or a lecture up to three times that; a new Deepgram account comes with \$200 of free credit. Away from Wi‑Fi it uses mobile data: a few hundred MB an hour of speech, up to about 1 GB. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.Soniox -> tr(
        "הקול נשלח דרך האינטרנט ל‑Soniox, שכותבת את הכתוביות תוך כדי דיבור, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. החיבור נשאר פתוח כל זמן שהכתוביות פועלות, ו‑Soniox גובה על כל הקול שנשלח, גם על רגעי שקט: בערך 12 סנט לשעת כתוביות מהקרדיט של המפתח. בלי Wi‑Fi זה משתמש בכ‑120 MB גלישה סלולרית לשעה. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to Soniox, which writes the captions as the words are said, together with the names and special words and the important words, so it can spell them. The connection stays open while captions run, and Soniox charges for all the audio sent, quiet moments included: about 12 cents an hour of captions from the key’s credit. Away from Wi‑Fi it uses about 120 MB of mobile data an hour. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.OpenAI -> tr(
        "הקול נשלח דרך האינטרנט ל‑OpenAI, שכותבת את הכתוביות, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. רק כשמישהו מדבר; כל משפט נשלח שוב כל כמה שניות עד שהוא נגמר. שעת דיבור עולה בערך 90 סנט מהקרדיט של המפתח, ודיבור רצוף בלי הפסקות, כמו חדשות או הרצאה, עד פי שלושה. בלי Wi‑Fi זה משתמש בגלישה סלולרית: כמה מאות MB לשעת דיבור, ועד כ‑1 GB. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to OpenAI, which writes the captions, together with the names and special words and the important words, so it can spell them. Only while someone is speaking; each sentence is sent again every few seconds until it ends. An hour of speech costs about 90 cents from the key’s credit, and unbroken talk like the news or a lecture up to three times that. Away from Wi‑Fi it uses mobile data: a few hundred MB an hour of speech, up to about 1 GB. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.Groq -> tr(
        "הקול נשלח דרך האינטרנט ל‑Groq, שכותבת את הכתוביות עם מודל ה‑Whisper של OpenAI, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. כל משפט נשלח פעם אחת, כשהוא נגמר, ולכן המילים מופיעות כשהמשפט הסתיים: Groq מחשבת כל בקשה כעשר שניות לפחות. שעת דיבור עולה עד 30 סנט בערך מהקרדיט של המפתח; לתוכנית החינמית של Groq יש מגבלות שימוש, ומעבר להן מודל ה‑Whisper שבטלפון ממשיך לזמן מה. בלי Wi‑Fi זה משתמש בכ‑150 MB גלישה סלולרית לשעת דיבור. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to Groq, which writes the captions with OpenAI’s Whisper model, together with the names and special words and the important words, so it can spell them. Each sentence is sent once, when it ends, so its words appear when it is finished: Groq counts every request as at least ten seconds. An hour of speech costs up to about 30 cents from the key’s credit; Groq’s free plan has usage limits, and past them the Whisper model on the phone carries on for a while. Away from Wi‑Fi it uses about 150 MB of mobile data an hour of speech. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.ElevenLabs -> tr(
        "הקול נשלח דרך האינטרנט ל‑ElevenLabs, שכותבת את הכתוביות, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. רק כשמישהו מדבר; כל משפט נשלח שוב כל כמה שניות עד שהוא נגמר. שעת דיבור עולה בערך 70 סנט מהקרדיט של המפתח, ודיבור רצוף בלי הפסקות, כמו חדשות או הרצאה, עד פי שלושה. בלי Wi‑Fi זה משתמש בגלישה סלולרית: כמה מאות MB לשעת דיבור, ועד כ‑1 GB. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to ElevenLabs, which writes the captions, together with the names and special words and the important words, so it can spell them. Only while someone is speaking; each sentence is sent again every few seconds until it ends. An hour of speech costs about 70 cents from the key’s credit, and unbroken talk like the news or a lecture up to three times that. Away from Wi‑Fi it uses mobile data: a few hundred MB an hour of speech, up to about 1 GB. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.Gemini -> tr(
        "הקול נשלח דרך האינטרנט ל‑Google Gemini, שכותב את הכתוביות, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שיכתוב אותן נכון. בתוכנית החינמית של Google, ‏Google רשאית להשתמש במה שנשלח לשיפור המוצרים שלה, ואנשים ב‑Google עשויים לקרוא אותו; כשמופעל חיוב בתשלום, לא. רק כשמישהו מדבר; כל משפט נשלח שוב כל כמה שניות עד שהוא נגמר. שעת דיבור עולה בערך 75 סנט מהקרדיט של המפתח, ודיבור רצוף בלי הפסקות, כמו חדשות או הרצאה, עד פי שלושה. בלי Wi‑Fi זה משתמש בגלישה סלולרית: כמה מאות MB לשעת דיבור, ועד כ‑1 GB. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to Google Gemini, which writes the captions, together with the names and special words and the important words, so it can spell them. On Google’s free plan, Google may use what is sent to improve its products, and people at Google may read it; with billing turned on, it doesn’t. Only while someone is speaking; each sentence is sent again every few seconds until it ends. An hour of speech costs about 75 cents from the key’s credit, and unbroken talk like the news or a lecture up to three times that. Away from Wi‑Fi it uses mobile data: a few hundred MB an hour of speech, up to about 1 GB. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.Speechmatics -> tr(
        "הקול נשלח דרך האינטרנט ל‑Speechmatics, שכותבת את הכתוביות תוך כדי דיבור, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. החיבור נשאר פתוח כל זמן שהכתוביות פועלות, ו‑Speechmatics גובה על כל הקול שנשלח, גם על רגעי שקט: בערך 80 סנט לשעת כתוביות מהקרדיט של המפתח; חשבון Speechmatics חדש מקבל 100 דולר קרדיט חינם. בלי Wi‑Fi זה משתמש בכ‑120 MB גלישה סלולרית לשעה. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to Speechmatics, which writes the captions as the words are said, together with the names and special words and the important words, so it can spell them. The connection stays open while captions run, and Speechmatics charges for all the audio sent, quiet moments included: about 80 cents an hour of captions from the key’s credit; a new Speechmatics account comes with \$100 of free credit. Away from Wi‑Fi it uses about 120 MB of mobile data an hour. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.AssemblyAI -> tr(
        "הקול נשלח דרך האינטרנט ל‑AssemblyAI, שכותבת את הכתוביות תוך כדי דיבור, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שתכתוב אותן נכון. החיבור נשאר פתוח כל זמן שהכתוביות פועלות, ו‑AssemblyAI גובה על כל הקול שנשלח, גם על רגעי שקט: בערך 57 סנט לשעת כתוביות מהקרדיט של המפתח; חשבון AssemblyAI חדש מקבל 50 דולר קרדיט חינם. בלי Wi‑Fi זה משתמש בכ‑120 MB גלישה סלולרית לשעה. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to AssemblyAI, which writes the captions as the words are said, together with the names and special words and the important words, so it can spell them. The connection stays open while captions run, and AssemblyAI charges for all the audio sent, quiet moments included: about 57 cents an hour of captions from the key’s credit; a new AssemblyAI account comes with \$50 of free credit. Away from Wi‑Fi it uses about 120 MB of mobile data an hour. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
    CloudProvider.OpenRouter -> tr(
        "הקול נשלח דרך האינטרנט ל‑OpenRouter, ומשם לדגם של Google שכותב את הכתוביות, יחד עם השמות והמילים המיוחדות והמילים החשובות, כדי שיכתוב אותן נכון. רק כשמישהו מדבר; כל משפט נשלח שוב כל כמה שניות עד שהוא נגמר. שעת דיבור עולה בערך 30 סנט מהקרדיט של המפתח (המהיר: כ‑15 סנט), ודיבור רצוף בלי הפסקות, כמו חדשות או הרצאה, עד פי שלושה. בלי Wi‑Fi זה משתמש בגלישה סלולרית: כמה מאות MB לשעת דיבור, ועד כ‑1 GB. המפתח נשמר רק בטלפון. בלי אינטרנט, מודל ה‑Whisper שכבר בטלפון ממשיך לבד.",
        "The audio is sent over the internet to OpenRouter, and from there to a Google model that writes the captions, together with the names and special words and the important words, so it can spell them. Only while someone is speaking; each sentence is sent again every few seconds until it ends. An hour of speech costs about 30 cents from the key’s credit (the fast one: about 15 cents), and unbroken talk like the news or a lecture up to three times that. Away from Wi‑Fi it uses mobile data: a few hundred MB an hour of speech, up to about 1 GB. The key is saved only on the phone. Without internet, a Whisper model already on the phone carries on by itself.",
    )
}

@Composable
private fun ChoiceRow(title: String, summary: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warning(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Caution(text: String) {
    Text(text, color = CAUTION_ORANGE)
}

private val SAVED_GREEN = Color(0xFF81C784)
private val CAUTION_ORANGE = Color(0xFFFFB74D)
