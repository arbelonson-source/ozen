package com.arbelonson.ozen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.HebrewTime
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.MicrophoneDropNotice
import com.arbelonson.ozen.core.RecentConversation
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.UncertainWords
import com.arbelonson.ozen.core.tr
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val showingSettings = mutableStateOf(false)
    private val settingsAtBackup = mutableStateOf(false)
    private val confirmingCellular = mutableStateOf(false)
    private val microphoneAnswer = mutableStateOf<Boolean?>(null)
    private val notificationsAnswer = mutableStateOf<Boolean?>(null)
    private val approveMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { microphoneAnswer.value = it }
    private val approveNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAnswer.value = it }

    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            ListeningService.start(this)
        } else {
            (application as OzenApplication).captions.listen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turning the phone or a new theme rebuilds the activity; the
        // screens below Settings are kept by rememberSaveable already.
        showingSettings.value = savedInstanceState?.getBoolean(SHOWING_SETTINGS) == true
        val app = application as OzenApplication
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = app.settings.current.value.appLanguage.resolved(phoneLanguages)
        enableEdgeToEdge()
        setContent { OzenApp(app, showingSettings, settingsAtBackup, confirmingCellular, onboarding(), onStatusTap = ::statusTapped, onLaunch = ::startListening, onAppLanguage = ::chooseAppLanguage, onLightScreen = ::systemBarsFor) }
        // Once per link: a rebuilt screen (a turn, a new language, the app
        // back after Android ended it) and an opening from recent apps carry
        // the link the app was first opened with, and asking about it again
        // offered to replace the address and code set since.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) openLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SHOWING_SETTINGS, showingSettings.value)
    }

    override fun onStart() {
        super.onStart()
        // Back from the phone's settings, where the walkthrough sent her.
        if (microphoneAnswer.value == false && hasMicrophone()) microphoneAnswer.value = true
        if (notificationsAnswer.value != true && AlertNotifier.allowed(this)) notificationsAnswer.value = true
        val captions = (application as OzenApplication).captions
        if (captions.screenStarted() && captions.retriesOnReturn && hasMicrophone()) ListeningService.start(this)
        captions.refreshVoice()
        CaptionState.screenReturned()
    }

    override fun onStop() {
        if ((application as OzenApplication).captions.screenStopped(rebuilding = isChangingConfigurations)) CaptionState.screenLeft()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun systemBarsFor(lightScreen: Boolean) {
        if (lightScreen) {
            enableEdgeToEdge(SystemBarStyle.light(TRANSPARENT, TRANSPARENT), SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM))
        } else {
            enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(DARK_SCRIM))
        }
    }

    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let { (application as OzenApplication).pairing.open(it) }
    }

    private fun statusTapped() {
        when (CaptionState.screen.value.status.action) {
            PhasePresentation.Action.Pause -> ListeningService.pause(this)
            PhasePresentation.Action.OpenSystemSettings -> if (hasMicrophone()) startListening() else openAppSettings()
            PhasePresentation.Action.Start, PhasePresentation.Action.Resume, PhasePresentation.Action.Retry -> startListening()
            PhasePresentation.Action.OpenEngineSettings -> showingSettings.value = true
            PhasePresentation.Action.OpenBackupSettings -> {
                settingsAtBackup.value = true
                showingSettings.value = true
            }
            PhasePresentation.Action.ConfirmCellularDownload -> confirmingCellular.value = true
            PhasePresentation.Action.StopSpeaking -> (application as OzenApplication).captions.stopSpeaking()
            else -> Unit
        }
    }

    private fun onboarding() = OnboardingHooks(
        microphone = microphoneAnswer,
        notifications = notificationsAnswer,
        shown = {
            microphoneAnswer.value = if (hasMicrophone()) true else null
            notificationsAnswer.value = if (AlertNotifier.allowed(this)) true else null
        },
        approveMicrophone = { approveMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
        approveNotifications = ::askForNotifications,
        openPhoneSettings = ::openAppSettings,
        finish = { (application as OzenApplication).settings.change { it.hasCompletedOnboarding = true } },
    )

    /** Rebuilt so every screen is drawn again in it; Settings stays open through it (see onSaveInstanceState). */
    private fun chooseAppLanguage(language: AppLanguage) {
        (application as OzenApplication).settings.change { it.appLanguage = language }
        recreate()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            approveNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun startListening() {
        if (hasMicrophone()) {
            ListeningService.start(this)
            askForNotificationsIfNeverAnswered()
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        askForMicrophone.launch(wanted.toTypedArray())
    }

    /**
     * As on the iPhone: asked the first time captions run if nobody answered
     * in the walkthrough, or alerts while she is away from the app never
     * arrive and nothing on the caption screen says so. Android keeps no
     * "never asked" (NotificationAsking.answered): a refusal it would ask
     * about again shows as a rationale, and for one it won't ask about
     * again it shows no dialog.
     */
    private fun askForNotificationsIfNeverAnswered() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alertsAway = (application as OzenApplication).settings.current.value.notifyWhenInBackground
        if (BackgroundAlertPolicy.shouldAskPermission(alertsWhenScreenOff = alertsAway, allowed = NotificationAsking.answered(this))) {
            NotificationAsking.ask(approveNotifications)
        }
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private companion object {
        const val SHOWING_SETTINGS = "showingSettings"
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        // The scrims enableEdgeToEdge draws behind three-button navigation by default.
        val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** What the walkthrough needs from the activity: the two permissions it asks for, their answers, and finishing. */
private class OnboardingHooks(
    val microphone: State<Boolean?>,
    val notifications: State<Boolean?>,
    val shown: () -> Unit,
    val approveMicrophone: () -> Unit,
    val approveNotifications: () -> Unit,
    val openPhoneSettings: () -> Unit,
    val finish: () -> Unit,
)

@Composable
private fun OzenApp(
    app: OzenApplication,
    showingSettings: MutableState<Boolean>,
    settingsAtBackup: MutableState<Boolean>,
    confirmingCellular: MutableState<Boolean>,
    onboarding: OnboardingHooks,
    onStatusTap: () -> Unit,
    onLaunch: () -> Unit,
    onAppLanguage: (AppLanguage) -> Unit,
    onLightScreen: (Boolean) -> Unit,
) {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val state by CaptionState.screen.collectAsStateWithLifecycle()
    val current by app.settings.current.collectAsStateWithLifecycle()
    val voices by app.captions.voice.installed.collectAsStateWithLifecycle()
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<Pair<UUID, UUID?>?>(null) }
    var saying by rememberSaveable { mutableStateOf(false) }
    var choosingMicrophone by rememberSaveable { mutableStateOf(false) }
    var namingAlert by rememberSaveable { mutableStateOf(false) }
    var sayDraft by rememberSaveable { mutableStateOf("") }
    var awakeClock by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1_000.0) }
    LaunchedEffect(state.phase.isListening) {
        while (true) {
            awakeClock = System.currentTimeMillis() / 1_000.0
            delay(30_000)
        }
    }
    // The walkthrough is long enough for the phone's own lock timeout to
    // end it partway, as the iPhone found. The home computer's backup
    // downloads outside the captions, often while they are stopped, with
    // nothing else keeping the app going once the phone locks; the iPhone
    // keeps the screen on for it too. A new speaker's half minute of voice
    // as well: the captions are stopped for it, and once the screen locks
    // Android hands an app out of sight only silence from the microphone
    // (the iPhone records on behind its lock screen).
    val backup by app.captions.backupStatus.collectAsStateWithLifecycle()
    val recordingVoice by SpeakerRecordings.progress.collectAsStateWithLifecycle()
    KeepScreenOn(
        !current.hasCompletedOnboarding ||
            backup is BackupModelStatus.Downloading ||
            recordingVoice != null ||
            state.keepsScreenOn(current.display.keepScreenAwake, awakeClock),
    )
    val look = CaptionTheme.of(current.display.theme, isSystemInDarkTheme(), phoneAsksForMoreContrast())
    LaunchedEffect(look.isLight) { onLightScreen(look.isLight) }
    MaterialTheme(
        colorScheme = if (look.isLight) lightColorScheme() else darkColorScheme(),
        typography = OzenTypography.of(MaterialTheme.typography),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDecimalComma provides usesDecimalComma(current.spokenLanguage)) {
            val front = when {
                !current.hasCompletedOnboarding -> FrontScreen(Front.Walkthrough)
                showingSettings.value -> FrontScreen(Front.Settings)
                reading != null -> FrontScreen(Front.Conversation, reading)
                namingAlert -> FrontScreen(Front.NameAlert)
                choosingMicrophone -> FrontScreen(Front.Microphones)
                saying -> FrontScreen(Front.Say)
                showingHistory -> FrontScreen(Front.History)
                else -> FrontScreen(Front.Captions)
            }
            // A screen opened from another slides in from where the reading
            // goes on (from the left in Hebrew and Arabic) and back out the
            // same way, Material's shared axis: the one leaving fades before
            // the one arriving shows, so their words never cross.
            val shift = with(LocalDensity.current) { 30.dp.roundToPx() } * if (direction == LayoutDirection.Rtl) -1 else 1
            AnimatedContent(
                targetState = front,
                transitionSpec = {
                    val way = targetState.kind.depth.compareTo(initialState.kind.depth)
                    (fadeIn(tween(210, delayMillis = 90)) + slideInHorizontally(tween(300)) { way * shift }) togetherWith
                        (fadeOut(tween(90)) + slideOutHorizontally(tween(300)) { -way * shift })
                },
                label = "screen",
            ) { screen ->
                val opened = screen.reading
                OnTabletWidth(screen.kind != Front.Captions && screen.kind != Front.Say) {
                    when (screen.kind) {
                        Front.Walkthrough -> {
                            LaunchedEffect(Unit) { onboarding.shown() }
                            OnboardingScreen(
                                settings = current,
                                microphone = onboarding.microphone.value,
                                notifications = onboarding.notifications.value,
                                onApproveMicrophone = onboarding.approveMicrophone,
                                onApproveNotifications = onboarding.approveNotifications,
                                onOpenPhoneSettings = onboarding.openPhoneSettings,
                                onSpokenLanguage = app.engineSettings::chooseSpokenLanguage,
                                onAddName = app.captions::addNameAlert,
                                onAddAlert = app.captions::addAlert,
                                onFinish = onboarding.finish,
                            )
                        }
                        Front.Settings -> SettingsScreen(app.engineSettings, app.settings, onClose = { showingSettings.value = false; settingsAtBackup.value = false }, onWords = { app.captions.setWords(it) }, onImportantWords = { app.captions.setImportantWords(it) }, canSay = app.captions::canSay, onSpeak = { app.captions.speak(it) }, diagnostics = app.captions, heard = state.recentlyHeard, heardBy = { state.recentlyHeardBy[it.id]?.takeIf { current.display.showSpeakerNames } }, onAppLanguage = onAppLanguage, onShowWalkthrough = {
                            app.captions.showWalkthroughAgain()
                            showingSettings.value = false
                        }, onClearCaptions = app.captions::clearCaptions, historySaveFailing = { app.captions.savedConversations.lastSaveFailure != null }, voiceNotice = VoiceNotice.forSettings(Localization.language, voices), onSoundAlerts = app.captions::setSoundAlerts, soundNearMiss = app.captions::soundNearMiss, onFeel = app.captions::buzz, speakers = app.captions, onCellularDownloads = app.captions::allowCellularModelDownload, backup = app.captions, scrollToBackup = settingsAtBackup.value)
                        Front.Conversation -> ConversationScreen(
                            app.captions.savedConversations,
                            opened!!.first,
                            query = "",
                            marksUncertain = current.display.markUncertainLines,
                            emphasizeNumbers = current.display.emphasizeNumbers,
                            // Renamed or deleted inside: the empty screen's offer follows.
                            onClose = {
                                reading = null
                                app.captions.loadRecentConversation()
                            },
                            line = opened.second,
                            languageCode = current.languageCode,
                        )
                        Front.NameAlert -> NameAlertScreen(current, onAddName = app.captions::addNameAlert, onAddAlert = app.captions::addAlert, onClose = { namingAlert = false })
                        Front.Microphones -> MicPickerScreen(
                            inputs = state.inputs,
                            selected = state.selectedInput,
                            listening = state.phase.isListening,
                            level = { app.captions.pipeline.inputLevel },
                            onChoose = { app.captions.selectInput(it.uid) },
                            onRefresh = app.captions::refreshInputs,
                            onClose = { choosingMicrophone = false },
                        )
                        Front.Say -> SayScreen(
                            draft = sayDraft,
                            onDraft = { sayDraft = it },
                            speaking = state.speaking,
                            phrases = QuickPhrases.shown(current.quickPhrases, Localization.language),
                            canSay = app.captions::canSay,
                            onSpeak = app.captions::speak,
                            onStop = app.captions::stopSpeaking,
                            onAddPhrase = app.captions::addQuickPhrase,
                            onRemovePhrase = app.captions::removeQuickPhrase,
                            onMovePhrase = app.captions::moveQuickPhrase,
                            onRestorePhrases = app.captions::restoreQuickPhrases,
                            phrasesAreDefault = QuickPhrases.isDefault(current.quickPhrases, Localization.language),
                            look = look,
                            captionSize = current.display.fontSize,
                            onClose = { saying = false },
                            voiceNotices = VoiceNotice.forSay(Localization.language, voices),
                        )
                        Front.History -> HistoryScreen(
                            app.captions.savedConversations,
                            saving = current.saveHistory,
                            retention = current.historyRetention,
                            marksUncertain = current.display.markUncertainLines,
                            emphasizeNumbers = current.display.emphasizeNumbers,
                            onClose = { showingHistory = false },
                            languageCode = current.languageCode,
                        )
                        Front.Captions -> {
                            LaunchedEffect(Unit) { if (app.captions.takeFirstAppearance()) onLaunch() }
                            CaptionScreen(
                                state,
                                current.display,
                                look,
                                onStatusTap,
                                onSettings = {
                                    settingsAtBackup.value = false
                                    showingSettings.value = true
                                },
                                onHistory = { showingHistory = true },
                                onStar = { app.captions.toggleStar(it) },
                                onOpen = { conversation, line ->
                                    app.captions.saveBeforeReading()
                                    reading = conversation to line
                                },
                                onAddWord = { app.captions.addWord(it) },
                                onNameSpeaker = app.captions::nameSpeaker,
                                savedSpeakerNames = { app.captions.savedSpeakerNames },
                                onAskToRepeat = { app.captions.askToRepeat() }.takeIf { app.captions.canAskToRepeat() },
                                onSay = { if (state.speaking) app.captions.stopSpeaking() else saying = true },
                                onDismissSavingTrouble = app.captions::dismissSavingTrouble,
                                onDismissMicrophoneDrop = app.captions::dismissMicrophoneDrop,
                                onMarkProblem = app.captions::markProblem,
                                onMicrophone = { choosingMicrophone = true },
                                offersNameAlert = current.offersNameAlert,
                                onNameAlert = { namingAlert = true },
                                onDismissNameAlert = app.captions::dismissNameAlertOffer,
                                // Not while it slides away under another screen: talk
                                // that resumes then is heard there, not buzzed here.
                                buzzWhenTalkResumes = current.hapticOnSpeechResume && transition.targetState == EnterExitState.Visible,
                                onBuzz = app.captions::buzz,
                                soundSecondsLeft = app.captions::soundBannerSecondsLeft,
                                onDismissSound = app.captions::dismissSoundAlert,
                                onFontSize = { size -> app.settings.change { it.display = it.display.copy(fontSize = size) } },
                                onOpenConversation = { reading = it to null },
                                onDismissRecent = app.captions::dismissRecentConversation,
                                onDismissBattery = app.captions::dismissBatteryWarning,
                            )
                        }
                    }
                }
            }
            if (confirmingCellular.value) {
                CellularDownloadDialog(
                    state.phase.failure?.engineUnavailability?.downloadMegabytes,
                    onDownload = {
                        confirmingCellular.value = false
                        app.captions.approveCellularDownload()
                    },
                    onWait = { confirmingCellular.value = false },
                )
            }
            PairingDialogs(app.pairing)
        }
    }
}

