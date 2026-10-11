package com.arbelonson.ozen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundNearMisses
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.QuietHours
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    engineSettings: EngineSettings,
    settings: SettingsHolder,
    onClose: () -> Unit,
    onWords: (List<String>) -> Unit,
    onImportantWords: (List<KeywordAlert>) -> Unit,
    canSay: (String) -> Boolean,
    onSpeak: (String) -> Unit,
    diagnostics: DiagnosticsSource,
    heard: List<KeywordHit> = emptyList(),
    heardBy: (KeywordHit) -> String? = { null },
    onAppLanguage: (AppLanguage) -> Unit = {},
    onShowWalkthrough: () -> Unit = {},
    onClearCaptions: () -> Unit = {},
    historySaveFailing: () -> Boolean = { false },
    voiceNotice: String? = null,
    onSoundAlerts: (SoundAlertPreferences) -> Unit = {},
    soundNearMiss: (SoundEvent) -> SoundNearMisses.Entry? = { null },
    onFeel: (AlertVibration) -> Unit = {},
    speakers: SpeakerBook,
    onCellularDownloads: (Boolean) -> Unit,
    backup: PhoneBackup? = null,
    scrollToBackup: Boolean = false,
) {
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
    var showingWords by rememberSaveable { mutableStateOf(false) }
    if (showingWords) {
        WordsScreen(current.vocabulary, current.speakerProfiles.map { it.name }, onWords, onClose = { showingWords = false })
        return
    }
    var showingDiagnostics by rememberSaveable { mutableStateOf(false) }
    if (showingDiagnostics) {
        DiagnosticsScreen(diagnostics, onClose = { showingDiagnostics = false })
        return
    }
    var showingGuide by rememberSaveable { mutableStateOf(false) }
    if (showingGuide) {
        HomeServerGuideScreen(onClose = { showingGuide = false })
        return
    }
    var showingSupport by rememberSaveable { mutableStateOf(false) }
    if (showingSupport) {
        SupportScreen(onClose = { showingSupport = false })
        return
    }
    var showingSounds by rememberSaveable { mutableStateOf(false) }
    if (showingSounds) {
        SoundAlertsScreen(current.soundAlerts, onSoundAlerts, soundNearMiss, onFeel, onClose = { showingSounds = false })
        return
    }
    var showingImportantWords by rememberSaveable { mutableStateOf(false) }
    if (showingImportantWords) {
        ImportantWordsScreen(current.keywordAlerts, current.vocabulary, current.languageCode, current.spokenLanguage, heard, onImportantWords, onClose = { showingImportantWords = false }, heardBy = heardBy)
        return
    }
    var showingEnrollment by rememberSaveable { mutableStateOf(false) }
    if (showingEnrollment) {
        SpeakerEnrollmentScreen(speakers, onClose = { showingEnrollment = false })
        return
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
                Text(tr("הגדרות", "Settings"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = close) { Text(tr("סגירה", "Close")) }
            }
            DisplaySection(settings, current.display, current.engine)
            AlertsSection(current.keywordAlerts.count { it.isEnabled }, listed = current.keywordAlerts.isNotEmpty(), onOpen = { showingImportantWords = true }, sounds = current.soundAlerts, onSounds = { showingSounds = true })
            AwayAlertsRow(current.notifyWhenInBackground) { on -> settings.change { it.notifyWhenInBackground = on } }
            if (current.notifyWhenInBackground) QuietHoursRows(current.quietHours) { hours -> settings.change { it.quietHours = hours } }
            // The iPhone's Alerts footer, less the way to its camera flash.
            Footnote(tr("רטט והדגשה כשנאמרת מילה חשובה; כרזה כשנשמע פעמון דלת, טלפון, אזעקה ועוד. כשהטלפון בכיס או נעול, אותן התראות מגיעות כהודעה בטלפון, וגם הודעה אם הכתוביות נעצרו ולא חזרו לבד.", "Vibration and highlighting when an important word is said; a banner when a doorbell, phone, alarm, and more are heard. When the phone is in a pocket or locked, the same alerts arrive as a phone notification, plus a notification if captions stopped and didn’t come back on their own."))
            SpeechSection(current.speechRate, onRate = { rate -> settings.change { it.speechRate = rate } }, canSay, onSpeak, voiceNotice)
            SpokenLanguageSection(current.spokenLanguage, engineSettings::chooseSpokenLanguage)
            LanguageSection(current.appLanguage, onAppLanguage)
            EngineSection(engineSettings, current)
            if (current.engine == TranscriptionEngineKind.WhisperKit || current.engine == TranscriptionEngineKind.AppleSpeech) {
                SwitchRow(tr("להוריד מודלים גם בחבילת הגלישה", "Download models on cellular data too"), current.allowCellularModelDownload, onChange = onCellularDownloads)
            }
            if (current.engine == TranscriptionEngineKind.Cloud) CloudSection(engineSettings, current, cloudKeyDraft)
            if (current.engine == TranscriptionEngineKind.HomeServer) HomeComputerSection(engineSettings, current, addressDraft, codeDraft, backup, scrollToBackup, onGuide = { showingGuide = true })
            WordsSection(current.vocabulary.size, onOpen = { showingWords = true })
            SavedSpeakersSection(current.speakerProfiles, speakers, onAdd = { showingEnrollment = true })
            BehaviourSection(
                current.hapticOnSpeechResume,
                onBuzz = { on -> settings.change { it.hapticOnSpeechResume = on } },
                current.speakerSimilarityThreshold,
                onSeparation = speakers::setSpeakerSeparation,
            )
            SectionHeader(tr("תחזוקה", "Maintenance"))
            DiagnosticsRow(onOpen = { showingDiagnostics = true })
            WalkthroughAgainRow(onShowWalkthrough)
            ClearCaptionsRow(current.saveHistory, historySaveFailing, onClearCaptions)
            AboutSection(onSupport = { showingSupport = true })
        }
    }
}

