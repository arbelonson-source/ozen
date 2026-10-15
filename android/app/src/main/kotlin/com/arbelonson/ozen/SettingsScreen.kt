package com.arbelonson.ozen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import com.arbelonson.ozen.ui.Chevron
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.OzenSlider
import com.arbelonson.ozen.ui.OzenTopBar
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.GroupFootnote
import com.arbelonson.ozen.ui.SettingRow
import com.arbelonson.ozen.ui.SettingSwitchRow
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.TonalButton
import com.arbelonson.ozen.ui.TrailingValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.QuietHours
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundNearMisses
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
    val closing = rememberCoroutineScope()
    LaunchedEffect(Unit) { engineSettings.loadKeyState() }
    val close: () -> Unit = {
        closing.launch {
            engineSettings.saveUnsavedEntries(cloudKeyDraft.value, addressDraft.value, codeDraft.value)
            onClose()
        }
    }
    var showingWords by rememberSaveable { mutableStateOf(false) }
    var showingDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showingGuide by rememberSaveable { mutableStateOf(false) }
    var showingSupport by rememberSaveable { mutableStateOf(false) }
    var showingSounds by rememberSaveable { mutableStateOf(false) }
    var showingImportantWords by rememberSaveable { mutableStateOf(false) }
    var showingEnrollment by rememberSaveable { mutableStateOf(false) }
    val page = when {
        showingWords -> SettingsPage.Words
        showingDiagnostics -> SettingsPage.Diagnostics
        showingGuide -> SettingsPage.Guide
        showingSupport -> SettingsPage.Support
        showingSounds -> SettingsPage.Sounds
        showingImportantWords -> SettingsPage.ImportantWords
        showingEnrollment -> SettingsPage.Enrollment
        else -> SettingsPage.Main
    }
    // Kept out here, so back from a page of its own Settings is where it was
    // left, not at its top again.
    val scroll = rememberScrollState()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() } * if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val way = if (targetState == SettingsPage.Main) -1 else 1
            (fadeIn(tween(210, delayMillis = 90)) + slideInHorizontally(tween(300)) { way * shift }) togetherWith
                (fadeOut(tween(90)) + slideOutHorizontally(tween(300)) { -way * shift })
        },
        label = "settings page",
    ) { shown ->
        when (shown) {
            SettingsPage.Words -> WordsScreen(current.vocabulary, current.speakerProfiles.map { it.name }, onWords, onClose = { showingWords = false })
            SettingsPage.Diagnostics -> DiagnosticsScreen(diagnostics, onClose = { showingDiagnostics = false })
            SettingsPage.Guide -> HomeServerGuideScreen(current.spokenLanguage, onClose = { showingGuide = false })
            SettingsPage.Support -> SupportScreen(onClose = { showingSupport = false })
            SettingsPage.Sounds -> SoundAlertsScreen(current.soundAlerts, onSoundAlerts, soundNearMiss, onFeel, onClose = { showingSounds = false })
            SettingsPage.ImportantWords -> ImportantWordsScreen(current.keywordAlerts, current.vocabulary, current.languageCode, current.spokenLanguage, heard, onImportantWords, onClose = { showingImportantWords = false }, heardBy = heardBy)
            SettingsPage.Enrollment -> SpeakerEnrollmentScreen(speakers, onClose = { showingEnrollment = false })
            SettingsPage.Main -> {
                BackHandler(onBack = close)
                SettingsPageFrame(tr("הגדרות", "Settings"), scroll = scroll, onClose = close, closeLabel = tr("סגירה", "Close")) {
                    DisplaySection(settings, current.display, current.engine)
                    AlertsSection(
                        current.keywordAlerts.count { it.isEnabled },
                        listed = current.keywordAlerts.isNotEmpty(),
                        onOpen = { showingImportantWords = true },
                        sounds = current.soundAlerts,
                        onSounds = { showingSounds = true },
                        notifyInBackground = current.notifyWhenInBackground,
                        onNotifyInBackground = { on -> settings.change { it.notifyWhenInBackground = on } },
                        quietHours = current.quietHours,
                        onQuietHours = { hours -> settings.change { it.quietHours = hours } },
                    )
                    SpeechSection(current.speechRate, onRate = { rate -> settings.change { it.speechRate = rate } }, canSay, onSpeak, voiceNotice)
                    SpokenLanguageSection(current.spokenLanguage, engineSettings::chooseSpokenLanguage)
                    LanguageSection(current.appLanguage, onAppLanguage)
                    EngineSection(engineSettings, current, onCellularDownloads)
                    AnimatedVisibility(current.engine == TranscriptionEngineKind.Cloud) {
                        Column(verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) { CloudSection(engineSettings, current, cloudKeyDraft) }
                    }
                    AnimatedVisibility(current.engine == TranscriptionEngineKind.HomeServer) {
                        Column(verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) { HomeComputerSection(engineSettings, current, addressDraft, codeDraft, backup, scrollToBackup, onGuide = { showingGuide = true }) }
                    }
                    WordsSection(current.vocabulary.size, onOpen = { showingWords = true })
                    SavedSpeakersSection(current.speakerProfiles, speakers, onAdd = { showingEnrollment = true })
                    BehaviourSection(
                        current.hapticOnSpeechResume,
                        onBuzz = { on -> settings.change { it.hapticOnSpeechResume = on } },
                        current.speakerSimilarityThreshold,
                        onSeparation = speakers::setSpeakerSeparation,
                    )
                    MaintenanceSection(
                        onDiagnostics = { showingDiagnostics = true },
                        onWalkthrough = onShowWalkthrough,
                        saving = current.saveHistory,
                        saveFailing = historySaveFailing,
                        onClear = onClearCaptions,
                    )
                    AboutSection(onSupport = { showingSupport = true })
                }
            }
        }
    }
}