/** The screens in front of each other, by how far in from the captions each is. */
private enum class Front(val depth: Int) { Walkthrough(0), Captions(0), History(1), Say(1), Microphones(1), NameAlert(1), Settings(1), Conversation(2) }

/** The screen in front, with the conversation open on it, so one sliding away still shows what it showed. */
private data class FrontScreen(val kind: Front, val reading: Pair<UUID, UUID?>? = null)

/** As on the iPhone: "Tap to download now" on the waiting-for-Wi-Fi status asks first, saying what cellular data may cost. */
@Composable
private fun CellularDownloadDialog(megabytes: Int?, onDownload: () -> Unit, onWait: () -> Unit) {
    val size = megabytes?.takeIf { it > 0 }?.let { "$it\u00A0MB" } ?: tr("כמה מאות MB", "A few hundred MB")
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text(tr("להוריד את המודל בחבילת הגלישה?", "Download the model over cellular data?")) },
        text = { Text(tr("המודל שוקל %1. בחבילת גלישה זה יכול לעלות כסף או לגמור את נפח הגלישה. ב-Wi-Fi ההורדה תתחיל לבד.", "The model is about %1. Over cellular data this can cost money or use up your data plan. On Wi‑Fi the download will start on its own.", listOf(size))) },
        confirmButton = { TextButton(onClick = onDownload) { Text(tr("להוריד עכשיו", "Download now")) } },
        dismissButton = { TextButton(onClick = onWait) { Text(tr("לחכות ל-Wi-Fi", "Wait for Wi‑Fi")) } },
    )
}