/**
 * How the captions look, first as on the iPhone: the size is kept when the
 * slider is let go, not at every step of a drag, and the sample line shows
 * the size, weight and colours as they will be.
 */
@Composable
private fun DisplaySection(settings: SettingsHolder, display: DisplayPreferences, engine: TranscriptionEngineKind) {
    SectionHeader(tr("תצוגה", "Display"))
    var size by remember(display.fontSize) { mutableFloatStateOf(display.fontSize.toFloat()) }
    val sizeLabel = tr("גודל טקסט", "Text size")
    val look = CaptionTheme.of(display.theme, isSystemInDarkTheme())
    Row(modifier = Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Text(sizeLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("${size.roundToInt()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = size,
        onValueChange = { size = it },
        onValueChangeFinished = { settings.change { it.display = it.display.copy(fontSize = size.roundToInt().toDouble()) } },
        valueRange = DisplayPreferences.minimumFontSize.toFloat()..DisplayPreferences.maximumFontSize.toFloat(),
        steps = ((DisplayPreferences.maximumFontSize - DisplayPreferences.minimumFontSize) / 2).toInt() - 1,
        modifier = Modifier.semantics {
            contentDescription = sizeLabel
            stateDescription = "${size.roundToInt()}"
        },
    )
    if (size.roundToInt() != DisplayPreferences.default.fontSize.roundToInt()) {
        TextButton(
            onClick = { settings.change { it.display = it.display.copy(fontSize = DisplayPreferences.default.fontSize) } },
        ) { Text(tr("חזרה לגודל הרגיל", "Back to the usual size")) }
    }
    Text(
        captionText(
            tr("שלום סבתא, נגיע בשש עם שלושה ילדים.", "Hello grandma, we’ll arrive at six with three kids."),
            display.emphasizeNumbers,
            look.numberText,
        ),
        color = look.text,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
        fontWeight = if (display.boldText) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .background(look.background, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
    Text(tr("צבעים", "Colors"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.selectableGroup()) {
        for (theme in DisplayPreferences.Theme.entries) {
            ChoiceRow(CaptionTheme.name(theme), summary = null, selected = display.theme == theme) {
                settings.change { it.display = it.display.copy(theme = theme) }
            }
        }
    }
    SwitchRow(tr("טקסט מודגש", "Bold text"), display.boldText) { on -> settings.change { it.display = it.display.copy(boldText = on) } }
    SwitchRow(tr("להציג שמות דוברים", "Show speaker names"), display.showSpeakerNames) { on ->
        settings.change { it.display = it.display.copy(showSpeakerNames = on) }
    }
    SwitchRow(tr("המסך לא נכבה בזמן האזנה", "Screen doesn’t turn off while listening"), display.keepScreenAwake) { on ->
        settings.change { it.display = it.display.copy(keepScreenAwake = on) }
    }
    SwitchRow(tr("להסתיר את הכפתורים בזמן האזנה", "Hide the buttons while captions are running"), display.autoHideControls) { on ->
        settings.change { it.display = it.display.copy(autoHideControls = on) }
    }
    SwitchRow(
        tr("סימן שאלה ליד שורות שהמנוע לא בטוח בהן, ונקודות מתחת למילים שבספק", "Question mark next to lines the engine isn’t sure about, and dots under the doubtful words"),
        display.markUncertainLines,
    ) { on -> settings.change { it.display = it.display.copy(markUncertainLines = on) } }
    SwitchRow(tr("TalkBack מקריא שורות חדשות", "TalkBack reads new lines aloud"), display.announceNewLines) { on ->
        settings.change { it.display = it.display.copy(announceNewLines = on) }
    }
    if (display.markUncertainLines && engine == TranscriptionEngineKind.Cloud) {
        Footnote(tr("התמלול בענן לא מדווח כמה הוא בטוח, ולכן איתו שום דבר לא מסומן.", "Cloud transcription doesn’t report how sure it is, so with it nothing is marked."))
    }
    SwitchRow(tr("מספרים בולטים (שעות, כמויות, טלפונים)", "Bold numbers (times, amounts, phone numbers)"), display.emphasizeNumbers) { on ->
        settings.change { it.display = it.display.copy(emphasizeNumbers = on) }
    }
    SwitchRow(tr("כתוביות גם במסך הנעילה", "Captions on the lock screen too"), display.lockScreenCaptions) { on ->
        settings.change { it.display = it.display.copy(lockScreenCaptions = on) }
    }
    // The iPhone's footer, less its sentence about VoiceOver.
    Footnote(tr("סימן שאלה ליד שורה אומר שייתכן שהיא לא נשמעה נכון. מספרים כמו שעה, כמות כדורים או מספר טלפון מודגשים בצבע אחר, כדי שלא יתפספסו. לחיצה ארוכה על השורה מאפשרת לבקש שיחזרו עליה. אחרי רבע שעה בלי דיבור המסך ננעל כרגיל, והכתוביות וההתראות ממשיכות. כשהכתוביות רצות לבד, הכפתורים למטה יורדים אחרי כמה שניות כדי לא להסתיר את השורה החדשה; נגיעה במסך מחזירה אותם. השורות האחרונות מופיעות גם במסך הנעילה, בלי לפתוח את הטלפון; מי שמסתכל על הטלפון יכול לקרוא אותן.", "A question mark next to a line means it may not have been heard correctly. Numbers like the time, a pill count, or a phone number are highlighted in another color so they aren’t missed. A long press on a line lets you ask for it to be repeated. After a quarter hour without speech the screen locks as usual, and captions and alerts keep going. While captions are running on their own, the buttons at the bottom drop away after a few seconds so they don’t hide the new line; touching the screen brings them back. The last lines also appear on the lock screen without unlocking the phone; anyone looking at the phone can read them."))
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
    if (hasKey) Text(tr("מפתח שמור בטלפון", "Key saved on the phone"), color = savedGreen())
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

// The iPhone's footer for this section tells her to point the iPhone's
// camera at the computer's code; Android has no words of its own for that
// in every language yet, so there is none here.
@Composable
private fun HomeComputerSection(
    engineSettings: EngineSettings,
    current: AppSettings,
    addressDraft: MutableState<String>,
    codeDraft: MutableState<String>,
    backup: PhoneBackup?,
    scrollToBackup: Boolean,
    onGuide: () -> Unit,
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
    // As on the iPhone, the steps for setting the computer up come first.
    Text(
        tr("איך מכינים את המחשב בבית", "How to set up the home computer"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onGuide).padding(vertical = 12.dp),
    )
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
    if (hasCode) Text(tr("קוד צימוד שמור בטלפון", "Pairing code saved on the phone"), color = savedGreen())
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
    var backupShown = false
    if (backup != null) {
        LaunchedEffect(current.spokenLanguage, current.allowCellularModelDownload) { backup.refreshBackup() }
        val status by backup.backupStatus.collectAsStateWithLifecycle()
        backupShown = status != BackupModelStatus.NotNeeded
        // Opened from the status's offer of a backup: there, as on the iPhone.
        val shown = remember { BringIntoViewRequester() }
        if (scrollToBackup) {
            LaunchedEffect(Unit) {
                withFrameNanos {}
                shown.bringIntoView()
            }
        }
        Box(modifier = Modifier.bringIntoViewRequester(shown)) { PhoneBackupRow(status, onDownload = backup::downloadBackup) }
    }
    HomeComputerBeam(engineSettings, current.homeServerBeam, onMoved = forgetCheck)
    if (hasCode) {
        TextButton(onClick = { confirmingCodeDelete = true }) {
            Text(tr("מחיקת הקוד", "Delete code"), color = MaterialTheme.colorScheme.error)
        }
    }
    // The iPhone's footer, less its QR code sentence, and the backup only
    // where there is one to download.
    Footnote(
        if (backupShown) tr("הקול נשלח למחשב שלכם, שכותב את הכתוביות ומחזיר אותן, רק בזמן שהכתוביות פועלות. באותה רשת Wi‑Fi כותבים את כתובת המחשב (למשל ‎192.168.1.20‎); מכל מקום אחר, כתובת שמתחילה ב‑wss://. הקוד נשמר רק בטלפון. כשאין חיבור למחשב, מודל ה‑Whisper שבטלפון ממשיך לבד, אחרי שהגיבוי למעלה הורד. \"מהירות מול דיוק\": כשמשפט נגמר, המחשב בודק כמה ניסוחים אפשריים ובוחר את הנכון ביותר. פחות ניסוחים מביאים את המשפט מהר יותר, אבל עם יותר טעויות.", "The audio goes to your own computer, which writes the captions and sends them back, only while captions are on. On the same Wi‑Fi, enter the computer’s address (for example 192.168.1.20); from anywhere else, an address starting with wss://. The code is saved only on the phone. When the computer can’t be reached, the Whisper model on the phone carries on by itself, once the backup above has been downloaded. “Speed or accuracy”: when a sentence ends, the computer weighs several possible wordings and picks the best. Fewer wordings bring the sentence sooner, with more mistakes.") else tr("הקול נשלח למחשב שלכם, שכותב את הכתוביות ומחזיר אותן, רק בזמן שהכתוביות פועלות. באותה רשת Wi‑Fi כותבים את כתובת המחשב (למשל ‎192.168.1.20‎); מכל מקום אחר, כתובת שמתחילה ב‑wss://. הקוד נשמר רק בטלפון. \"מהירות מול דיוק\": כשמשפט נגמר, המחשב בודק כמה ניסוחים אפשריים ובוחר את הנכון ביותר. פחות ניסוחים מביאים את המשפט מהר יותר, אבל עם יותר טעויות.", "The audio goes to your own computer, which writes the captions and sends them back, only while captions are on. On the same Wi‑Fi, enter the computer’s address (for example 192.168.1.20); from anywhere else, an address starting with wss://. The code is saved only on the phone. “Speed or accuracy”: when a sentence ends, the computer weighs several possible wordings and picks the best. Fewer wordings bring the sentence sooner, with more mistakes."),
    )
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

/** The phone's own model, kept for when the home computer can't be reached, as on the iPhone. */
interface PhoneBackup {
    val backupStatus: StateFlow<BackupModelStatus>

    /** Looks again: the model on the phone, the room left and the network change while Settings is closed. */
    fun refreshBackup()

    fun downloadBackup()
}

/** The iPhone's backup row: whether the phone can carry on by itself, and how the download is going. */
@Composable
internal fun PhoneBackupRow(status: BackupModelStatus, onDownload: () -> Unit) {
    when (status) {
        BackupModelStatus.NotNeeded -> Unit
        BackupModelStatus.Ready -> Text(tr("גיבוי בטלפון מוכן: כשאין חיבור למחשב, הטלפון ממשיך לבד.", "Backup on the phone is ready: when the computer can’t be reached, the phone carries on by itself."), color = savedGreen())
        is BackupModelStatus.Missing -> {
            BackupDownloadButton(status.megabytes, onDownload)
            BackupNote(tr("בלי הגיבוי, הכתוביות נעצרות כשאין חיבור למחשב.", "Without the backup, captions stop when the computer can’t be reached."))
        }
        is BackupModelStatus.Downloading -> {
            Text(tr("מוריד את הגיבוי · %1%", "Downloading the backup · %1%", listOf("${(status.fraction * 100).roundToInt()}")))
            LinearProgressIndicator(progress = { status.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        BackupModelStatus.WaitingForWiFi -> BackupNote(tr("הגיבוי יורד רק ב‑Wi‑Fi. התחברו ל‑Wi‑Fi כדי להוריד אותו.", "The backup downloads only on Wi‑Fi. Connect to Wi‑Fi to download it."))
        BackupModelStatus.Offline -> BackupNote(tr("אין אינטרנט, אז אי אפשר להוריד את הגיבוי עכשיו.", "There’s no internet, so the backup can’t be downloaded now."))
        is BackupModelStatus.NotEnoughRoom -> Text(tr("אין מספיק מקום בטלפון לגיבוי. צריך לפנות עוד %1.", "Not enough room on the phone for the backup. %1 more needs to be freed up.", listOf(PhasePresentation.sizeText(status.megabytes))), color = MaterialTheme.colorScheme.error)
        BackupModelStatus.Failed -> {
            BackupDownloadButton(null, onDownload)
            Text(tr("ההורדה של הגיבוי נעצרה. נסו שוב.", "The backup download stopped. Try again."), color = cautionOrange())
        }
    }
}

@Composable
private fun BackupDownloadButton(megabytes: Int?, onDownload: () -> Unit) {
    TextButton(onClick = onDownload) {
        Text(if (megabytes != null) tr("הורדת גיבוי לטלפון (%1)", "Download a backup to the phone (%1)", listOf(PhasePresentation.sizeText(megabytes))) else tr("הורדת גיבוי לטלפון", "Download a backup to the phone"))
    }
}

@Composable
private fun BackupNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        is HomeServerCheck.Connected -> tr("מחובר: המחשב ענה תוך %1 אלפיות שנייה", "Connected: the computer answered in %1 ms", listOf("${check.milliseconds}")) to savedGreen()
        HomeServerCheck.CodeRefused -> tr("המחשב ענה, אבל לא קיבל את הקוד. סרקו שוב את קוד ה‑QR או הקלידו את הקוד מחדש.", "The computer answered but didn’t accept the code. Scan the QR code again or retype the code.") to MaterialTheme.colorScheme.error
        HomeServerCheck.Unreachable -> tr("אין תשובה מהמחשב. בדקו שהוא דלוק, ער (לא במצב שינה) ומחובר לאינטרנט.", "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet.") to cautionOrange()
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
internal fun ChoiceRow(title: String, summary: String?, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Right after how captions look, as on the iPhone: the words that buzz when said. */
@Composable
private fun AlertsSection(enabled: Int, listed: Boolean, onOpen: () -> Unit, sounds: SoundAlertPreferences, onSounds: () -> Unit) {
    SectionHeader(tr("התראות", "Alerts"))
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("מילים חשובות", "Important words"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (listed) "$enabled" else tr("אין", "None"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSounds).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("צלילים בבית", "Sounds at home"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (sounds.isEnabled) soundFloorName(sounds.minimumImportance) else tr("כבוי", "Off"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Alerts with the screen off, on by default as on the iPhone. Android asks
 * for notifications when captions first start; switched on here without
 * that, it asks then, since otherwise every alert in a pocket is dropped.
 * While on, it offers a test notification, or, with Ozen's notifications
 * turned off in the phone, says so and leads there; that is checked again
 * on coming back, where she may just have turned them on.
 */
@Composable
private fun AwayAlertsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(!AlertNotifier.allowed(context)) }
    var sent by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        blocked = !AlertNotifier.allowed(context)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { blocked = !AlertNotifier.allowed(context) }
    SwitchRow(tr("התראה בטלפון כשהמסך כבוי", "Alert on phone when the screen is off"), on) { switching ->
        onChange(switching)
        val granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (switching && !granted) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (!on) return
    if (blocked) {
        Warning(tr("ההודעות של אוזן כבויות בהגדרות הטלפון, אז כשהמסך כבוי לא תגיע שום התראה.", "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off."))
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        ) { Text(tr("לפתוח את הגדרות הטלפון", "Open the phone’s settings")) }
    } else {
        TextButton(onClick = { AlertNotifier(context.applicationContext).postTest(); sent = true }) {
            Text(
                if (sent) {
                    tr("נשלחה. נעלו את הטלפון, ותוך 10 שניות היא תגיע", "Sent. Lock the phone, and it will arrive within 10 seconds")
                } else {
                    tr("לבדוק שהתראה מגיעה כשהטלפון נעול", "Test that an alert arrives when the phone is locked")
                },
            )
        }
    }
}

/**
 * Quiet hours, offered only with alerts on while the screen is off, as on
 * the iPhone. An equal start and end is the whole day, deliberately; two
 * equal hours on screen don't say that, so the note under them does, and
 * the screen reader reads it out when it changes.
 */
@Composable
private fun QuietHoursRows(hours: QuietHours, onChange: (QuietHours) -> Unit) {
    SwitchRow(tr("שעות שקטות", "Quiet hours"), hours.isEnabled) { on -> onChange(QuietHours(on, hours.startHour, hours.endHour)) }
    if (!hours.isEnabled) return
    HourStepper(tr("מתחילות", "Starts"), hours.startHour) { onChange(QuietHours(true, it, hours.endHour)) }
    HourStepper(tr("מסתיימות", "Ends"), hours.endHour) { onChange(QuietHours(true, hours.startHour, it)) }
    Text(
        if (hours.startHour == hours.endHour) {
            tr("כשהן מתחילות ומסתיימות באותה שעה, השעות השקטות נמשכות כל היום. צלילים דחופים כמו אזעקה עדיין יתריעו.", "Starting and ending at the same hour, quiet hours last all day. Urgent sounds like a siren still alert.")
        } else {
            tr("צלילים דחופים כמו אזעקה עדיין יתריעו.", "Urgent sounds like a siren still alert.")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The iPhone's stepper: from 00:00 to 23:00 without wrapping round, the
 * hour under its word so a large text size never breaks either, and a
 * screen reader moves it by swiping up or down on the row.
 */
@Composable
private fun HourStepper(title: String, hour: Int, onChange: (Int) -> Unit) {
    val shown = hour.toString().padStart(2, '0') + ":00"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = shown
                progressBarRangeInfo = ProgressBarRangeInfo(hour.toFloat(), 0f..23f, steps = 22)
                setProgress { target -> onChange(target.roundToInt().coerceIn(0, 23)); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        }
        TextButton(onClick = { onChange(hour - 1) }, enabled = hour > 0) { Text("−", fontSize = 24.sp) }
        TextButton(onClick = { onChange(hour + 1) }, enabled = hour < 23) { Text("+", fontSize = 24.sp) }
    }
}

/** The names and words every engine is given as a hint, after the engine as on the iPhone. */
@Composable
private fun WordsSection(count: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("שמות ומילים מיוחדות", "Names and special words"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (count == 0) tr("ריק", "Empty") else "$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Footnote(tr("כל המנועים מקבלים את הרשימה כרמז, כדי ששמות של בני משפחה ייכתבו נכון.", "Every engine gets the list as a hint, so family members’ names are spelled correctly."))
}

/**
 * The voice that says her replies, after the alerts as on the iPhone: the
 * rate is kept when the slider is let go, and the sample is said at it.
 */
@Composable
private fun SpeechSection(rate: Float, onRate: (Float) -> Unit, canSay: (String) -> Boolean, onSpeak: (String) -> Unit, voiceNotice: String?) {
    SectionHeader(tr("להגיד משהו (הקלדה לדיבור)", "Say something (type to speak)"))
    var shown by remember(rate) { mutableFloatStateOf(rate) }
    val label = tr("מהירות דיבור", "Speech rate")
    // Written as on the iPhone, whatever the phone's own number format.
    val value = String.format(Locale.ROOT, "%.2f", shown)
    Row(modifier = Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = shown,
        onValueChange = { shown = it },
        onValueChangeFinished = { onRate((shown * 20).roundToInt() / 20f) },
        valueRange = 0.2f..0.7f,
        steps = 9,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = value
        },
    )
    Row(modifier = Modifier.clearAndSetSemantics {}) {
        Text(tr("לאט", "Slow"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(tr("מהר", "Fast"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val sample = tr("שלום, זה קול הטלפון. ככה אני נשמע.", "Hello, this is the phone's voice. This is how I sound.")
    TextButton(onClick = { onSpeak(sample) }, enabled = canSay(sample)) { Text(tr("להשמיע דוגמה", "Play a sample")) }
    VoiceNotices(listOfNotNull(voiceNotice))
    Footnote(tr("הכתוביות מושהות בזמן שהטלפון מדבר, ומתחדשות לבד.", "Captions pause while the phone is speaking, and resume on their own."))
}

/** Last, as on the iPhone, for whoever helps her: what the captions are doing and the report to send. */
/**
 * After the speaking rate, as on the iPhone: every language by its own
 * name, since a reader picks "العربية" by recognizing it, and a menu
 * rather than thirteen rows to scroll past.
 */
@Composable
private fun LanguageSection(chosen: AppLanguage, onChoose: (AppLanguage) -> Unit) {
    SectionHeader(tr("שפת האפליקציה", "App language"))
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = tr("שפת האפליקציה", "App language")) { open = true }.padding(vertical = 12.dp),
        ) {
            Text(languageName(chosen), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("▾", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (language in AppLanguage.entries) {
                DropdownMenuItem(
                    text = { Text(languageName(language)) },
                    onClick = {
                        open = false
                        if (language != chosen) onChoose(language)
                    },
                )
            }
        }
    }
    Footnote(tr("השפה של הכפתורים וההגדרות, לא של הכתוביות.", "The language of the buttons and settings, not of the captions."))
}

/** The language people around her speak, which every engine is given, each named as it is spoken. */
@Composable
private fun SpokenLanguageSection(chosen: UILanguage, onChoose: (UILanguage) -> Unit) {
    SectionHeader(tr("שפת הדיבור", "Spoken language"))
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = tr("שפת הדיבור", "Spoken language")) { open = true }.padding(vertical = 12.dp),
        ) {
            Text(chosen.spokenName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("▾", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (language in UILanguage.entries) {
                DropdownMenuItem(
                    text = { Text(language.spokenName) },
                    onClick = {
                        open = false
                        onChoose(language)
                    },
                )
            }
        }
    }
    Footnote(tr("השפה שמדברים סביבך. הכתוביות נכתבות בה.", "The language people around you speak. The captions are written in it."))
}

private fun languageName(language: AppLanguage): String =
    if (language == AppLanguage.System) tr("כמו בטלפון", "Same as the phone") else language.resolved(emptyList()).nativeName

/** Last, as on the iPhone: who made Ozen, which version this is, giving to it, and where its code is. */
@Composable
private fun AboutSection(onSupport: () -> Unit) {
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    SectionHeader(tr("אודות", "About"))
    AboutRow(tr("נוצר על ידי", "Made by"), "Arbel")
    AboutRow(tr("גרסה", "Version"), context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "")
    Text(
        tr("תמיכה באוזן (רק אם תרצו)", "Support Ozen (only if you want to)"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSupport).padding(vertical = 12.dp),
    )
    Text(
        tr("קוד המקור בגיטהאב", "Source code on GitHub"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { uris.openUri(SOURCE_CODE) }.padding(vertical = 12.dp),
    )
    Text(
        tr("whisper.cpp (MIT) — מנוע Whisper", "whisper.cpp (MIT) — Whisper engine"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { uris.openUri(WHISPER_ENGINE) }.padding(vertical = 12.dp),
    )
    Footnote(
        tr(
            "אוזן היא תוכנה חופשית בקוד פתוח (AGPL-3.0): כל עותק שלה חייב להישאר חופשי ופתוח. אלא אם בחרתם אחרת למעלה, כל העיבוד נעשה בטלפון ושום דבר לא נשלח החוצה.",
            "Ozen is free, open-source software (AGPL-3.0): every copy of it has to stay free and open. Unless you chose otherwise above, all processing happens on the phone and nothing is sent out.",
        ),
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SOURCE_CODE = "https://github.com/arbelonson-source/ozen"

// The phone's own engine, as the iPhone credits WhisperKit.
private const val WHISPER_ENGINE = "https://github.com/ggml-org/whisper.cpp"

@Composable
private fun DiagnosticsRow(onOpen: () -> Unit) {
    Text(
        tr("אבחון", "Diagnostics"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
    )
}

@Composable
private fun WalkthroughAgainRow(onShow: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Text(
        tr("להציג שוב את ההסבר הראשוני", "Show the initial walkthrough again"),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { asking = true }.padding(vertical = 12.dp),
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("להציג שוב את ההסבר הראשוני?", "Show the initial walkthrough again?")) },
            text = { Text(tr("הכתוביות ייעצרו עד סוף ההסבר.", "Captions stop until the walkthrough ends.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onShow()
                    },
                ) { Text(tr("להציג", "Show it")) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

@Composable
internal fun ClearCaptionsRow(saving: Boolean, saveFailing: () -> Boolean, onClear: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Text(
        tr("ניקוי הכתוביות מהמסך", "Clear captions from the screen"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().clickable { asking = true }.padding(vertical = 12.dp),
    )
    if (asking) {
        val warning = remember { HistoryTexts.clearWarning(saving, saveFailing()) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("למחוק את כל הכתוביות מהמסך?", "Delete all captions from the screen?")) },
            text = warning?.let { { Text(it) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onClear()
                    },
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}

@Composable
internal fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warning(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Caution(text: String) {
    Text(text, color = cautionOrange())
}

// On a white screen these give way to the iPhone's deeper shades of the
// same hues, which stay above 5:1 there; the light ones fall to about 2:1.
@Composable
private fun savedGreen() = if (onLightScreen()) Color(red = 0f, green = 0.45f, blue = 0.15f) else Color(0xFF81C784)

@Composable
internal fun cautionOrange() = if (onLightScreen()) Color(red = 0.7f, green = 0.3f, blue = 0f) else Color(0xFFFFB74D)

@Composable
internal fun onLightScreen() = MaterialTheme.colorScheme.background.luminance() > 0.5f