/** The pages Settings opens over itself. */
private enum class SettingsPage { Main, Words, Diagnostics, Guide, Support, Sounds, ImportantWords, Enrollment }

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
    val look = CaptionTheme.of(display.theme, isSystemInDarkTheme(), phoneAsksForMoreContrast())
    val lockScreenBlocked = lockScreenNotificationsBlocked()
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                    Text(sizeLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${size.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                    OzenSlider(
                        value = size,
                        onValueChange = { size = it },
                        onValueChangeFinished = { settings.change { it.display = it.display.copy(fontSize = size.roundToInt().toDouble()) } },
                        valueRange = DisplayPreferences.minimumFontSize.toFloat()..DisplayPreferences.maximumFontSize.toFloat(),
                        steps = ((DisplayPreferences.maximumFontSize - DisplayPreferences.minimumFontSize) / 2).toInt() - 1,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = sizeLabel
                            stateDescription = "${size.roundToInt()}"
                        },
                    )
                    Text("A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                }
                if (size.roundToInt() != DisplayPreferences.default.fontSize.roundToInt()) {
                    TextButton(
                        onClick = { settings.change { it.display = it.display.copy(fontSize = DisplayPreferences.default.fontSize) } },
                    ) { Text(tr("חזרה לגודל הרגיל", "Back to the usual size")) }
                }
            }
        }
        item {
            Text(
                captionText(
                    tr("שלום סבתא, נגיע בשש עם שלושה ילדים.", "Hello grandma, we’ll arrive at six with three kids."),
                    display.emphasizeNumbers,
                    look.numberText,
                    decimalComma = LocalDecimalComma.current,
                ),
                color = look.text,
                fontSize = size.sp,
                lineHeight = (size * 1.25f).sp,
                fontWeight = if (display.boldText) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(look.background, MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .padding(16.dp),
            )
        }
    }
    SectionHeader(tr("צבעים", "Colors"))
    SettingsGroup {
        for (theme in DisplayPreferences.Theme.entries) {
            item {
                ChoiceRow(CaptionTheme.name(theme), summary = null, selected = display.theme == theme) {
                    settings.change { it.display = it.display.copy(theme = theme) }
                }
            }
        }
    }
    Spacer(Modifier.height(OzenSpacing.Unit))
    SettingsGroup(dividerInset = 56.dp) {
        item {
            SettingSwitchRow(tr("טקסט מודגש", "Bold text"), display.boldText, { on -> settings.change { it.display = it.display.copy(boldText = on) } }, icon = Icons.Rounded.FormatBold)
        }
        item {
            SettingSwitchRow(tr("להציג שמות דוברים", "Show speaker names"), display.showSpeakerNames, { on -> settings.change { it.display = it.display.copy(showSpeakerNames = on) } }, icon = Icons.Rounded.RecordVoiceOver)
        }
        item {
            SettingSwitchRow(tr("המסך לא נכבה בזמן האזנה", "Screen doesn’t turn off while listening"), display.keepScreenAwake, { on -> settings.change { it.display = it.display.copy(keepScreenAwake = on) } }, icon = Icons.Rounded.LightMode)
        }
        item {
            SettingSwitchRow(tr("להסתיר את הכפתורים בזמן האזנה", "Hide the buttons while captions are running"), display.autoHideControls, { on -> settings.change { it.display = it.display.copy(autoHideControls = on) } }, icon = Icons.Rounded.VisibilityOff)
        }
        item {
            SettingSwitchRow(
                tr("סימן שאלה ליד שורות שהמנוע לא בטוח בהן, ונקודות מתחת למילים שבספק", "Question mark next to lines the engine isn’t sure about, and dots under the doubtful words"),
                display.markUncertainLines,
                { on -> settings.change { it.display = it.display.copy(markUncertainLines = on) } },
                icon = Icons.Rounded.HelpOutline,
            )
        }
        item {
            SettingSwitchRow(tr("TalkBack מקריא שורות חדשות", "TalkBack reads new lines aloud"), display.announceNewLines, { on -> settings.change { it.display = it.display.copy(announceNewLines = on) } }, icon = Icons.AutoMirrored.Rounded.VolumeUp)
        }
        item {
            SettingSwitchRow(tr("מספרים בולטים (שעות, כמויות, טלפונים)", "Bold numbers (times, amounts, phone numbers)"), display.emphasizeNumbers, { on -> settings.change { it.display = it.display.copy(emphasizeNumbers = on) } }, icon = Icons.Rounded.Pin)
        }
        item {
            SettingSwitchRow(tr("כתוביות גם במסך הנעילה", "Captions on the lock screen too"), display.lockScreenCaptions, { on -> settings.change { it.display = it.display.copy(lockScreenCaptions = on) } }, icon = Icons.Rounded.Lock)
        }
        if (display.lockScreenCaptions && lockScreenBlocked) item { LockScreenBlockedNotice() }
    }
    if (display.markUncertainLines && engine == TranscriptionEngineKind.Cloud) {
        GroupFootnote(tr("התמלול בענן לא מדווח כמה הוא בטוח, ולכן איתו שום דבר לא מסומן.", "Cloud transcription doesn’t report how sure it is, so with it nothing is marked."))
    }
    // The iPhone's footer, less its sentence about VoiceOver.
    GroupFootnote(tr("סימן שאלה ליד שורה אומר שייתכן שהיא לא נשמעה נכון. מספרים כמו שעה, כמות כדורים או מספר טלפון מודגשים בצבע אחר, כדי שלא יתפספסו. לחיצה ארוכה על השורה מאפשרת לבקש שיחזרו עליה. אחרי רבע שעה בלי דיבור המסך ננעל כרגיל, והכתוביות וההתראות ממשיכות. כשהכתוביות רצות לבד, הכפתורים למטה יורדים אחרי כמה שניות כדי לא להסתיר את השורה החדשה; נגיעה במסך מחזירה אותם. השורות האחרונות מופיעות גם במסך הנעילה, בלי לפתוח את הטלפון; מי שמסתכל על הטלפון יכול לקרוא אותן.", "A question mark next to a line means it may not have been heard correctly. Numbers like the time, a pill count, or a phone number are highlighted in another color so they aren’t missed. A long press on a line lets you ask for it to be repeated. After a quarter hour without speech the screen locks as usual, and captions and alerts keep going. While captions are running on their own, the buttons at the bottom drop away after a few seconds so they don’t hide the new line; touching the screen brings them back. The last lines also appear on the lock screen without unlocking the phone; anyone looking at the phone can read them."))
}