@Composable
private fun KeepScreenOn(keep: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keep) {
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun PairingDialogs(pairing: PairingRequests) {
    val pending by pairing.pending.collectAsStateWithLifecycle()
    val saveFailed by pairing.saveFailed.collectAsStateWithLifecycle()
    val linkBroken by pairing.linkBroken.collectAsStateWithLifecycle()
    pending?.let { shown ->
        AlertDialog(
            onDismissRequest = { pairing.pending.value = null },
            title = { Text(tr("להתחבר למחשב בבית?", "Connect to the home computer?")) },
            text = {
                Text(
                    tr(
                        "הקול ישלח לכתוביות אל %1. אשרו רק אם זה המחשב של המשפחה.",
                        "The audio will go to %1 for captions. Only connect if this is the family’s computer.",
                        listOf(shown.computerName),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.accept(shown) }) { Text(tr("להתחבר", "Connect")) } },
            dismissButton = { TextButton(onClick = { pairing.pending.value = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { pairing.saveFailed.value = false },
            title = { Text(tr("החיבור למחשב בבית לא נשמר", "The connection to the home computer wasn’t saved")) },
            text = {
                Text(
                    tr(
                        "הכתוביות ממשיכות כמו קודם. סרקו שוב את הקוד, או בקשו עזרה ממי שהתקין את הטלפון.",
                        "Captions carry on as before. Scan the code again, or ask whoever set up the phone for help.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.saveFailed.value = false }) { Text(tr("סגירה", "Close")) } },
        )
    }
    if (linkBroken) {
        AlertDialog(
            onDismissRequest = { pairing.linkBroken.value = false },
            title = { Text(tr("הקישור למחשב בבית לא תקין", "The home computer link didn’t come through")) },
            text = {
                Text(
                    tr(
                        "חלק מהקישור חסר או השתבש. סרקו שוב את הריבוע במחשב עם מצלמת הטלפון, ממש מקרוב.",
                        "Part of the link is missing or garbled. Scan the square on the computer again with the phone’s camera, up close.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.linkBroken.value = false }) { Text(tr("סגירה", "Close")) } },
        )
    }
}

@Composable
private fun CaptionScreen(
    state: CaptionScreenState,
    display: DisplayPreferences,
    look: CaptionTheme,
    onStatusTap: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onStar: (UUID) -> Unit,
    onOpen: (conversation: UUID, line: UUID) -> Unit,
    onAddWord: (String) -> Unit,
    onNameSpeaker: (line: UUID, name: String) -> Unit,
    savedSpeakerNames: () -> List<String>,
    onAskToRepeat: (() -> Unit)?,
    onSay: () -> Unit,
    onDismissSavingTrouble: () -> Unit,
    onDismissMicrophoneDrop: () -> Unit,
    onMarkProblem: () -> Boolean,
    onMicrophone: () -> Unit,
    offersNameAlert: Boolean,
    onNameAlert: () -> Unit,
    onDismissNameAlert: () -> Unit,
    buzzWhenTalkResumes: Boolean,
    onBuzz: (AlertVibration) -> Unit,
    soundSecondsLeft: (SoundAlert) -> Double,
    onDismissSound: (UUID) -> Unit,
    onFontSize: (Double) -> Unit,
    onOpenConversation: (UUID) -> Unit,
    onDismissRecent: () -> Unit,
    onDismissBattery: () -> Unit,
) {
    val list = rememberLazyListState()
    // As on the iPhone: the text grows under the fingers and only the
    // final size is saved.
    var pinchScale by remember { mutableDoubleStateOf(1.0) }
    val pinchedSize = DisplayPreferences.fontSize(display.fontSize, pinchScale)
    val savedSize by rememberUpdatedState(display.fontSize)
    var problemMarked by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    var shownWord by remember { mutableStateOf<HeardWord?>(null) }
    // Counted from when this screen appeared: talk that resumed while she
    // was in Settings or away is long over by the time she is back.
    val resumedBefore = remember { state.speechResumed }
    LaunchedEffect(state.speechResumed) {
        if (state.speechResumed > resumedBefore && buzzWhenTalkResumes) onBuzz(AlertVibration.speechResumed)
    }
    // The session buzzes and tells TalkBack, whichever screen is open; the
    // pill is for a word said just now, or one the session tells her of as
    // she comes back. Back from Settings, an old one does not show again.
    LaunchedEffect(state.heard?.id, state.heard?.at) {
        val word = state.heard ?: return@LaunchedEffect
        if (secondsNow() - word.at > HEARD_SHOWN_SECONDS) return@LaunchedEffect
        shownWord = word
        delay((HEARD_SHOWN_SECONDS * 1_000).toLong())
        if (shownWord?.id == word.id) shownWord = null
    }
    // The banner keeps what is left of its time: back from Settings within
    // it, the banner is there again, and one whose time is up is not.
    var shownSound by remember { mutableStateOf<SoundAlert?>(null) }
    LaunchedEffect(state.sound?.id) {
        val alert = state.sound?.takeIf { soundSecondsLeft(it) > 0 }
        shownSound = alert
        if (alert == null) return@LaunchedEffect
        delay((soundSecondsLeft(alert) * 1_000).toLong())
        shownSound = null
    }
    // Kept with the line's text, as the iPhone's sheet keeps its line:
    // turning the phone leaves the dialog open, and so does the line
    // leaving the newest 200 the screen holds while she types.
    var fixing by rememberSaveable { mutableStateOf<String?>(null) }
    fixing?.let { text ->
        FixWordDialog(text, onAdd = { onAddWord(it); fixing = null }, onDismiss = { fixing = null })
    }
    var naming by rememberSaveable { mutableStateOf<UUID?>(null) }
    var namingText by rememberSaveable { mutableStateOf("") }
    var namingHasVoice by rememberSaveable { mutableStateOf(false) }
    val nameSpeakerOf = { line: CaptionLine ->
        namingText = line.text
        namingHasVoice = line.speakerCluster != null
        naming = line.id
    }
    naming?.let { id ->
        NameSpeakerDialog(
            namingText,
            hasVoice = namingHasVoice,
            savedNames = remember(id) { savedSpeakerNames() },
            onSave = { onNameSpeaker(id, it); naming = null },
            onDismiss = { naming = null },
        )
    }
    problemMarked?.let { keptSound -> ProblemMarkedDialog(keptSound, onDismiss = { problemMarked = null }) }
    var following by remember { mutableStateOf(true) }
    var countWhenLeft by remember { mutableIntStateOf(0) }
    val lineCount by rememberUpdatedState(state.lineCount)
    // Only her own scrolling passes through here, not the screen following
    // the newest line, so a line growing past the bottom for a moment
    // doesn't stop the following, as it once did on the iPhone.
    val rereading = remember(list) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val atEnd = !list.canScrollForward
                if (following && !atEnd) countWhenLeft = lineCount
                following = atEnd
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state.lines.isEmpty()) { if (state.lines.isEmpty()) following = true }
    val scope = rememberCoroutineScope()
    val awayLine by rememberUpdatedState(state.away?.line)
    // Seen once it has stayed on screen a moment: coming back, the list can
    // show it for a frame before it follows the newest line down.
    val awayMarkShowing by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            info.visibleItemsInfo.any { it.key == awayLine && it.offset >= info.viewportStartOffset && it.offset < info.viewportEndOffset }
        }
    }
    LaunchedEffect(awayMarkShowing, state.away?.offersJump) {
        if (awayMarkShowing && state.away?.offersJump == true) {
            delay(AWAY_MARK_SEEN_MILLIS)
            CaptionState.acknowledgeAwayLines()
        }
    }
    var lastTouchAt by remember { mutableDoubleStateOf(secondsNow()) }
    var clock by remember { mutableDoubleStateOf(secondsNow()) }
    val listening = state.phase.isListening
    LaunchedEffect(listening) {
        while (listening) {
            clock = secondsNow()
            delay(1_000)
        }
    }
    val hidden = state.hidesButtons(
        autoHide = display.autoHideControls,
        followingLatest = following,
        screenReaderOn = screenReaderOn(),
        lastTouchAt = lastTouchAt,
        now = clock,
    )
    val (flashColour, flashStrength) = alertFlash(state.heardSound)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (flashStrength > 0f) {
                    drawRect(flashColour, alpha = 0.2f * flashStrength)
                    drawRect(flashColour, alpha = flashStrength, style = Stroke(width = 44.dp.toPx()))
                }
            }
            .background(look.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouchAt = secondsNow()
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // A few banners at once, at large text on a small phone, took
            // all the room and left no line of the captions: past about
            // half the room they scroll.
            val bannerRoom = maxHeight * 0.55f
            val bannerRoomPixels = constraints.maxHeight * 0.55f
            // Only while they overflow: an empty strip that could scroll was a
            // second list on the screen, for a screen reader and for tests.
            var bannersOverflow by remember { mutableStateOf(false) }
            val bannerScroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize()) {
                // Above the captions, pushing them down, not drawn over them: over
                // the lines, their words showed through a banner and around the
                // yellow pill, letters on letters.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = bannerRoom)
                        .then(if (bannersOverflow) Modifier.fadeWhenMoreBelow(bannerScroll).verticalScroll(bannerScroll) else Modifier)
                        .onSizeChanged { bannersOverflow = it.height >= bannerRoomPixels - 1 },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArrivingBanner(shownSound) { alert ->
                        SoundAlertBanner(alert) {
                            shownSound = null
                            onDismissSound(alert.id)
                        }
                    }
                    ArrivingBanner(shownWord) { word ->
                        val speaker = state.lines.firstOrNull { it.id == word.line }?.speaker?.takeIf { display.showSpeakerNames }
                        HeardPill(word.said, speaker, Modifier)
                    }
                    ArrivingBanner(state.away?.takeIf { it.offersJump && !awayMarkShowing && state.lines.isNotEmpty() }) { mark ->
                        AwayJump(mark.count, look) {
                            CaptionState.acknowledgeAwayLines()
                            countWhenLeft = lineCount
                            following = false
                            val index = state.lines.indexOfFirst { it.id == mark.line }
                            if (index >= 0) scope.launch { list.scrollToItem(index) }
                        }
                    }
                    ArrivingBanner(Unit.takeIf { state.savingTrouble }) { SavingTroubleBanner(onDismissSavingTrouble) }
                    ArrivingBanner(state.dropped) { lost ->
                        MicrophoneDropBanner(
                            title = MicrophoneDropNotice.titleFor(lost),
                            detail = MicrophoneDropNotice.detail(state.captionsRunning),
                            onChooseMicrophone = {
                                onDismissMicrophoneDrop()
                                onMicrophone()
                            },
                            onDismiss = onDismissMicrophoneDrop,
                        )
                    }
                    ArrivingBanner(state.battery) { BatteryBanner(it, onDismissBattery) }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().pinchToResize(
                        onScale = { pinchScale *= it },
                        onEnd = {
                            val size = DisplayPreferences.fontSize(savedSize, pinchScale)
                            pinchScale = 1.0
                            if (size != savedSize) onFontSize(size)
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.lines.isEmpty()) {
                        // Scrolls when it can't all fit, at the phone's largest text
                        // or turned sideways: the offer's last line was cut off there,
                        // and still looked cut until the edge faded to show there's more.
                        val emptyScroll = rememberScrollState()
                        Column(
                            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().fadeWhenMoreBelow(emptyScroll).verticalScroll(emptyScroll).padding(top = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Not while a call holds the microphone: the phase stays
                            // on listening, but nothing is being heard.
                            val running = state.captionsRunning
                            Text(
                                text = if (running) tr("מקשיב.", "Listening.") else tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                                color = look.text,
                                fontSize = pinchedSize.sp,
                                lineHeight = (pinchedSize * 1.25).sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (running) {
                                    tr(
                                        "כשמישהו ידבר, המילים יופיעו כאן בזמן אמת. השאירו אצבע על שורה כדי לסמן אותה כחשובה או לתת שם לדובר, וצבטו בשתי אצבעות כדי להגדיל או להקטין את הטקסט.",
                                        "When someone talks, the words will appear here in real time. Press and hold a line to mark it as important or name the speaker, and pinch with two fingers to make the text bigger or smaller.",
                                    )
                                } else {
                                    tr("אפשר לבחור מיקרופון בכפתור למטה מימין ולשנות מנוע תמלול בהגדרות.", "You can choose a microphone with the microphone button at the bottom, and change the transcription engine in Settings.")
                                },
                                color = look.pendingText,
                                fontSize = maxOf(17.0, pinchedSize * 0.6).sp,
                                lineHeight = (maxOf(17.0, pinchedSize * 0.6) * 1.35).sp,
                            )
                            val recent = state.recentConversation
                            if (recent != null) {
                                RecentConversationCard(recent, look, onOpen = { onOpenConversation(recent.id) }, onDismiss = onDismissRecent)
                            } else if (offersNameAlert) {
                                NameAlertOffer(look, onNameAlert, onDismissNameAlert)
                            }
                        }
                    } else {
                        CaptionLines(state.lines, state.away, state.earlier, display.copy(fontSize = pinchedSize), look, list, following, Modifier.nestedScroll(rereading), onStar, onOpen, onFix = { fixing = it.text }, onName = nameSpeakerOf, onAskToRepeat = onAskToRepeat, onMarkProblem = { problemMarked = onMarkProblem() })
                        if (!following) {
                            JumpToLatest(jumpToLatestTitle(linesSince(state.lineCount, countWhenLeft)), look, Modifier.align(Alignment.BottomCenter)) {
                                following = true
                            }
                        }
                    }
                    if (pinchScale != 1.0) {
                        Text(
                            tr("גודל טקסט %1", "Text size %1", listOf("${pinchedSize.toInt()}")),
                            color = look.chrome,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(look.background.copy(alpha = 0.9f), RoundedCornerShape(50))
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = !hidden) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusButton(state.status, look.chrome, live = state.captionsRunning, onTap = onStatusTap)
                val history = tr("היסטוריה", "History")
                val microphone = state.selectedInput?.let(::inputShortName) ?: tr("מיקרופון", "Microphone")
                val sayWord = if (state.speaking) "■ " + tr("להגיד", "Say") else tr("להגיד", "Say")
                val settingsWord = tr("הגדרות", "Settings")
                // The row's words grow with the phone's text size only so far,
                // as the iPhone's stop at its xxxLarge, and each button's share
                // of the row follows its word's own width: at the largest text
                // the four shrink together, where the last one used to break a
                // few letters to a line; at usual sizes each keeps its width.
                // Each button keeps only a little padding at its sides: with
                // the usual padding in every share, German's "Verlauf" and
                // "Sagen" were cut to "Verla…" and "Sag…".
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, LARGEST_ORDINARY_FONT_SCALE))) {
                    val measurer = rememberTextMeasurer()
                    val barStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp)
                    val buttonPadding = with(LocalDensity.current) { (BAR_SIDE_PADDING * 2).toPx() }
                    val padding = PaddingValues(horizontal = BAR_SIDE_PADDING, vertical = 8.dp)
                    val share = { word: String -> measurer.measure(word, barStyle, maxLines = 1).size.width + buttonPadding }
                    // The microphone's name gives way first: a long headset
                    // name cut History, Say and Settings to "Histo…", "S…" and
                    // "Settin…" on a small phone. The three keep their words
                    // whole; the name takes what room is left, shrinking and,
                    // past that, cut.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val shortest = with(LocalDensity.current) { 56.dp.toPx() }
                        val widest = with(LocalDensity.current) { 120.dp.toPx() }
                        val left = constraints.maxWidth - share(history) - share(sayWord) - share(settingsWord)
                        val microphoneShare = minOf(share(microphone), widest, maxOf(left, shortest))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onHistory, contentPadding = padding, modifier = Modifier.weight(share(history), fill = false)) {
                                BarWord(history)
                            }
                            val choose = tr("בחירת מיקרופון", "Choose microphone")
                            TextButton(
                                onClick = onMicrophone,
                                contentPadding = padding,
                                modifier = Modifier.weight(microphoneShare, fill = false).widthIn(max = 120.dp).semantics {
                                    contentDescription = choose
                                    stateDescription = state.selectedInput?.portName ?: ""
                                },
                            ) {
                                // Shrunk to fit as on the iPhone; only a name too long even then is cut.
                                Text(
                                    microphone,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp),
                                )
                            }
                            // While the phone talks, this stops it at once instead of
                            // opening the Say screen, as on the iPhone: captions stay
                            // paused for as long as it talks.
                            val say = if (state.speaking) tr("עצירת הדיבור", "Stop speaking") else tr("להגיד משהו בקול", "Say something out loud")
                            TextButton(onClick = onSay, contentPadding = padding, modifier = Modifier.weight(share(sayWord), fill = false).semantics { contentDescription = say }) {
                                BarWord(sayWord)
                            }
                            TextButton(onClick = onSettings, contentPadding = padding, modifier = Modifier.weight(share(settingsWord), fill = false)) {
                                BarWord(settingsWord)
                            }
                        }
                    }
                }
            }
        }
        if (hidden) ShowButtonsButton(look.chrome) { lastTouchAt = secondsNow() }
    }
}