@Composable
private fun EngineSection(engineSettings: EngineSettings, current: AppSettings, onCellularDownloads: (Boolean) -> Unit) {
    SectionHeader(tr("מנוע תמלול", "Transcription engine"))
    val chosen = if (current.engine == TranscriptionEngineKind.AppleSpeech) TranscriptionEngineKind.WhisperKit else current.engine
    SettingsGroup(modifier = Modifier.selectableGroup()) {
        for (kind in engineSettings.engines) {
            item { ChoiceRow(kind.displayName, engineSummary(kind), selected = chosen == kind) { engineSettings.chooseEngine(kind) } }
        }
    }
    GroupFootnote(tr("שינוי המנוע מפעיל מחדש את ההאזנה. הכתוביות שכבר על המסך נשארות.", "Changing the engine restarts listening. Captions already on screen stay."))
    if (current.engine == TranscriptionEngineKind.WhisperKit || current.engine == TranscriptionEngineKind.AppleSpeech) {
        SettingsGroup(dividerInset = 56.dp) {
            item {
                SettingSwitchRow(
                    tr("להוריד מודלים גם בחבילת הגלישה", "Download models on cellular data too"),
                    current.allowCellularModelDownload,
                    onCellularDownloads,
                    icon = Icons.Rounded.Download,
                )
            }
        }
    }
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
    val keysHeld by engineSettings.cloudKeysHeld.collectAsStateWithLifecycle()
    val hasKey = service in keysHeld
    val scope = rememberCoroutineScope()
    var draft by keyDraft
    var saveFailed by remember(service) { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var askingConsent by remember { mutableStateOf(false) }
    val saveNow = {
        if (draft.isNotBlank()) {
            val typed = draft
            scope.launch {
                val saved = engineSettings.saveCloudKey(typed)
                saveFailed = !saved
                if (saved && draft == typed) draft = ""
            }
        }
    }
    val save = {
        if (draft.isNotBlank()) {
            if (current.cloudProvider in current.cloudSoundAcknowledged) saveNow() else askingConsent = true
        }
    }

    SectionHeader(tr("תמלול בענן", "Cloud transcription"))
    GroupLabel(tr("שירות", "Service"))
    SettingsGroup(modifier = Modifier.selectableGroup()) {
        for (provider in CloudProvider.entries) {
            item { ChoiceRow(provider.displayName, null, selected = provider == service) { engineSettings.chooseCloudService(provider) } }
        }
    }
    if (!service.covers(current.languageCode)) {
        Warning(tr("השירות הזה לא יודע לכתוב כתוביות בשפה שמדברים בה. בחרו שירות אחר.", "This service can’t caption the language being spoken. Choose another service."))
    }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
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
                if (draft.isNotBlank()) PrimaryButton(tr("שמירת המפתח", "Save key"), onClick = save, modifier = Modifier.fillMaxWidth())
                if (saveFailed) Warning(tr("המפתח לא נשמר. נסו שוב.", "The key wasn’t saved. Try again."))
                if (hasKey) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(tr("מחיקת המפתח", "Delete key"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (askingConsent) {
        AlertDialog(
            onDismissRequest = { askingConsent = false },
            title = { Text(tr("לשלוח את הקול ל־%1?", "Send the audio to %1?", listOf(service.displayName))) },
            text = {
                Text(
                    tr(
                        "כשהכתוביות מגיעות מ־%1, הקול מהמיקרופון נשלח אליו דרך האינטרנט, יחד עם השמות והמילים המיוחדות והמילים החשובות. על מה שנשלח חלים התנאים ומדיניות הפרטיות של %1. בלי אישור, המפתח לא יישמר ושום דבר לא יישלח.",
                        "While captions come from %1, the audio from the microphone is sent to it over the internet, together with the names and special words and the important words. %1’s own terms and privacy policy apply to what it receives. Without your OK, the key isn’t saved and nothing is sent.",
                        listOf(service.displayName),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingConsent = false
                        engineSettings.acknowledgeCloud(service)
                        saveNow()
                    },
                ) { Text(tr("לאשר ולשמור", "Allow and save")) }
            },
            dismissButton = { TextButton(onClick = { askingConsent = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(tr("למחוק את מפתח הענן?", "Delete the cloud key?")) },
            text = { Text(tr("בלי המפתח הכתוביות לא יגיעו מהענן, עד שיכניסו אותו שוב.", "Without the key, captions can’t come from the cloud until it’s entered again.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { engineSettings.deleteCloudKey() }
                        confirmingDelete = false
                    },
                ) { Text(tr("למחוק", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (service == CloudProvider.OpenRouter) {
        GroupLabel(tr("מודל", "Model"))
        SettingsGroup(modifier = Modifier.selectableGroup()) {
            for (model in CloudSpeech.models) {
                item { ChoiceRow(EngineSettings.cloudModelName(model), null, selected = model == current.chosenCloudModel) { engineSettings.chooseCloudModel(model) } }
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
    val hasCode by engineSettings.pairingCodeHeld.collectAsStateWithLifecycle()
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
    val saveCode: suspend () -> Unit = {
        if (code.isNotBlank()) {
            val stored = engineSettings.savePairingCode(code)
            codeSaveFailed = !stored
            if (stored) {
                code = ""
                forgetCheck()
            }
        }
    }
    // As on the iPhone, what is on screen is what gets tested: a code or
    // address typed but not saved yet is saved first.
    val saveCodeNow = { scope.launch { saveCode() }; Unit }
    val testConnection = {
        checking = true
        val typed = address
        scope.launch {
            saveCode()
            forgetCheck()
            val generation = checkGeneration
            val result = engineSettings.testConnection(typed)
            checking = false
            if (generation == checkGeneration) check = result
        }
    }

    SectionHeader(tr("המחשב בבית", "Home computer"))
    // As on the iPhone, the steps for setting the computer up come first.
    SettingsGroup(dividerInset = 56.dp) {
        item {
            SettingRow(tr("איך מכינים את המחשב בבית", "How to set up the home computer"), icon = Icons.Rounded.Computer, onClick = onGuide, trailing = { Chevron() })
        }
    }
    var backupStatus: BackupModelStatus? = null
    if (backup != null) {
        LaunchedEffect(current.spokenLanguage, current.allowCellularModelDownload) { backup.refreshBackup() }
        val status by backup.backupStatus.collectAsStateWithLifecycle()
        backupStatus = status
    }
    val backupShown = backupStatus != null && backupStatus != BackupModelStatus.NotNeeded
    // Opened from the status's offer of a backup: there, as on the iPhone.
    val shown = remember { BringIntoViewRequester() }
    if (backupShown && scrollToBackup) {
        LaunchedEffect(Unit) {
            withFrameNanos {}
            shown.bringIntoView()
        }
    }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(tr("כתובת המחשב", "Computer address")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveAddress() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (address.trim() != saved) TonalButton(tr("שמירת הכתובת", "Save address"), onClick = saveAddress, modifier = Modifier.fillMaxWidth())
                if (HomeServer.needsEncryptedAddress(address)) {
                    Caution(tr("מחוץ לרשת הביתית, צריך את הכתובת שמתחילה ב-wss://", "Outside the home network, use the address that starts with wss://"))
                } else if (address.isNotBlank() && HomeServer.url(address) == null) {
                    Caution(tr("הכתובת לא נראית תקינה", "That address doesn’t look right"))
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
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
                    keyboardActions = KeyboardActions(onDone = { saveCodeNow() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (code.isNotBlank()) TonalButton(tr("שמירת הקוד", "Save code"), onClick = saveCodeNow, modifier = Modifier.fillMaxWidth())
                if (codeSaveFailed) Warning(tr("הקוד לא נשמר. נסו שוב.", "The code wasn’t saved. Try again."))
            }
        }
        if (engineSettings.canTestConnection(address, code, hasCode) || check != null) {
            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
                    if (engineSettings.canTestConnection(address, code, hasCode)) {
                        val checkingText = tr("בודק…", "Checking…")
                        PrimaryButton(
                            tr("בדיקת חיבור", "Test connection"),
                            onClick = { testConnection() },
                            icon = Icons.Rounded.Wifi,
                            enabled = !checking,
                            modifier = Modifier.fillMaxWidth().semantics { if (checking) stateDescription = checkingText },
                        )
                        if (checking && !ReducedMotion.isOn()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    check?.let { HomeComputerCheckLabel(it) }
                }
            }
        }
        if (backupShown) {
            item {
                Box(modifier = Modifier.padding(16.dp).bringIntoViewRequester(shown)) { PhoneBackupRow(backupStatus!!, onDownload = backup!!::downloadBackup) }
            }
        }
        item { HomeComputerBeam(engineSettings, current.homeServerBeam, onMoved = forgetCheck) }
        if (hasCode) {
            item {
                TextButton(onClick = { confirmingCodeDelete = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(tr("מחיקת הקוד", "Delete code"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    // The iPhone's footer, less its QR code sentence, and the backup only
    // where there is one to download.
    GroupFootnote(
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
                        scope.launch { engineSettings.deletePairingCode() }
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
    Column(verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) { PhoneBackupContent(status, onDownload) }
}

@Composable
private fun PhoneBackupContent(status: BackupModelStatus, onDownload: () -> Unit) {
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
    TonalButton(
        if (megabytes != null) tr("הורדת גיבוי לטלפון (%1)", "Download a backup to the phone (%1)", listOf(PhasePresentation.sizeText(megabytes))) else tr("הורדת גיבוי לטלפון", "Download a backup to the phone"),
        onClick = onDownload,
        icon = Icons.Rounded.Download,
        modifier = Modifier.fillMaxWidth(),
    )
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LabelAndValue(
            label = { Text(tr("מהירות מול דיוק", "Speed or accuracy"), style = MaterialTheme.typography.bodyLarge) },
            value = { Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        OzenSlider(
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
        Text(
            tr("ההבדל בזמן ההמתנה קטן מאוד, חלקיק שנייה. עדיף להשאיר על 5, אלא אם אתם יודעים מה אתם עושים.", "The difference in waiting time is very small, a fraction of a second. Leave it at 5 unless you know what you’re doing."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (beam != AppSettings.default.homeServerBeam) {
            TextButton(
                onClick = {
                    engineSettings.chooseBeam(AppSettings.default.homeServerBeam)
                    onMoved()
                },
            ) { Text(tr("חזרה להגדרה הרגילה", "Back to the usual setting")) }
        }
    }
}

@Composable
private fun HomeComputerCheckLabel(check: HomeServerCheck) {
    val (text, color) = when (check) {
        is HomeServerCheck.Connected -> tr("מחובר: המחשב ענה תוך %1 אלפיות שנייה", "Connected: the computer answered in %1 ms", listOf("${check.milliseconds}")) to savedGreen()
        HomeServerCheck.CodeRefused -> tr("המחשב ענה, אבל לא קיבל את הקוד. סרקו שוב את קוד ה‑QR או הקלידו את הקוד מחדש.", "The computer answered but didn’t accept the code. Scan the QR code again or retype the code.") to MaterialTheme.colorScheme.error
        HomeServerCheck.Unreachable -> tr("אין תשובה מהמחשב. בדקו שהוא דלוק, ער (לא במצב שינה) ומחובר לאינטרנט.", "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet.") to cautionOrange()
        HomeServerCheck.NotSetUp -> tr("חסרים כתובת או קוד", "The address or code is missing") to MaterialTheme.colorScheme.onSurfaceVariant
        HomeServerCheck.NeedsEncryptedAddress -> tr("מחוץ לרשת הביתית, צריך את הכתובת שמתחילה ב-wss://", "Outside the home network, use the address that starts with wss://") to cautionOrange()
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
internal fun ChoiceRow(title: String, summary: String?, selected: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
    }
}

/**
 * Alerts as on the iPhone: the words that buzz when said, the sounds at home,
 * and alerts with the screen off, on by default. Android asks for
 * notifications when captions first start; switched on here without that, it
 * asks then, since otherwise every alert in a pocket is dropped. While on, it
 * offers a test notification, or, with Ozen's notifications turned off in the
 * phone, says so and leads there; that is checked again on coming back,
 * where she may just have turned them on.
 */
@Composable
private fun AlertsSection(
    enabled: Int,
    listed: Boolean,
    onOpen: () -> Unit,
    sounds: SoundAlertPreferences,
    onSounds: () -> Unit,
    notifyInBackground: Boolean,
    onNotifyInBackground: (Boolean) -> Unit,
    quietHours: QuietHours,
    onQuietHours: (QuietHours) -> Unit,
) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(!AlertNotifier.allowed(context)) }
    var sent by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        blocked = !AlertNotifier.allowed(context)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { blocked = !AlertNotifier.allowed(context) }
    SectionHeader(tr("התראות", "Alerts"))
    SettingsGroup(dividerInset = 56.dp) {
        item {
            SettingRow(
                tr("מילים חשובות", "Important words"),
                icon = Icons.Rounded.NotificationsActive,
                onClick = onOpen,
                trailing = { TrailingValue(if (listed) "$enabled" else tr("אין", "None")) },
            )
        }
        item {
            SettingRow(
                tr("צלילים בבית", "Sounds at home"),
                icon = Icons.Rounded.Hearing,
                onClick = onSounds,
                trailing = { TrailingValue(if (sounds.isEnabled) soundFloorName(sounds.minimumImportance) else tr("כבוי", "Off")) },
            )
        }
        item {
            SettingSwitchRow(
                tr("התראה בטלפון כשהמסך כבוי", "Alert on phone when the screen is off"),
                notifyInBackground,
                { switching ->
                    onNotifyInBackground(switching)
                    val granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (switching && !granted) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                icon = Icons.Rounded.PhoneAndroid,
            )
        }
        if (notifyInBackground) {
            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (blocked) {
                        Text(
                            tr("ההודעות של אוזן כבויות בהגדרות הטלפון, אז כשהמסך כבוי לא תגיע שום התראה.", "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off."),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        TextButton(onClick = { openNotificationSettings(context) }) { Text(tr("לפתוח את הגדרות הטלפון", "Open the phone’s settings")) }
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
            }
            item {
                SettingSwitchRow(
                    tr("שעות שקטות", "Quiet hours"),
                    quietHours.isEnabled,
                    { on -> onQuietHours(QuietHours(on, quietHours.startHour, quietHours.endHour)) },
                    icon = Icons.Rounded.Bedtime,
                )
            }
            if (quietHours.isEnabled) {
                item { HourStepper(tr("מתחילות", "Starts"), quietHours.startHour) { onQuietHours(QuietHours(true, it, quietHours.endHour)) } }
                item { HourStepper(tr("מסתיימות", "Ends"), quietHours.endHour) { onQuietHours(QuietHours(true, quietHours.startHour, it)) } }
                // An equal start and end is the whole day, deliberately; two
                // equal hours on screen don't say that, so the note does, and
                // the screen reader reads it out when it changes.
                item {
                    Text(
                        if (quietHours.startHour == quietHours.endHour) {
                            tr("כשהן מתחילות ומסתיימות באותה שעה, השעות השקטות נמשכות כל היום. צלילים דחופים כמו אזעקה עדיין יתריעו.", "Starting and ending at the same hour, quiet hours last all day. Urgent sounds like a siren still alert.")
                        } else {
                            tr("צלילים דחופים כמו אזעקה עדיין יתריעו.", "Urgent sounds like a siren still alert.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    // The iPhone's Alerts footer, less the way to its camera flash.
    GroupFootnote(tr("רטט והדגשה כשנאמרת מילה חשובה; כרזה כשנשמע פעמון דלת, טלפון, אזעקה ועוד. כשהטלפון בכיס או נעול, אותן התראות מגיעות כהודעה בטלפון, וגם הודעה אם הכתוביות נעצרו ולא חזרו לבד.", "Vibration and highlighting when an important word is said; a banner when a doorbell, phone, alarm, and more are heard. When the phone is in a pocket or locked, the same alerts arrive as a phone notification, plus a notification if captions stopped and didn’t come back on their own."))
}

private fun openNotificationSettings(context: android.content.Context) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}

/**
 * The lock screen's captions come in the listening notification, so with
 * Ozen's notifications turned off in the phone (or never allowed) there
 * are none. Said, and led there, as the iPhone does when its Live
 * Activities are off; checked again on coming back.
 */
@Composable
private fun lockScreenNotificationsBlocked(): Boolean {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(!ListeningService.shown(context)) }
    LifecycleResumeEffect(Unit) {
        blocked = !ListeningService.shown(context)
        onPauseOrDispose { }
    }
    return blocked
}

@Composable
private fun LockScreenBlockedNotice() {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            tr("ההודעות של אוזן כבויות בהגדרות הטלפון, אז הכתוביות לא יופיעו במסך הנעילה.", "Ozen’s notifications are turned off in the phone’s settings, so captions won’t appear on the lock screen."),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        TextButton(onClick = { openNotificationSettings(context) }) { Text(tr("לפתוח את הגדרות הטלפון", "Open the phone’s settings")) }
    }
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
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 8.dp)
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
    SettingsGroup(modifier = Modifier.padding(top = OzenSpacing.Unit), dividerInset = 56.dp) {
        item {
            SettingRow(
                tr("שמות ומילים מיוחדות", "Names and special words"),
                icon = Icons.Rounded.Spellcheck,
                onClick = onOpen,
                trailing = { TrailingValue(if (count == 0) tr("ריק", "Empty") else "$count") },
            )
        }
    }
    GroupFootnote(tr("כל המנועים מקבלים את הרשימה כרמז, כדי ששמות של בני משפחה ייכתבו נכון.", "Every engine gets the list as a hint, so family members’ names are spelled correctly."))
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
    val sample = tr("שלום, זה קול הטלפון. ככה אני נשמע.", "Hello, this is the phone's voice. This is how I sound.")
    SettingsGroup(dividerInset = 56.dp) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            }
        }
        item { SettingRow(tr("להשמיע דוגמה", "Play a sample"), icon = Icons.Rounded.PlayArrow, onClick = { onSpeak(sample) }, enabled = canSay(sample)) }
    }
    VoiceNotices(listOfNotNull(voiceNotice))
    GroupFootnote(tr("הכתוביות מושהות בזמן שהטלפון מדבר, ומתחדשות לבד.", "Captions pause while the phone is speaking, and resume on their own."))
}

/**
 * After the speaking rate, as on the iPhone: every language by its own
 * name, since a reader picks "العربية" by recognizing it, and a menu
 * rather than thirteen rows to scroll past.
 */
@Composable
private fun LanguageSection(chosen: AppLanguage, onChoose: (AppLanguage) -> Unit) {
    SectionHeader(tr("שפת האפליקציה", "App language"))
    var open by remember { mutableStateOf(false) }
    SettingsGroup(dividerInset = 56.dp) {
        item {
            Box {
                SettingRow(languageName(chosen), icon = Icons.Rounded.Translate, onClick = { open = true }, trailing = { MenuArrow() })
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
        }
    }
    GroupFootnote(tr("השפה של הכפתורים וההגדרות, לא של הכתוביות.", "The language of the buttons and settings, not of the captions."))
}

/** The language people around her speak, which every engine is given, each named as it is spoken. */
@Composable
private fun SpokenLanguageSection(chosen: UILanguage, onChoose: (UILanguage) -> Unit) {
    SectionHeader(tr("שפת הדיבור", "Spoken language"))
    var open by remember { mutableStateOf(false) }
    SettingsGroup(dividerInset = 56.dp) {
        item {
            Box {
                SettingRow(chosen.spokenName, icon = Icons.Rounded.RecordVoiceOver, onClick = { open = true }, trailing = { MenuArrow() })
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
        }
    }
    GroupFootnote(tr("השפה שמדברים סביבך. הכתוביות נכתבות בה.", "The language people around you speak. The captions are written in it."))
}

@Composable
private fun MenuArrow() {
    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun languageName(language: AppLanguage): String =
    if (language == AppLanguage.System) tr("כמו בטלפון", "Same as the phone") else language.resolved(emptyList()).nativeName

/** Last, as on the iPhone: who made Ozen, which version this is, giving to it, and where its code is. */
@Composable
private fun AboutSection(onSupport: () -> Unit) {
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    SectionHeader(tr("אודות", "About"))
    SettingsGroup(dividerInset = 56.dp) {
        item { AboutRow(tr("נוצר על ידי", "Made by"), "Arbel", Icons.Rounded.Person) }
        item { AboutRow(tr("גרסה", "Version"), context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "", Icons.Rounded.Info) }
        item { SettingRow(tr("תמיכה באוזן (רק אם תרצו)", "Support Ozen (only if you want to)"), icon = Icons.Rounded.Favorite, onClick = onSupport, trailing = { Chevron() }) }
        item {
            SettingRow(
                tr("קוד המקור בגיטהאב", "Source code on GitHub"),
                icon = Icons.Rounded.Code,
                onClick = { uris.openUri(SOURCE_CODE) },
                trailing = { OpensOutside() },
            )
        }
        item {
            SettingRow(
                tr("whisper.cpp (MIT) — מנוע Whisper", "whisper.cpp (MIT) — Whisper engine"),
                icon = Icons.Rounded.Memory,
                onClick = { uris.openUri(WHISPER_ENGINE) },
                trailing = { OpensOutside() },
            )
        }
    }
    GroupFootnote(
        tr(
            "אוזן היא תוכנה חופשית בקוד פתוח (AGPL-3.0): כל עותק שלה חייב להישאר חופשי ופתוח. אלא אם בחרתם אחרת למעלה, כל העיבוד נעשה בטלפון ושום דבר לא נשלח החוצה.",
            "Ozen is free, open-source software (AGPL-3.0): every copy of it has to stay free and open. Unless you chose otherwise above, all processing happens on the phone and nothing is sent out.",
        ),
    )
}

@Composable
private fun OpensOutside() {
    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AboutRow(label: String, value: String, icon: ImageVector) {
    SettingRow(
        label,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        icon = icon,
        trailing = { Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

private const val SOURCE_CODE = "https://github.com/arbelonson-source/ozen"

// The phone's own engine, as the iPhone credits WhisperKit.
private const val WHISPER_ENGINE = "https://github.com/ggml-org/whisper.cpp"

@Composable
private fun MaintenanceSection(onDiagnostics: () -> Unit, onWalkthrough: () -> Unit, saving: Boolean, saveFailing: () -> Boolean, onClear: () -> Unit) {
    SectionHeader(tr("תחזוקה", "Maintenance"))
    SettingsGroup(dividerInset = 56.dp) {
        item { SettingRow(tr("אבחון", "Diagnostics"), icon = Icons.Rounded.BugReport, onClick = onDiagnostics, trailing = { Chevron() }) }
        item { WalkthroughAgainRow(onWalkthrough) }
        item { ClearCaptionsRow(saving, saveFailing, onClear) }
    }
}

@Composable
private fun WalkthroughAgainRow(onShow: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    SettingRow(tr("להציג שוב את ההסבר הראשוני", "Show the initial walkthrough again"), icon = Icons.Rounded.Replay, onClick = { asking = true })
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
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { asking = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = error)
        Text(tr("ניקוי הכתוביות מהמסך", "Clear captions from the screen"), style = MaterialTheme.typography.bodyLarge, color = error, modifier = Modifier.weight(1f))
    }
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
    com.arbelonson.ozen.ui.SectionHeader(text)
}

@Composable
internal fun Footnote(text: String) {
    GroupFootnote(text)
}

/** A small grey label over a group that has no header of its own, for example "Service". */
@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = OzenSpacing.Unit))
}

/**
 * A scrolling settings page: the top bar scrolls with the content, so a
 * page of any length ends in the same place, and its controls are found by scrolling to them. The body has 16 dp margins and 8 dp between its
 * children.
 */
@Composable
internal fun SettingsPageFrame(
    title: String,
    scroll: ScrollState = rememberScrollState(),
    onClose: (() -> Unit)? = null,
    closeLabel: String = tr("סיום", "Done"),
    actions: @Composable RowScope.() -> Unit = com.arbelonson.ozen.ui.NoActions,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)).imePadding().verticalScroll(scroll)) {
            OzenTopBar(title, onClose = onClose, closeLabel = closeLabel, actions = actions, insets = false)
            Column(
                modifier = Modifier
                    .padding(horizontal = OzenSpacing.Page)
                    .padding(bottom = OzenSpacing.Section)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit),
                content = content,
            )
        }
    }
}

/**
 * A screen's title with its buttons beside it, or, where even the title's
 * longest word wouldn't fit beside them, the title on a line of its own
 * and the buttons under it at the end. Squeezed by its buttons at the
 * phone's largest text, History broke before its last Hebrew letter and
 * German's into three pieces.
 */
@Composable
internal fun ScreenTitle(title: String, actions: @Composable RowScope.() -> Unit) {
    BesideOrUnder(
        start = { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) },
        end = { Row(verticalAlignment = Alignment.CenterVertically, content = actions) },
        underAtEnd = true,
    )
}

/**
 * A label with its value at the end of the row, or, where even the label's
 * longest word wouldn't fit beside the value, the value under it: in German
 * at the phone's largest text, "Haushaltsgeräusche und mehr" left "Geräusche
 * zu Hause" one letter to a line.
 */
@Composable
internal fun LabelAndValue(label: @Composable () -> Unit, value: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Dp = 16.dp) {
    BesideOrUnder(start = label, end = value, underAtEnd = false, modifier = modifier, gap = gap)
}

@Composable
private fun BesideOrUnder(start: @Composable () -> Unit, end: @Composable () -> Unit, underAtEnd: Boolean, modifier: Modifier = Modifier, gap: Dp = 0.dp) {
    Layout(contents = listOf(start, end), modifier = modifier) { (starts, ends), constraints ->
        val first = starts.single()
        val second = ends.single()
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        if (first.minIntrinsicWidth(Constraints.Infinity) + space + second.maxIntrinsicWidth(Constraints.Infinity) <= width) {
            val after = second.measure(Constraints(maxWidth = width))
            val before = first.measure(Constraints(maxWidth = width - after.width - space))
            val height = maxOf(before.height, after.height, constraints.minHeight)
            layout(width, height) {
                before.placeRelative(0, (height - before.height) / 2)
                after.placeRelative(width - after.width, (height - after.height) / 2)
            }
        } else {
            val above = first.measure(Constraints(maxWidth = width))
            val below = second.measure(Constraints(maxWidth = width))
            layout(width, maxOf(above.height + below.height, constraints.minHeight)) {
                above.placeRelative(0, 0)
                below.placeRelative(if (underAtEnd) width - below.width else 0, above.height)
            }
        }
    }
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