/** A word on the bottom row, on one line, shrunk as far as the iPhone's to fit. */
@Composable
private fun BarWord(word: String) {
    Text(word, maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 18.sp))
}

/**
 * Two fingers spreading or closing anywhere over the captions; one finger
 * still scrolls and holds lines. Seen before the lines see it, so a pinch
 * neither scrolls the list nor opens a line's menu.
 */
private fun Modifier.pinchToResize(onScale: (Double) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    pinching = true
                    onScale(zoom.toDouble())
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) onEnd()
    }
}

private fun secondsNow() = System.currentTimeMillis() / 1_000.0

private const val HEARD_SHOWN_SECONDS = 5.0

private const val AWAY_MARK_SEEN_MILLIS = 1_000L

internal const val LARGEST_ORDINARY_FONT_SCALE = 1.5f

private val BAR_SIDE_PADDING = 4.dp

// A deep orange as on the iPhone, with white words on it at 5.5:1.
private val TROUBLE_ORANGE = Color(red = 0.7f, green = 0.28f, blue = 0f)

// The deeper red for a battery about to run out, white words on it at 6.6:1.
private val CRITICAL_RED = Color(red = 0.7f, green = 0.1f, blue = 0.1f)

@Composable
private fun screenReaderOn(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * The one control left while the buttons are away, as on the iPhone: at
 * least the 44-point target a shaky finger needs. A touch anywhere on the
 * screen brings the buttons back too.
 */
@Composable
private fun ShowButtonsButton(chrome: Color, onTap: () -> Unit) {
    val label = tr("הצגת הכפתורים", "Show the buttons")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 44.dp)
                .background(chrome.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 20.dp, height = 11.dp)) {
                val chevron = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                }
                drawPath(chevron, chrome, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun CaptionLines(
    lines: List<CaptionLine>,
    away: AwayMark?,
    earlier: EarlierLines?,
    display: DisplayPreferences,
    look: CaptionTheme,
    list: LazyListState,
    following: Boolean,
    modifier: Modifier,
    onStar: (UUID) -> Unit,
    onOpen: (conversation: UUID, line: UUID) -> Unit,
    onFix: (CaptionLine) -> Unit,
    onName: (CaptionLine) -> Unit,
    onAskToRepeat: (() -> Unit)?,
    onMarkProblem: () -> Unit,
) {
    LaunchedEffect(lines.size, lines.lastOrNull()?.text, following, display.fontSize) {
        if (following) list.scrollToEnd(lines.lastIndex)
    }
    // The buttons coming back, or a turn of the phone, change the room the
    // lines have; the list keeps its top, so the newest line was left under
    // the buttons until the next word, a long time in a quiet room. The
    // iPhone follows again on both.
    LaunchedEffect(list, following) {
        if (!following) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { if (list.layoutInfo.totalItemsCount > 0) list.scrollToEnd(list.layoutInfo.totalItemsCount - 1) }
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines, key = { it.id }) { line ->
            val row = @Composable {
                CaptionLineRow(
                    line,
                    display,
                    look,
                    onStar = { onStar(line.id) },
                    onOpen = { conversation -> onOpen(conversation, line.id) },
                    onFix = { onFix(line) },
                    onName = { onName(line) },
                    onAskToRepeat = onAskToRepeat,
                    onMarkProblem = onMarkProblem,
                )
            }
            val gap = line.afterQuiet
            val note = earlier?.takeIf { line.id == lines.first().id }
            val missed = away?.takeIf { line.id == it.line }
            // A new line fades in where it lands; the lines already there
            // stay still, as moving them would move what she is reading.
            Box(Modifier.animateItem(fadeInSpec = tween(250), placementSpec = null, fadeOutSpec = null)) {
                if (note != null || missed != null || gap != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (note != null) EarlierLinesNote(note, display.fontSize, look) { conversation -> onOpen(conversation, note.line) }
                        if (missed != null) AwayDivider(missed.count, display.fontSize, look) else if (gap != null) QuietGapDivider(gap, display.fontSize, look)
                        row()
                    }
                } else {
                    row()
                }
            }
        }
    }
}

/**
 * To the bottom of the newest line, not its top: a long line at a large
 * size can be taller than the screen, and its newest words come last.
 */
private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val hidden = last.offset + last.size - layoutInfo.viewportEndOffset
    if (hidden > 0) scrollBy(hidden.toFloat())
}

@Composable
private fun JumpToLatest(title: String, look: CaptionTheme, modifier: Modifier, onTap: () -> Unit) {
    Row(
        modifier = modifier
            .padding(bottom = 8.dp)
            .heightIn(min = 48.dp)
            .background(look.background.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
            .border(width = 1.dp, color = look.chrome.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp))
            .clickable(onClickLabel = tr("מעבר לשורה האחרונה", "Jump to the last line"), role = Role.Button, onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↓", color = look.chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clearAndSetSemantics {})
        Text(text = title, color = look.chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * Above the first line said while the screen was away, as on the iPhone:
 * how many lines she missed, so she knows where she stopped reading.
 */
/**
 * Above the oldest line kept, as on the iPhone: the earlier ones are a
 * tap away while they are saved, opened at the newest of them.
 */
@Composable
private fun EarlierLinesNote(earlier: EarlierLines, captionSize: Double, look: CaptionTheme, onOpen: (UUID) -> Unit) {
    val size = maxOf(15.0, captionSize * 0.5).sp
    val saved = earlier.conversation
    if (saved == null) {
        Text(tr("שורות מוקדמות יותר כבר לא מוצגות", "Earlier lines are no longer shown"), color = look.pendingText, fontSize = size, modifier = Modifier.fillMaxWidth())
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(saved) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💬", fontSize = size, modifier = Modifier.clearAndSetSemantics {})
        Text(tr("שורות מוקדמות יותר נשמרו. הקישו כדי לקרוא אותן", "Earlier lines were saved. Tap to read them"), color = look.chrome, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AwayDivider(count: Int, captionSize: Double, look: CaptionTheme) {
    val lines = ConversationStats.linesText(count)
    val spoken = tr("מכאן, מה שנאמר כשהאפליקציה הייתה סגורה: %1", "From here, what was said while the app was closed: %1", listOf(lines))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("נאמר כשהאפליקציה הייתה סגורה · %1", "Said while the app was closed · %1", listOf(lines)),
            color = look.pendingText,
            fontSize = maxOf(15.0, captionSize * 0.5).sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.weight(1f).height(2.dp).background(look.pendingText))
    }
}

/**
 * Between two lines with a quiet stretch of five minutes or more, as on the
 * iPhone: the clock time talk picked up again, so a sentence from half an
 * hour ago isn't read as the one before the words just said.
 */
@Composable
private fun QuietGapDivider(gap: QuietGap, captionSize: Double, look: CaptionTheme) {
    val time = resumedTime(gap)
    val spoken = tr("אחרי הפסקה, מהשעה %1", "After a break, from %1", listOf(time))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(look.pendingText.copy(alpha = 0.5f)))
        Text(
            text = time,
            color = look.pendingText,
            fontSize = maxOf(15.0, captionSize * 0.45).sp,
            fontWeight = FontWeight.Medium,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(look.pendingText.copy(alpha = 0.5f)))
    }
}

/** Back at the newest line after a while away, a way up to where the lines she missed begin, until she goes there or sees the mark. */
@Composable
private fun AwayJump(count: Int, look: CaptionTheme, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(look.background.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
            .border(width = 1.dp, color = look.chrome.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↑", color = look.chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = tr("מה שנאמר בינתיים · %1", "What was said meanwhile · %1", listOf(ConversationStats.linesText(count))),
            color = look.chrome,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * One caption line, laid out in its own reading direction as on the iPhone,
 * so a Hebrew line starts at the right whatever language the buttons are
 * in. A line the engine wasn't sure of gets a question mark at its start
 * and dots under the words it doubted, without changing their colour: the
 * words stay as readable as any other line. Held, as on the iPhone, it can
 * be marked as important (a star), its speaker named ("Who is speaking?")
 * or copied, and, with a voice on the phone
 * for it, the phone asks them aloud to repeat it; "Captions not good?" keeps
 * what was running in the diagnostics journal. A screen reader has these too.
 */
@Composable
private fun CaptionLineRow(
    line: CaptionLine,
    display: DisplayPreferences,
    look: CaptionTheme,
    onStar: () -> Unit,
    onOpen: (UUID) -> Unit,
    onFix: () -> Unit,
    onName: () -> Unit,
    onAskToRepeat: (() -> Unit)?,
    onMarkProblem: () -> Unit,
) {
    val doubtful = if (display.markUncertainLines) line.doubtfulWords else emptyList()
    val spoken = UncertainWords.spoken(line.text, doubtful)
    val colour = if (line.isFinal) look.text else look.pendingText
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val star = if (line.starred) tr("ביטול הסימון", "Remove mark") else tr("סימון כחשוב", "Mark as important")
    val copy = tr("העתקה", "Copy")
    val fix = tr("תיקון מילה למילון", "Fix a word for next time")
    val ask = tr("לבקש שיחזרו על זה", "Ask them to repeat that")
    val who = tr("מי מדבר?", "Who is speaking?")
    val open =tr("פתיחת השיחה כולה, לשיתוף או לחיפוש", "Open the whole conversation, to share or search")
    val problem = tr("הכתוביות לא טובות? לסמן בעיה", "Captions not good? Mark a problem")
    val context = LocalContext.current
    val copyLine = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(copy, line.copied)) }
    val uris = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().onHold { menu = true }) {
        CaptionLineContent(
            line = line,
            display = display,
            look = look,
            doubtful = doubtful,
            spoken = spoken,
            colour = colour,
            onLayout = { layout = it },
            layout = layout,
            actions = listOf(
                CustomAccessibilityAction(star) { onStar(); true },
            ) + listOfNotNull(onAskToRepeat).map { say ->
                CustomAccessibilityAction(ask) { say(); true }
            } + listOf(
                CustomAccessibilityAction(who) { onName(); true },
                CustomAccessibilityAction(fix) { onFix(); true },
                CustomAccessibilityAction(copy) { copyLine(); true },
            ) + listOfNotNull(line.conversation).map { conversation ->
                CustomAccessibilityAction(open) { onOpen(conversation); true }
            } + listOf(
                CustomAccessibilityAction(problem) { onMarkProblem(); true },
            ) + line.phones.map { phone ->
                CustomAccessibilityAction(tr("להתקשר ל־%1", "Call %1", listOf(phone.dialable))) { uris.openUri(phone.url); true }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(star) }, onClick = { menu = false; onStar() })
            onAskToRepeat?.let { say -> DropdownMenuItem(text = { Text(ask) }, onClick = { menu = false; say() }) }
            DropdownMenuItem(text = { Text(who) }, onClick = { menu = false; onName() })
            DropdownMenuItem(text = { Text(fix) }, onClick = { menu = false; onFix() })
            DropdownMenuItem(text = { Text(copy) }, onClick = { menu = false; copyLine() })
            line.conversation?.let { conversation ->
                DropdownMenuItem(text = { Text(open) }, onClick = { menu = false; onOpen(conversation) })
            }
            DropdownMenuItem(text = { Text(problem) }, onClick = { menu = false; onMarkProblem() })
        }
    }
}

@Composable
private fun CaptionLineContent(
    line: CaptionLine,
    display: DisplayPreferences,
    look: CaptionTheme,
    doubtful: List<String>,
    spoken: List<String>,
    colour: Color,
    onLayout: (TextLayoutResult) -> Unit,
    layout: TextLayoutResult?,
    actions: List<CustomAccessibilityAction>,
) {
    val speaker = line.speaker?.takeIf { display.showSpeakerNames }
    CompositionLocalProvider(LocalLayoutDirection provides if (line.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (speaker != null && line.showsSpeaker) SpeakerLabel(speaker, line.speakerCluster, display.fontSize, look)
            CaptionLineWords(line, display, look, doubtful, spoken, colour, onLayout, layout, actions, speaker)
        }
    }
}

/**
 * Like a chat, as on the iPhone: the name heads a run of lines by one
 * person, in their colour, with a dot. A screen reader hears the name on
 * every line instead, so the label itself is skipped.
 */
@Composable
private fun SpeakerLabel(name: String, cluster: Int?, captionSize: Double, look: CaptionTheme) {
    val colour = SpeakerColour.of(cluster, name, look.isLight)
    Row(
        modifier = Modifier.semantics { hideFromAccessibility() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = colour, fontSize = maxOf(15.0, captionSize * 0.5).sp, fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.size(10.dp).background(colour, CircleShape))
    }
}

@Composable
private fun CaptionLineWords(
    line: CaptionLine,
    display: DisplayPreferences,
    look: CaptionTheme,
    doubtful: List<String>,
    spoken: List<String>,
    colour: Color,
    onLayout: (TextLayoutResult) -> Unit,
    layout: TextLayoutResult?,
    actions: List<CustomAccessibilityAction>,
    speaker: String?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (line.starred) StarMark(display.fontSize, starColour(look.isLight))
        if (line.important) BellMark(display.fontSize, look.text)
        if (display.markUncertainLines && line.unsure) UnsureMark(display.fontSize, look.pendingText)
        Text(
            text = captionText(
                line.text,
                display.emphasizeNumbers,
                numberColor = if (line.isFinal) look.numberText else null,
                doubtfulWords = doubtful,
                linkingPhoneNumbers = line.phones.isNotEmpty(),
                decimalComma = LocalDecimalComma.current,
            ),
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            color = colour,
            fontSize = display.fontSize.sp,
            lineHeight = (display.fontSize * 1.25).sp,
            fontWeight = if (display.boldText) FontWeight.Bold else FontWeight.Medium,
            onTextLayout = onLayout,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (line.important) Modifier.background(importantField(look.isLight), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier)
                .drawBehind { layout?.let { drawDoubtDots(it, colour) } }
                // One stop for a screen reader, as on the iPhone: the marks
                // beside the words are heard here, not each on its own.
                .semantics {
                    val label = CaptionLineSpeech.label(line.spoken, speaker, line.starred, display.markUncertainLines && line.unsure)
                    if (label != line.text) contentDescription = label
                    CaptionLineSpeech.state(line.important, spoken)?.let { stateDescription = it }
                    customActions = actions
                },
        )
    }
}

// The iPhone's soft yellow field, so the line where a name was said is found again scrolling back.
private fun importantField(light: Boolean) = Color.Yellow.copy(alpha = if (light) 0.35f else 0.22f)

@Composable
private fun BellMark(fontSize: Double, colour: Color) {
    val side = maxOf(14.0, fontSize * 0.6)
    val inset = with(LocalDensity.current) { ((fontSize * 1.25 - side) / 2).sp.toDp() }
    val size = with(LocalDensity.current) { side.sp.toDp() }
    Canvas(
        modifier = Modifier
            .padding(top = inset)
            .padding(end = 8.dp)
            .size(size)
            .clearAndSetSemantics {
                contentDescription = tr("מכילה מילה חשובה", "Contains an important word")
                hideFromAccessibility()
            },
    ) {
        val w = this.size.width
        val h = this.size.height
        val bell = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            cubicTo(w * 0.22f, h * 0.08f, w * 0.2f, h * 0.35f, w * 0.2f, h * 0.55f)
            lineTo(w * 0.1f, h * 0.75f)
            lineTo(w * 0.9f, h * 0.75f)
            lineTo(w * 0.8f, h * 0.55f)
            cubicTo(w * 0.8f, h * 0.35f, w * 0.78f, h * 0.08f, w * 0.5f, h * 0.08f)
            close()
        }
        drawPath(bell, colour)
        drawCircle(colour, radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.86f))
    }
}

/**
 * "Saving on the phone failed" over the captions, as on the iPhone, until
 * she taps it away; it comes back only once saving has worked and then
 * failed again. The iPhone's way to free space is left out of the words.
 */
/**
 * Where a problem marked from a caption line went, and how it reaches
 * whoever helps her: a kept sound is sent from its own row in Diagnostics.
 */
@Composable
private fun ProblemMarkedDialog(keptSound: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("הבעיה סומנה", "Problem marked")) },
        text = { Text(if (keptSound) tr("פרטי התקלה, השורות האחרונות ו-30 השניות האחרונות של הקול נשמרו בטלפון בלבד. כדי לשלוח אותם למי שעוזר לך: הגדרות ← אבחון.", "The problem's details, the last few lines and the last 30 seconds of sound were saved, on the phone only. To send them to whoever helps you: Settings → Diagnostics.") else tr("פרטי התקלה והשורות האחרונות נשמרו בטלפון בלבד. כדי לשלוח אותם למי שעוזר לך: הגדרות ← אבחון ← שליחת הדוח.", "The problem's details and the last few lines were saved, on the phone only. To send them to whoever helps you: Settings → Diagnostics → Send report.")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("אישור", "OK")) } },
    )
}

@Composable
private fun SavingTroubleBanner(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(TROUBLE_ORANGE, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = tr("סגירה", "Close"), onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("⚠", color = Color.White, fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {})
        Column(Modifier.weight(1f)) {
            Text(SavingTroubleNotice.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(SavingTroubleNotice.detail, color = Color.White, fontSize = 16.sp)
        }
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** "Battery at 19%", as the iPhone's: deeper red when the phone may shut down, a tap anywhere puts it away. */
@Composable
private fun BatteryBanner(warning: BatteryWarning, onDismiss: () -> Unit) {
    val critical = warning is BatteryWarning.Critical
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (critical) CRITICAL_RED else TROUBLE_ORANGE, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = tr("סגירה", "Close"), onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("🔋", fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {})
        Column(Modifier.weight(1f)) {
            Text(tr("הסוללה ב-%1%", "Battery at %1%", listOf("${warning.percent}")), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (critical) {
                    tr("הטלפון עלול להיכבות באמצע השיחה. חברו למטען.", "The phone might shut down mid-conversation. Plug in a charger.")
                } else {
                    tr("כדאי לחבר למטען.", "Consider plugging in a charger.")
                },
                color = Color.White,
                fontSize = 16.sp,
            )
        }
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** As the iPhone's: a tap on the words opens the microphone picker, the ✕ only puts the notice away. */
@Composable
private fun MicrophoneDropBanner(title: String, detail: String, onChooseMicrophone: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(TROUBLE_ORANGE, RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = tr("בחירת מיקרופון", "Choose microphone"), onClick = onChooseMicrophone)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text("🎙", fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {})
            Column {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color.White, fontSize = 16.sp)
            }
        }
        val close = tr("סגירה", "Close")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = close },
        ) {
            Text("✕", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** As the iPhone's card, for a phone whose walkthrough was skipped: the buzz for her name only works once the name is in. */
@Composable
private fun NameAlertOffer(look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 32.dp).fillMaxWidth().background(look.chrome.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Text("🔔", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
            Column {
                Text(tr("שהטלפון ירטוט כשקוראים לך?", "Have the phone buzz when someone calls your name?"), color = look.chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("הקישו כדי לכתוב את השם שלך", "Tap to write your name"), color = look.chrome, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val notNow = tr("לא עכשיו", "Not now")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = notNow },
        ) {
            Text("✕", color = look.chrome, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** The conversation the app closed in the middle of, a tap away, as on the iPhone. */
@Composable
private fun RecentConversationCard(recent: TranscriptSessionSummary, look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val minutes = RecentConversation.minutesAgo(recent, now = secondsNow())
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(look.chrome.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Text("💬", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("השיחה מ%1 נשמרה", "The conversation from %1 was saved", listOf(HebrewTime.minutesAgo(minutes))), color = look.chrome, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(CaptionLayout.directed(recent.title ?: recent.preview), color = look.chrome.copy(alpha = 0.8f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("הקישו כדי לקרוא אותה", "Tap to read it"), color = look.chrome, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val close = tr("סגירה", "Close")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = close },
        ) {
            Text("✕", color = look.chrome, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/**
 * "Said: grandma" for five seconds at the top, as the iPhone's pill, with
 * who said it when the line's voice is known; the screen reader reads it
 * as it appears.
 */
@Composable
private fun HeardPill(said: String, speaker: String?, modifier: Modifier) {
    Text(
        speaker?.let { tr("%1 — נאמר: %2", "%1 said: %2", listOf(it, said)) } ?: tr("נאמר: %1", "Said: %1", listOf(said)),
        color = Color.Black,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(Color.Yellow.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * A banner above the captions slides down into place and folds away again
 * as it goes, the lines moving with it rather than jumping. While it folds
 * away it keeps showing what it said.
 */
@Composable
private fun <T : Any> ArrivingBanner(shown: T?, content: @Composable (T) -> Unit) {
    val kept = remember { arrayOfNulls<Any>(1) }
    if (shown != null) kept[0] = shown
    AnimatedVisibility(
        visible = shown != null,
        enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically { -it / 2 } + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        @Suppress("UNCHECKED_CAST")
        (kept[0] as T?)?.let { Box(Modifier.padding(bottom = 12.dp)) { content(it) } }
    }
}

@Composable
private fun StarMark(fontSize: Double, colour: Color) {
    val side = maxOf(14.0, fontSize * 0.6)
    val inset = with(LocalDensity.current) { ((fontSize * 1.25 - side) / 2).sp.toDp() }
    Text(
        "★",
        color = colour,
        fontSize = side.sp,
        lineHeight = side.sp,
        modifier = Modifier
            .padding(top = inset)
            .padding(end = 8.dp)
            .clearAndSetSemantics {
                contentDescription = tr("מסומן כחשוב. ", "Marked as important. ").trim()
                hideFromAccessibility()
            },
    )
}

@Composable
internal fun UnsureMark(fontSize: Double, colour: Color) {
    val label = tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ").trim()
    val side = maxOf(14.0, fontSize * 0.6)
    val inset = with(LocalDensity.current) { ((fontSize * 1.25 - side) / 2).sp.toDp() }
    val diameter = with(LocalDensity.current) { side.sp.toDp() }
    Box(
        modifier = Modifier
            .padding(top = inset)
            .padding(end = 8.dp)
            .size(diameter)
            .border(width = 2.dp, color = colour, shape = CircleShape)
            .clearAndSetSemantics {
                contentDescription = label
                hideFromAccessibility()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = colour, fontSize = (side * 0.7).sp, lineHeight = (side * 0.7).sp, fontWeight = FontWeight.Bold)
    }
}

/** A row of dots under each doubted word, line by line where a word wraps. */
private fun DrawScope.drawDoubtDots(layout: TextLayoutResult, colour: Color) {
    val text = layout.layoutInput.text
    val radius = 1.5.dp.toPx()
    for (word in text.getStringAnnotations(DOUBTFUL_WORD, 0, text.length)) {
        (word.start until word.end)
            .filterNot { text[it].isWhitespace() }
            .groupBy { layout.getLineForOffset(it) }
            .forEach { (row, offsets) ->
                val boxes = offsets.map { layout.getBoundingBox(it) }
                val left = boxes.minOf { it.left }
                val right = boxes.maxOf { it.right }
                val y = minOf(layout.getLineBaseline(row) + radius * 3, layout.getLineBottom(row) - radius)
                var x = left + radius
                while (x <= right - radius) {
                    drawCircle(colour, radius, Offset(x, y))
                    x += radius * 3.5f
                }
            }
    }
}

@Composable
private fun StatusButton(status: PhasePresentation, chrome: Color, live: Boolean, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(chrome.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            // As on the iPhone, no button while a tap would do nothing.
            .clickable(enabled = status.action != PhasePresentation.Action.None, onClick = onTap)
            // A download's bar is read with the button, in one stop: the
            // bar alone is an item of its own to TalkBack.
            .semantics { status.progress?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..1f) } }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The title already says what it is busy with; only a download's bar is read out.
            if (status.isBusy) {
                CircularProgressIndicator(color = chrome, strokeWidth = 2.dp, modifier = Modifier.size(18.dp).testTag(STATUS_BUSY).clearAndSetSemantics {})
            } else if (live) {
                LiveDot()
            }
            Text(text = status.title, color = chrome, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
        status.detail?.let { detail ->
            Text(text = detail, color = chrome.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        status.progress?.let { progress ->
            LinearProgressIndicator(progress = { progress.toFloat() }, color = chrome, trackColor = chrome.copy(alpha = 0.2f), modifier = Modifier.padding(top = 6.dp).widthIn(max = 160.dp).clearAndSetSemantics {})
        }
    }
}

internal const val STATUS_BUSY = "statusBusy"

/**
 * A red dot beside "Listening" that breathes while the microphone is on, as
 * a recording light does; still for a phone that asks for less motion.
 */
@Composable
private fun LiveDot() {
    val breathing = !ReducedMotion.isOn()
    val glow = if (breathing) {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "live dot",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(10.dp).graphicsLayer { alpha = glow }.background(LIVE_RED, CircleShape).clearAndSetSemantics {})
}

private val LIVE_RED = Color(0xFFE53935)

/**
 * On a tablet, reading screens keep a phone's line length in the middle
 * instead of running each row from one edge of the glass to the other.
 */
@Composable
private fun OnTabletWidth(capped: Boolean, content: @Composable () -> Unit) {
    if (capped) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
        }
    } else {
        content()
    }
}
