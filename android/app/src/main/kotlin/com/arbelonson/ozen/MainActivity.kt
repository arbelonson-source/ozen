package com.arbelonson.ozen

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
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
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.HebrewTime
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.MicrophoneAsk
import com.arbelonson.ozen.core.MicrophoneDropNotice
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.RecentConversation
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.SharedRecordingLink
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.UncertainWords
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.DialogText
import com.arbelonson.ozen.ui.IconBadge
import com.arbelonson.ozen.ui.OzenColors
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.OzenTheme
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.ToolbarButton
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val showingSettings = mutableStateOf(false)
    private val settingsAtBackup = mutableStateOf(false)
    private val confirmingCellular = mutableStateOf(false)
    // Set by the tile's and the shortcut's start requests until the screens
    // held inside the composition have closed, so the captions are in front;
    // saved, so a rebuild in between doesn't drop it.
    private val captionsAsked = mutableStateOf(false)
    // Every answer counts, even a second "no" after the first: the walkthrough
    // then looks again at whether the system would still ask.
    internal val microphoneAnswer = mutableStateOf<Boolean?>(null, neverEqualPolicy())
    internal val notificationsAnswer = mutableStateOf<Boolean?>(null)

    /** Whether the system would still show its microphone question after a refusal; replaced by device tests. */
    internal var canAskMicrophoneAgain: () -> Boolean = { shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) }

    private val approveMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { microphoneAnswer.value = it }
    private val approveNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAnswer.value = it }

    // Two requests rather than one with a remembered flag: the system hands a
    // result back to the request that asked, even to an activity rebuilt while
    // its dialog was open, and a flag would have been lost with the old one.
    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        microphoneAnswered(granted, soundsOnly = false)
    }
    private val askForMicrophoneForSoundsOnly = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        microphoneAnswered(granted, soundsOnly = true)
    }

    private fun microphoneAnswered(granted: Map<String, Boolean>, soundsOnly: Boolean) {
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            if (soundsOnly) ListeningService.startSoundsOnly(this) else ListeningService.start(this)
        } else {
            (application as OzenApplication).captions.listen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turning the phone or a new theme rebuilds the activity; the
        // screens below Settings are kept by rememberSaveable already.
        showingSettings.value = savedInstanceState?.getBoolean(SHOWING_SETTINGS) == true
        captionsAsked.value = savedInstanceState?.getBoolean(CAPTIONS_ASKED) == true
        settingsAtBackup.value = savedInstanceState?.getBoolean(SETTINGS_AT_BACKUP) == true
        confirmingCellular.value = savedInstanceState?.getBoolean(CONFIRMING_CELLULAR) == true
        val app = application as OzenApplication
        StartCaptionsRequest.applyAppLanguage(app.settings)
        StartCaptionsRequest.publishShortcut(this)
        enableEdgeToEdge()
        setContent { OzenApp(app, showingSettings, settingsAtBackup, confirmingCellular, captionsAsked, onboarding(), onStatusTap = ::statusTapped, onLaunch = ::startListening, onSoundsOnly = ::startSoundsOnly, onAppLanguage = ::chooseAppLanguage, onLightScreen = ::systemBarsFor) }
        // Once per link: a rebuilt screen (a turn, a new language, the app
        // back after Android ended it) and an opening from recent apps carry
        // the link the app was first opened with, and asking about it again
        // offered to replace the address and code set since.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            openLink(intent)
            if (takeSharedRecording(intent)) app.captions.takeFirstAppearance()
        }
        startFromRequest(intent, newIntent = false, recreated = savedInstanceState != null)
    }

    private fun startFromRequest(intent: Intent?, newIntent: Boolean, recreated: Boolean) {
        val app = application as OzenApplication
        val start = StartCaptionsRequest.shouldStart(
            action = intent?.action,
            newIntent = newIntent,
            recreated = recreated,
            fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
            walkthroughShowing = !app.settings.current.value.hasCompletedOnboarding,
            crashGuardAllows = app.captions.mayStartCaptions(),
            firstAppearancePending = app.captions.firstAppearancePending(),
        )
        if (!start) return
        app.captions.takeFirstAppearance()
        showingSettings.value = false
        settingsAtBackup.value = false
        captionsAsked.value = true
        startListening()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SHOWING_SETTINGS, showingSettings.value)
        outState.putBoolean(CAPTIONS_ASKED, captionsAsked.value)
        outState.putBoolean(SETTINGS_AT_BACKUP, settingsAtBackup.value)
        outState.putBoolean(CONFIRMING_CELLULAR, confirmingCellular.value)
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
        takeSharedRecording(intent)
        startFromRequest(intent, newIntent = true, recreated = false)
    }

    /** A voice note, recording or video shared to Ozen from another app is offered for transcribing. */
    private fun takeSharedRecording(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val type = intent.type ?: return false
        if (!type.startsWith("audio/") && !type.startsWith("video/")) return false
        val recording = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return false
        if (!SharedRecordingLink.isAcceptable(recording.toString(), packageName)) return false
        return (application as OzenApplication).transcriber.open(recording)
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
            PhasePresentation.Action.OpenSystemSettings -> when (MicrophoneAsk.next(hasMicrophone(), canAskMicrophoneAgain())) {
                MicrophoneAsk.Granted, MicrophoneAsk.AskAgain -> startListening()
                MicrophoneAsk.OpenSettings -> openAppSettings()
            }
            PhasePresentation.Action.Start, PhasePresentation.Action.Resume, PhasePresentation.Action.Retry -> startListening()
            PhasePresentation.Action.OpenEngineSettings -> {
                settingsAtBackup.value = false
                showingSettings.value = true
            }
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
        canAskMicrophoneAgain = { canAskMicrophoneAgain() },
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

    private fun startSoundsOnly() {
        if (hasMicrophone()) {
            ListeningService.startSoundsOnly(this)
            askForNotificationsIfNeverAnswered()
            return
        }
        askForMicrophoneForSoundsOnly.launch(ListeningPermissions.wanted(Build.VERSION.SDK_INT).toTypedArray())
    }

    private fun startListening() {
        if (hasMicrophone()) {
            ListeningService.start(this)
            askForNotificationsIfNeverAnswered()
            return
        }
        askForMicrophone.launch(ListeningPermissions.wanted(Build.VERSION.SDK_INT).toTypedArray())
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
        const val CAPTIONS_ASKED = "captionsAsked"
        const val SETTINGS_AT_BACKUP = "settingsAtBackup"
        const val CONFIRMING_CELLULAR = "confirmingCellular"
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
    val canAskMicrophoneAgain: () -> Boolean,
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
    captionsAsked: MutableState<Boolean>,
    onboarding: OnboardingHooks,
    onStatusTap: () -> Unit,
    onLaunch: () -> Unit,
    onSoundsOnly: () -> Unit,
    onAppLanguage: (AppLanguage) -> Unit,
    onLightScreen: (Boolean) -> Unit,
) {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val state by CaptionState.screen.collectAsStateWithLifecycle()
    val current by app.settings.current.collectAsStateWithLifecycle()
    val voices by app.captions.voice.installed.collectAsStateWithLifecycle()
    val recording by app.transcriber.state.collectAsStateWithLifecycle()
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<Pair<UUID, UUID?>?>(null) }
    var saying by rememberSaveable { mutableStateOf(false) }
    var choosingMicrophone by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Cancelled changes nothing: the microphone stays.
    val sharingAnswer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answer ->
        val consent = answer.data
        if (answer.resultCode == Activity.RESULT_OK && consent != null) ListeningService.startPhoneSound(context, answer.resultCode, consent)
    }
    var namingAlert by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(captionsAsked.value) {
        if (!captionsAsked.value) return@LaunchedEffect
        showingHistory = false
        reading = null
        saying = false
        choosingMicrophone = false
        namingAlert = false
        captionsAsked.value = false
    }
    val modelSwitch by app.captions.phoneModels.switching.collectAsStateWithLifecycle()
    var comebacks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { comebacks++ }
    val fitOffer = remember(current, comebacks) { app.captions.modelFitOffer() }
    val inUseModelBytes = remember(current, comebacks) { app.captions.phoneModels.inUseBytes() }
    val loadCrash by app.captions.loadCrash.showing.collectAsStateWithLifecycle()
    val listeningCut by app.captions.listeningCut.collectAsStateWithLifecycle()
    // Any start, from the card or the status line, ends the question, even
    // one that waits for Wi-Fi or fails before the captions run.
    val captionsStarted = state.phase != PipelinePhase.Idle
    LaunchedEffect(captionsStarted) { if (captionsStarted) app.captions.dismissLoadCrash() }
    // The lighter model offered after a crash is in use, whether it was on
    // the phone already or has just finished downloading: the captions the
    // crash held back start on it.
    val crashSwitchDone = loadCrash?.file?.takeIf { it == current.phoneModel.file }
    LaunchedEffect(crashSwitchDone) {
        if (crashSwitchDone != null) {
            app.captions.dismissLoadCrash()
            onLaunch()
        }
    }
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
            recording is FileTranscription.Working ||
            state.keepsScreenOn(current.display.keepScreenAwake, awakeClock),
    )
    val moreContrast = phoneAsksForMoreContrast()
    val look = CaptionTheme.of(current.display.theme, isSystemInDarkTheme(), moreContrast)
    LaunchedEffect(look.isLight) { onLightScreen(look.isLight) }
    OzenTheme(dark = !look.isLight, highContrast = moreContrast || current.display.theme == DisplayPreferences.Theme.HighContrast) {
        CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDecimalComma provides usesDecimalComma(current.spokenLanguage)) {
            val front = when {
                !current.hasCompletedOnboarding -> FrontScreen(Front.Walkthrough)
                showingSettings.value -> FrontScreen(Front.Settings)
                recording !is FileTranscription.Idle -> FrontScreen(Front.Transcribe)
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
                                microphoneCanAskAgain = onboarding.canAskMicrophoneAgain(),
                                onApproveMicrophone = onboarding.approveMicrophone,
                                onApproveNotifications = onboarding.approveNotifications,
                                onOpenPhoneSettings = onboarding.openPhoneSettings,
                                onSpokenLanguage = app.engineSettings::chooseSpokenLanguage,
                                phoneModels = app.captions.phoneModels,
                                onAddName = app.captions::addNameAlert,
                                onAddAlert = app.captions::addAlert,
                                onFinish = onboarding.finish,
                            )
                        }
                        Front.Settings -> SettingsScreen(app.engineSettings, app.settings, onClose = { showingSettings.value = false; settingsAtBackup.value = false }, onWords = { app.captions.setWords(it) }, onWordFixes = { app.captions.setWordFixes(it) }, onImportantWords = { app.captions.setImportantWords(it) }, canSay = app.captions::canSay, onSpeak = { app.captions.speak(it) }, diagnostics = app.captions, heard = state.recentlyHeard, heardBy = { state.recentlyHeardBy[it.id]?.takeIf { current.display.showSpeakerNames } }, onAppLanguage = onAppLanguage, onShowWalkthrough = {
                            app.captions.showWalkthroughAgain()
                            showingSettings.value = false
                            settingsAtBackup.value = false
                        }, onClearCaptions = app.captions::clearCaptions, historySaveFailing = { app.captions.savedConversations.lastSaveFailure != null }, voiceNotice = VoiceNotice.forSettings(Localization.language, voices), onSoundAlerts = app.captions::setSoundAlerts, onSoundsOnly = { showingSettings.value = false; settingsAtBackup.value = false; onSoundsOnly() }, soundNearMiss = app.captions::soundNearMiss, onFeel = app.captions::buzz, onTryLight = app.captions::tryCameraLight, speakers = app.captions, onCellularDownloads = app.captions::allowCellularModelDownload, backup = app.captions, scrollToBackup = settingsAtBackup.value, onScrolledToBackup = { settingsAtBackup.value = false }, phoneModels = app.captions.phoneModels)
                        Front.Transcribe -> TranscribeScreen(app.transcriber, recording, onClose = app.transcriber::close)
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
                            selected = state.inputInUse?.takeIf { state.phase.isListening } ?: state.selectedInput,
                            listening = state.phase.isListening,
                            phoneSoundStopped = state.phoneSoundStopped,
                            level = { app.captions.pipeline.inputLevel },
                            onChoose = {
                                if (!PhoneSound.isPhoneSound(it.uid)) {
                                    app.captions.selectInput(it.uid)
                                } else {
                                    if (state.selectedInput?.uid != it.uid) sharingAnswer.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                                    true
                                }
                            },
                            onRefresh = app.captions::refreshInputs,
                            onClose = { choosingMicrophone = false },
                            routedElsewhere = state.routedElsewhere?.takeIf { state.captionsRunning },
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
                            onTranscribe = { app.transcriber.open(it) },
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
                                languageCode = current.languageCode,
                                onStar = { app.captions.toggleStar(it) },
                                onOpen = { conversation, line ->
                                    app.captions.saveBeforeReading()
                                    reading = conversation to line
                                },
                                onFixWord = { line, heard, written, everyTime ->
                                    app.captions.fixLine(line, heard, written).also { fixed ->
                                        if (fixed) app.captions.saveWordFix(heard, written, everyTime)
                                    }
                                },
                                onNameSpeaker = app.captions::nameSpeaker,
                                savedPersonRenamed = app.captions::savedPersonRenamed,
                                savedSpeakerNames = { app.captions.savedSpeakerNames },
                                onAskToRepeat = { app.captions.askToRepeat() }.takeIf { app.captions.canAskToRepeat() },
                                onSay = { if (state.speaking) app.captions.stopSpeaking() else saying = true },
                                onDismissSavingTrouble = app.captions::dismissSavingTrouble,
                                onDismissMicrophoneDrop = app.captions::dismissMicrophoneDrop,
                                onDismissRoutedElsewhere = app.captions::dismissRoutedElsewhere,
                                onDismissPhoneSoundStopped = app.captions::dismissPhoneSoundStopped,
                                onMarkProblem = app.captions::markProblem,
                                onSendReport = { markedAt ->
                                    val journal = withContext(Dispatchers.IO) { app.captions.readJournal() }
                                    helperReport(ProblemNotes.reportLine(markedAt), app.captions.diagnostics(journal))
                                },
                                savesConversations = current.saveHistory,
                                onMicrophone = { choosingMicrophone = true },
                                offersNameAlert = current.offersNameAlert,
                                onNameAlert = { namingAlert = true },
                                onDismissNameAlert = app.captions::dismissNameAlertOffer,
                                modelOffer = fitOffer?.takeIf { it.file != modelSwitch.downloadingFile },
                                modelOfferWaiting = modelSwitch.let { it is PhoneModelSwitch.WaitingForWiFi && it.file == fitOffer?.file },
                                inUseModelBytes = inUseModelBytes,
                                onSwitchModel = { app.captions.phoneModels.replaceWith(it.file) },
                                onDismissModelOffer = app.captions::dismissModelFitOffer,
                                loadCrashCard = loadCrash?.let { lighter ->
                                    {
                                        ModelLoadCrashCard(
                                            current.phoneModel,
                                            lighter,
                                            inUseModelBytes,
                                            switching = modelSwitch.takeIf { it.fileOrNull == lighter.file },
                                            onSwitch = { app.captions.phoneModels.replaceWith(lighter.file) },
                                            onRetry = {
                                                app.captions.dismissLoadCrash()
                                                onLaunch()
                                            },
                                        )
                                    }
                                },
                                listeningCutCard = listeningCut?.let { cut -> { ListeningCutCard(cut.text(), app.captions::dismissListeningCut) } },
                                // Not while it slides away under another screen: talk
                                // that resumes then is heard there, not buzzed here.
                                buzzWhenTalkResumes = current.hapticOnSpeechResume && transition.targetState == EnterExitState.Visible,
                                onBuzz = app.captions::buzz,
                                soundSecondsLeft = app.captions::soundBannerSecondsLeft,
                                onDismissSound = app.captions::dismissSoundAlert,
                                onDismissMissedSound = app.captions::dismissMissedSound,
                                onFontSize = { size -> app.settings.change { it.display = it.display.copy(fontSize = size) } },
                                onOpenConversation = { reading = it to null },
                                onDismissRecent = app.captions::dismissRecentConversation,
                                onDismissBattery = app.captions::dismissBatteryWarning,
                                onClear = app.captions::clearCaptions,
                                clearQuestion = { HistoryTexts.clearQuestion(current.saveHistory, app.captions.savedConversations.lastSaveFailure != null) },
                            )
                        }
                    }
                }
            }
            val asksForCellular = state.status.action == PhasePresentation.Action.ConfirmCellularDownload
            LaunchedEffect(asksForCellular) { if (!asksForCellular) confirmingCellular.value = false }
            if (confirmingCellular.value && asksForCellular) {
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
private enum class Front(val depth: Int) { Walkthrough(0), Captions(0), History(1), Say(1), Microphones(1), NameAlert(1), Settings(1), Transcribe(2), Conversation(2) }

/** The screen in front, with the conversation open on it, so one sliding away still shows what it showed. */
private data class FrontScreen(val kind: Front, val reading: Pair<UUID, UUID?>? = null)

/**
 * What the question before a download over mobile data says, kept apart so
 * a test can read it. It opens with what waiting costs: no captions at all
 * until the model is on the phone. Any metered network counts as mobile
 * data, a phone's hotspot or a Wi-Fi marked as limited too, so the promise
 * to start on its own names the Wi-Fi that keeps it.
 */
internal object CellularDownloadTexts {
    val title: String get() = tr("להוריד את מודל הדיבור בחבילת הגלישה?", "Download the language model over mobile data?")

    fun body(megabytes: Int?): String {
        val size = megabytes?.takeIf { it > 0 }?.let { ByteSize.megabytes(it) } ?: tr("כמה מאות MB", "A few hundred MB")
        return tr(
            "בלי מודל הדיבור אין כתוביות. צריך להוריד אותו פעם אחת בלבד, והגודל שלו בערך %1. הורדה בחבילת הגלישה עלולה לעלות כסף או לנצל את נפח הגלישה. ב־Wi‑Fi בלי הגבלת נפח (לא נקודה חמה של טלפון) ההורדה תתחיל מעצמה.",
            "Captions need the language model. It downloads once and is about %1. Over mobile data this can cost money or use up your data plan. On Wi‑Fi without a data limit (not a phone hotspot) the download will start on its own.",
            listOf(size),
        )
    }
}

/** As on the iPhone: "Tap to download now" on the waiting-for-Wi-Fi status asks first, saying what cellular data may cost. */
@Composable
private fun CellularDownloadDialog(megabytes: Int?, onDownload: () -> Unit, onWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text(CellularDownloadTexts.title) },
        text = { DialogText(CellularDownloadTexts.body(megabytes)) },
        confirmButton = { TextButton(onClick = onDownload) { Text(tr("להוריד עכשיו", "Download now")) } },
        dismissButton = { TextButton(onClick = onWait) { Text(tr("לחכות ל־Wi‑Fi", "Wait for Wi‑Fi")) } },
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
    val paired by pairing.paired.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    pending?.let { shown ->
        AlertDialog(
            onDismissRequest = { pairing.pending.value = null },
            title = { Text(tr("להתחבר למחשב בבית?", "Connect to the home computer?")) },
            text = {
                DialogText(
                    tr(
                        "כדי ליצור כתוביות, הקול יישלח למחשב שאת הקוד שלו סרקתם עכשיו (%1, כמו שכתוב בדף שלו). התחברו רק אם זה מחשב שלכם או של המשפחה.",
                        "For captions, the audio will go to the computer whose code you just scanned (%1, as shown on its page). Only connect if it’s your own or your family’s computer.",
                        listOf(shown.computerName),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { scope.launch { pairing.accept(shown) } }) { Text(tr("להתחבר", "Connect")) } },
            dismissButton = { TextButton(onClick = { pairing.pending.value = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { pairing.saveFailed.value = false },
            title = { Text(tr("החיבור למחשב בבית לא נשמר", "The connection to the home computer wasn’t saved")) },
            text = {
                DialogText(
                    tr(
                        "הכתוביות ממשיכות כמו קודם. סרקו שוב את הקוד, או בקשו עזרה ממי שהגדיר את הטלפון.",
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
                DialogText(
                    tr(
                        "חלק מהקישור חסר או משובש. סרקו שוב מקרוב את הריבוע שעל מסך המחשב, במצלמה של הטלפון.",
                        "Part of the link is missing or garbled. Scan the square on the computer again with the phone’s camera, up close.",
                    ) + "\n\n" + tr(
                        "אפשר גם להקליד את הכתובת ואת הקוד שמופיעים מתחת לריבוע: בהגדרות, בוחרים ״המחשב בבית״ תחת ״מנוע תמלול״ וממלאים את שתי התיבות.",
                        "You can also type in the address and code shown under the square: in Settings, choose “Home computer” under “Transcription engine” and fill in both boxes.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.linkBroken.value = false }) { Text(tr("סגירה", "Close")) } },
        )
    }
    // Connect only closes its question: without a word here, a pairing
    // that worked looks like nothing happened, to TalkBack too. A newer
    // link's question goes first, so this can't cover it.
    if (paired && pending == null) {
        AlertDialog(
            onDismissRequest = { pairing.paired.value = false },
            text = { DialogText(tr("התחברתם בעזרת הקוד שסרקתם. הכתוביות מגיעות מהמחשב בבית.", "Connected with the code you scanned. Captions come from the computer at home.")) },
            confirmButton = { TextButton(onClick = { pairing.paired.value = false }) { Text(tr("סגירה", "Close")) } },
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
    languageCode: String,
    onStar: (UUID) -> Unit,
    onOpen: (conversation: UUID, line: UUID) -> Unit,
    onFixWord: (UUID, String, String, Boolean) -> Boolean,
    onNameSpeaker: (line: UUID, name: String, onlyThisLine: Boolean) -> Boolean,
    savedPersonRenamed: (line: UUID, name: String) -> String?,
    savedSpeakerNames: () -> List<String>,
    onAskToRepeat: (() -> Unit)?,
    onSay: () -> Unit,
    onDismissSavingTrouble: () -> Unit,
    onDismissMicrophoneDrop: () -> Unit,
    onDismissRoutedElsewhere: () -> Unit = {},
    onDismissPhoneSoundStopped: () -> Unit,
    onMarkProblem: (heldLine: UUID?) -> Boolean,
    onSendReport: suspend (markedAt: Double) -> String,
    savesConversations: Boolean,
    onMicrophone: () -> Unit,
    offersNameAlert: Boolean,
    onNameAlert: () -> Unit,
    onDismissNameAlert: () -> Unit,
    modelOffer: PhoneModel?,
    modelOfferWaiting: Boolean,
    inUseModelBytes: Long?,
    onSwitchModel: (PhoneModel) -> Unit,
    onDismissModelOffer: (PhoneModel) -> Unit,
    loadCrashCard: (@Composable () -> Unit)?,
    listeningCutCard: (@Composable () -> Unit)?,
    buzzWhenTalkResumes: Boolean,
    onBuzz: (AlertVibration) -> Unit,
    soundSecondsLeft: (SoundAlert) -> Double,
    onDismissSound: (UUID) -> Unit,
    onDismissMissedSound: () -> Unit = {},
    onFontSize: (Double) -> Unit,
    onOpenConversation: (UUID) -> Unit,
    onDismissRecent: () -> Unit,
    onDismissBattery: () -> Unit,
    onClear: (keepingHistory: Boolean) -> Boolean,
    clearQuestion: () -> ClearQuestion,
) {
    val list = rememberLazyListState()
    // As on the iPhone: the text grows under the fingers and only the
    // final size is saved.
    var pinchScale by remember { mutableDoubleStateOf(1.0) }
    val pinchedSize = DisplayPreferences.fontSize(display.fontSize, pinchScale)
    // One object until the settings or the pinch change: the settings come
    // from the shared module, which Compose can't see into, so it compares
    // them by identity, and a fresh copy at every word or clock tick drew
    // every line on screen again instead of only the one that changed.
    val linesDisplay = remember(display, pinchedSize) { display.copy(fontSize = pinchedSize) }
    val savedSize by rememberUpdatedState(display.fontSize)
    var problemMarked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var markedWithCaptions by rememberSaveable { mutableStateOf(true) }
    var problemMarkedAt by rememberSaveable { mutableDoubleStateOf(0.0) }
    val context = LocalContext.current
    var shownWord by remember { mutableStateOf<HeardWord?>(null) }
    // Counted from when this screen appeared: talk that resumed while she
    // was in Settings or away is long over by the time she is back.
    val resumedBefore = remember { state.speechResumed }
    LaunchedEffect(state.speechResumed) {
        if (state.speechResumed > resumedBefore && buzzWhenTalkResumes) onBuzz(AlertVibration.speechResumed)
    }
    // With TalkBack on, the pill and the banner stay until closed or
    // replaced: hidden on their timer, they left someone who missed the
    // spoken alert, or reads by braille, no way to find which word or
    // sound it was. As on the iPhone.
    val talkBack = screenReaderOn()
    // The session buzzes and tells TalkBack, whichever screen is open; the
    // pill is for a word said just now, or one the session tells her of as
    // she comes back. Back from Settings, an old one does not show again.
    LaunchedEffect(state.heard?.id, state.heard?.at, talkBack) {
        val word = state.heard ?: return@LaunchedEffect
        val kept = shownWord?.id == word.id
        if (secondsNow() - word.at > HEARD_SHOWN_SECONDS) {
            if (kept && !talkBack) shownWord = null
            if (!kept) return@LaunchedEffect
        } else {
            shownWord = word
        }
        if (talkBack) return@LaunchedEffect
        delay((HEARD_SHOWN_SECONDS * 1_000).toLong())
        if (shownWord?.id == word.id) shownWord = null
    }
    // The banner keeps what is left of its time: back from Settings within
    // it, the banner is there again, and one whose time is up is not.
    var shownSound by remember { mutableStateOf<SoundAlert?>(null) }
    LaunchedEffect(state.sound?.id, talkBack) {
        val alert = state.sound?.takeIf { soundSecondsLeft(it) > 0 || (talkBack && shownSound?.id == it.id) }
        shownSound = alert
        if (alert == null || talkBack) return@LaunchedEffect
        delay((soundSecondsLeft(alert) * 1_000).toLong())
        shownSound = null
    }
    // Kept with the line's text, as the iPhone's sheet keeps its line:
    // turning the phone leaves the dialog open, and so does the line
    // leaving the newest 200 the screen holds while she types.
    var fixing by rememberSaveable { mutableStateOf<String?>(null) }
    var fixingLine by rememberSaveable { mutableStateOf<UUID?>(null) }
    var fixRefused by rememberSaveable { mutableStateOf(false) }
    fixing?.let { text ->
        FixWordDialog(
            text,
            languageCode = languageCode,
            error = tr("השורה כבר לא על המסך, ולכן התיקון לא נשמר.", "The line is no longer on the screen, so the fix was not saved.").takeIf { fixRefused },
            onSave = { heard, written, everyTime ->
                if (fixingLine?.let { onFixWord(it, heard, written, everyTime) } == true) {
                    fixing = null
                    fixRefused = false
                } else {
                    fixRefused = true
                }
            },
            onDismiss = {
                fixing = null
                fixRefused = false
            },
        )
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
            renames = { name -> savedPersonRenamed(id, name) },
            onSave = { name, onlyThisLine -> onNameSpeaker(id, name, onlyThisLine).also { saved -> if (saved) naming = null } },
            onDismiss = { naming = null },
        )
    }
    problemMarked?.let { keptSound ->
        ProblemMarkedDialog(keptSound, markedWithCaptions, report = { onSendReport(problemMarkedAt) }, onDismiss = { problemMarked = null })
    }
    var following by rememberSaveable { mutableStateOf(true) }
    var countWhenLeft by rememberSaveable { mutableIntStateOf(0) }
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
    // TalkBack, a braille display or a keyboard move the list through its
    // accessibility actions, which never pass through the connection above:
    // reading back an earlier line that way was undone by the next word. Any
    // move the screen's own following didn't make counts as hers.
    val marks = remember(list) { FollowMarks() }
    LaunchedEffect(list) {
        snapshotFlow { list.place() }
            .drop(1)
            .collect { at ->
                if (marks.scrolling > 0 || at == marks.at) return@collect
                val atEnd = !list.canScrollForward
                if (following && !atEnd) countWhenLeft = lineCount
                following = atEnd
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
        screenReaderOn = talkBack,
        pointerAccessOn = pointerAccessOn(),
        lastTouchAt = lastTouchAt,
        now = clock,
    )
    val (flashColour, flashStrength) = alertFlash(state.heardSound)
    val offer = modelOffer?.takeIf { state.allowsModelOffer }
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
            .padding(horizontal = OzenSpacing.Page, vertical = OzenSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(OzenSpacing.Page),
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = OzenSpacing.Unit)) {
            // A few banners at once, at large text on a small phone, took
            // all the room and left no line of the captions: past about
            // half the room they scroll.
            val bannerRoom = maxHeight * 0.5f
            val bannerRoomPixels = constraints.maxHeight * 0.5f
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
                    // The same sound once: as heard while away, with its
                    // time. An older banner, kept up by TalkBack, stays
                    // beside it instead of hiding it, as on the iPhone.
                    ArrivingBanner(shownSound?.takeIf { it.id != state.missedSound?.alert?.id }) { alert ->
                        SoundAlertBanner(alert) {
                            shownSound = null
                            onDismissSound(alert.id)
                        }
                    }
                    ArrivingBanner(state.missedSound) { missed ->
                        MissedSoundBanner(missed) {
                            // Closing it closes that sound: its plain banner,
                            // kept behind it, came back without the time.
                            if (shownSound?.id == missed.alert.id) {
                                shownSound = null
                                onDismissSound(missed.alert.id)
                            }
                            onDismissMissedSound()
                        }
                    }
                    ArrivingBanner(shownWord) { word ->
                        val speaker = state.lines.firstOrNull { it.id == word.line }?.speaker?.takeIf { display.showSpeakerNames }
                        HeardPill(
                            word.said,
                            speaker,
                            if (talkBack) Modifier.clickable(onClickLabel = tr("סגירה", "Close")) { shownWord = null } else Modifier,
                        )
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
                    ArrivingBanner(state.routedElsewhere?.takeIf { state.captionsRunning }) { routed ->
                        MicrophoneDropBanner(
                            title = routed.title,
                            detail = routed.detail,
                            onChooseMicrophone = {
                                onDismissRoutedElsewhere()
                                onMicrophone()
                            },
                            onDismiss = onDismissRoutedElsewhere,
                        )
                    }
                    ArrivingBanner(Unit.takeIf { state.phoneSoundStopped }) { PhoneSoundStoppedBanner(onDismissPhoneSoundStopped) }
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
                        // or turned sideways. Centred in the free room when it fits.
                        val emptyScroll = rememberScrollState()
                        Column(
                            modifier = Modifier.fillMaxSize().fadeWhenMoreBelow(emptyScroll).verticalScroll(emptyScroll),
                            verticalArrangement = Arrangement.spacedBy(OzenSpacing.Page, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Not while a call holds the microphone: the phase stays
                            // on listening, but nothing is being heard.
                            val running = state.captionsRunning
                            IconBadge(if (running) Icons.Rounded.Hearing else Icons.Rounded.MicOff, size = 72.dp)
                            Text(
                                text = if (running) tr("מאזינה.", "Listening.") else tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                                color = look.text,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (running) {
                                    tr(
                                        "כשמישהו ידבר, המילים יופיעו כאן בזמן אמת. לחיצה ארוכה על שורה מאפשרת לסמן אותה כחשובה או לתת שם לדובר, וצביטה בשתי אצבעות מגדילה או מקטינה את הטקסט.",
                                        "When someone talks, the words will appear here in real time. Press and hold a line to mark it as important or name the speaker, and pinch with two fingers to make the text bigger or smaller.",
                                    )
                                } else if (state.phase.modelGettingReady()) {
                                    // A first model can take minutes to download; the
                                    // hint about the microphone read as "ready, nobody
                                    // talking yet" while the status below said otherwise.
                                    tr("מודל הדיבור בהכנה. ההתקדמות מופיעה למטה, והכתוביות יתחילו מעצמן.", "The speech model is getting ready. The progress is shown below, and captions start on their own.")
                                } else {
                                    tr("אפשר לבחור מיקרופון בכפתור המיקרופון שבתחתית המסך, ולהחליף את מנוע התמלול בהגדרות.", "You can choose a microphone with the microphone button at the bottom, and change the transcription engine in Settings.")
                                },
                                color = look.pendingText,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 360.dp),
                            )
                            val recent = state.recentConversation
                            loadCrashCard?.invoke()
                            listeningCutCard?.invoke()
                            if (offer != null) LighterModelOffer(offer, modelOfferWaiting, inUseModelBytes, { onSwitchModel(offer) }, { onDismissModelOffer(offer) })
                            if (recent != null) {
                                RecentConversationCard(recent, look, onOpen = { onOpenConversation(recent.id) }, onDismiss = onDismissRecent)
                            } else if (offer == null && offersNameAlert) {
                                NameAlertOffer(look, onNameAlert, onDismissNameAlert)
                            }
                        }
                    } else {
                        // Captions start by themselves at launch, so after the
                        // system ended the app mid-conversation the card went
                        // with the empty screen at the first new line, before
                        // it was read. Above the first line it stays until its
                        // X, until it is opened or the captions are cleared, as
                        // on the iPhone.
                        val recent = state.recentConversation
                        val leading: (@Composable () -> Unit)? = recent?.let {
                            { RecentConversationCard(recent, look, onOpen = { onOpenConversation(recent.id) }, onDismiss = onDismissRecent) }
                        }
                        val trailing: (@Composable () -> Unit)? = if (offer == null && loadCrashCard == null && listeningCutCard == null) null else {
                            {
                                loadCrashCard?.invoke()
                                listeningCutCard?.invoke()
                                if (offer != null) LighterModelOffer(offer, modelOfferWaiting, inUseModelBytes, { onSwitchModel(offer) }, { onDismissModelOffer(offer) })
                            }
                        }
                        CaptionLines(state.lines, state.away, state.earlier, linesDisplay, look, list, following, marks, Modifier.nestedScroll(rereading), trailing, onStar, onOpen, onFix = { fixing = it.text; fixingLine = it.id; fixRefused = false }, onName = nameSpeakerOf, onAskToRepeat = onAskToRepeat, onMarkProblem = { line -> markedWithCaptions = savesConversations; problemMarkedAt = secondsNow(); problemMarked = onMarkProblem(line) }, leading = leading)
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
        var askingToClear by rememberSaveable { mutableStateOf(false) }
        var clearTries by remember { mutableIntStateOf(0) }
        AnimatedVisibility(visible = !hidden) {
            val microphone = state.selectedInput?.let(::inputShortName)?.takeIf { it.length <= 12 } ?: tr("מיקרופון", "Microphone")
            val choose = tr("בחירת מיקרופון", "Choose microphone")
            val say = if (state.speaking) tr("עצירת הדיבור", "Stop speaking") else tr("להגיד משהו בקול", "Say something out loud")
            // The labels grow with the phone's text size only so far: past that
            // the five would break a few letters to a line.
            val density = LocalDensity.current
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    StatusLine(state.status, look, live = state.captionsRunning, onTap = onStatusTap)
                    CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, TOOLBAR_FONT_SCALE))) {
                        Row(Modifier.padding(horizontal = OzenSpacing.Unit / 2).padding(bottom = 4.dp)) {
                            ToolbarButton(Icons.Rounded.History, tr("היסטוריה", "History"), onHistory, Modifier.weight(toolbarShare(tr("היסטוריה", "History"))))
                            ToolbarButton(
                                Icons.Rounded.Mic,
                                microphone,
                                onMicrophone,
                                Modifier.weight(toolbarShare(microphone)).semantics {
                                    contentDescription = choose
                                    stateDescription = state.selectedInput?.portName ?: ""
                                },
                            )
                            // While the phone talks, this stops it at once instead of
                            // opening the Say screen, as on the iPhone: captions stay
                            // paused for as long as it talks.
                            ToolbarButton(
                                if (state.speaking) Icons.Rounded.Stop else Icons.Rounded.Keyboard,
                                tr("להגיד", "Say"),
                                onSay,
                                Modifier.weight(toolbarShare(tr("להגיד", "Say"))).semantics { contentDescription = say },
                                selected = state.speaking,
                            )
                            ToolbarButton(Icons.Rounded.DeleteSweep, tr("ניקוי", "Clear"), { askingToClear = true }, Modifier.weight(toolbarShare(tr("ניקוי", "Clear"))), enabled = state.lines.isNotEmpty())
                            ToolbarButton(Icons.Rounded.Settings, tr("הגדרות", "Settings"), onSettings, Modifier.weight(toolbarShare(tr("הגדרות", "Settings"))))
                        }
                    }
                }
            }
        }
        if (askingToClear) {
            val question = remember(clearTries) { clearQuestion() }
            AlertDialog(
                onDismissRequest = { askingToClear = false },
                title = { Text(tr("למחוק את כל הכתוביות מהמסך?", "Delete all captions from the screen?")) },
                text = { Text(question.text) },
                confirmButton = {
                    // A save that fails now asks again, saying the lines won't be kept.
                    TextButton(
                        onClick = {
                            askingToClear = !onClear(question.keepsHistory)
                            if (askingToClear) clearTries += 1
                        },
                    ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { askingToClear = false }) { Text(tr("ביטול", "Cancel")) } },
            )
        }
        if (hidden) ShowButtonsButton { lastTouchAt = secondsNow() }
    }
}

/** Room on the bottom toolbar in proportion to the label's length, so a long word stays whole beside short ones. */
private fun toolbarShare(label: String): Float = label.length.toFloat().coerceIn(7f, 12f)

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

private const val TOOLBAR_FONT_SCALE = 1.3f

@Composable
private fun screenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = context.getSystemService(AccessibilityManager::class.java)
    var on by remember(manager) { mutableStateOf(ScreenReader.isOn(context)) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = ScreenReader.isOn(context) }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * Switch Access, Voice Access and Samsung's Universal Switch press buttons
 * through accessibility actions, which never reach the touch that keeps the
 * buttons up, so they keep the buttons on screen as TalkBack does (and as
 * Switch Control does on the iPhone). Read again on each return to the app,
 * where such a service is switched on.
 */
@Composable
private fun pointerAccessOn(): Boolean {
    val context = LocalContext.current
    val manager = context.getSystemService(AccessibilityManager::class.java)
    var on by remember(manager) { mutableStateOf(ScreenReader.pointsWithoutTouch(ScreenReader.enabledServiceIds(context))) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { on = ScreenReader.pointsWithoutTouch(ScreenReader.enabledServiceIds(context)) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on = ScreenReader.pointsWithoutTouch(ScreenReader.enabledServiceIds(context)) }
        manager.addAccessibilityStateChangeListener(listener)
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return on
}

/**
 * The one control left while the buttons are away, as on the iPhone: at
 * least the 44-point target a shaky finger needs. A touch anywhere on the
 * screen brings the buttons back too.
 */
@Composable
private fun ShowButtonsButton(onTap: () -> Unit) {
    val label = tr("הצגת הכפתורים", "Show the buttons")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onTap,
            modifier = Modifier.size(width = 64.dp, height = 48.dp).semantics { contentDescription = label },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    marks: FollowMarks,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)?,
    onStar: (UUID) -> Unit,
    onOpen: (conversation: UUID, line: UUID) -> Unit,
    onFix: (CaptionLine) -> Unit,
    onName: (CaptionLine) -> Unit,
    onAskToRepeat: (() -> Unit)?,
    onMarkProblem: (line: UUID) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val lastIndex = if (trailing != null) lines.size else lines.lastIndex
    // The saved-conversation card can arrive after the lines (it is looked
    // up while captions start), and above them it pushes the newest down.
    LaunchedEffect(lines.size, lines.lastOrNull()?.text, following, display.fontSize, trailing != null, leading != null) {
        if (following) list.follow(lastIndex, marks)
    }
    // The buttons coming back, or a turn of the phone, change the room the
    // lines have; the list keeps its top, so the newest line was left under
    // the buttons until the next word, a long time in a quiet room. The
    // iPhone follows again on both.
    LaunchedEffect(list, following) {
        if (!following) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { if (list.layoutInfo.totalItemsCount > 0) list.follow(list.layoutInfo.totalItemsCount - 1, marks) }
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
                    onMarkProblem = { onMarkProblem(line.id) },
                )
            }
            val gap = line.afterQuiet
            val first = line.id == lines.first().id
            val card = leading?.takeIf { first }
            val note = earlier?.takeIf { first }
            val missed = away?.takeIf { line.id == it.line }
            // A new line fades in where it lands; the lines already there
            // stay still, as moving them would move what she is reading.
            Box(Modifier.animateItem(fadeInSpec = tween(250), placementSpec = null, fadeOutSpec = null)) {
                if (card != null || note != null || missed != null || gap != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        card?.invoke()
                        if (note != null) EarlierLinesNote(note, display.fontSize, look) { conversation -> onOpen(conversation, note.line) }
                        if (missed != null) AwayDivider(missed.count, display.fontSize, look) else if (gap != null) QuietGapDivider(gap, display.fontSize, look)
                        row()
                    }
                } else {
                    row()
                }
            }
        }
        if (trailing != null) item(key = "model-fit-offer") { trailing() }
    }
}

/** A speech model on the phone downloading, checking, loading or warming up; the home computer and the cloud only check support. */
private fun PipelinePhase.modelGettingReady(): Boolean {
    val stage = (this as? PipelinePhase.PreparingEngine)?.progress?.stage ?: return false
    return stage != EnginePreparationProgress.Stage.CheckingSupport && stage != EnginePreparationProgress.Stage.RequestingPermission
}

/** Where the screen's own following last left the list, and how many of its moves are under way. */
private class FollowMarks {
    var scrolling = 0
    var at: Pair<Any?, Int>? = null
}

/** The first line in sight and how far it is scrolled: lines added below or dropped above leave it alone. */
private fun LazyListState.place(): Pair<Any?, Int> = layoutInfo.visibleItemsInfo.firstOrNull()?.key to firstVisibleItemScrollOffset

private suspend fun LazyListState.follow(lastIndex: Int, marks: FollowMarks) {
    marks.scrolling++
    try {
        scrollToEnd(lastIndex)
    } finally {
        marks.at = place()
        marks.scrolling--
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
    val spoken = tr("מכאן, מה שנאמר כשהאפליקציה לא הייתה על המסך: %1", "From here, what was said while the app wasn’t on the screen: %1", listOf(lines))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("נאמר כשהאפליקציה לא הייתה על המסך · %1", "Said while the app wasn’t on the screen · %1", listOf(lines)),
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
/** Told each time a caption line is composed; only device tests set it, to count the lines composed again per new word. */
@Volatile
internal var onCaptionRowComposed: ((UUID) -> Unit)? = null

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
    onCaptionRowComposed?.invoke(line.id)
    val doubtful = if (display.markUncertainLines) line.doubtfulWords else emptyList()
    val spoken = UncertainWords.spoken(line.text, doubtful)
    val colour = if (line.isFinal) look.text else look.pendingText
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val star = if (line.starred) tr("ביטול הסימון", "Remove mark") else tr("סימון כחשובה", "Mark as important")
    val copy = tr("העתקה", "Copy")
    val fix = tr("תיקון מילה", "Fix a word")
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
            layout = { layout },
            actions = listOf(
                CustomAccessibilityAction(star) { onStar(); true },
            ) + listOfNotNull(onAskToRepeat).map { say ->
                CustomAccessibilityAction(ask) { say(); true }
            } + listOf(
                CustomAccessibilityAction(who) { onName(); true },
            ) + listOf(fix).filter { line.isFinal }.map {
                CustomAccessibilityAction(it) { onFix(); true }
            } + listOf(
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
            if (line.isFinal) DropdownMenuItem(text = { Text(fix) }, onClick = { menu = false; onFix() })
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
    layout: () -> TextLayoutResult?,
    actions: List<CustomAccessibilityAction>,
) {
    val speaker = line.speaker?.takeIf { display.showSpeakerNames }
    CompositionLocalProvider(LocalLayoutDirection provides if (line.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (speaker != null && line.showsSpeaker) SpeakerLabel(speaker, line.speakerCluster, line.speakerSlot, display.fontSize, look)
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
private fun SpeakerLabel(name: String, cluster: Int?, slot: Int?, captionSize: Double, look: CaptionTheme) {
    val colour = SpeakerColour.of(cluster, name, look.isLight, slot)
    Row(
        modifier = Modifier.semantics { hideFromAccessibility() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(colour, CircleShape))
        Text(name, color = colour, fontSize = maxOf(15.0, captionSize * 0.5).sp, fontWeight = FontWeight.SemiBold)
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
    layout: () -> TextLayoutResult?,
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
                // Read only here, as it is drawn: read while composing, the
                // layout each new word gives composed the line a second time.
                .drawBehind { layout()?.let { drawDoubtDots(it, colour) } }
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
 * Where a problem marked from a caption line or from Diagnostics went.
 * Marked from a caption line, one large button sends the report to
 * whoever helps her through the phone's share sheet (built at the tap, its
 * journal read off the main thread); Diagnostics has its own Send report
 * first, so there the dialog only explains. A kept sound is sent from its
 * own row in Diagnostics.
 */
@Composable
internal fun ProblemMarkedDialog(keptSound: Boolean, keptCaptions: Boolean, report: (suspend () -> String)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    val send = {
        if (report != null && !building) {
            building = true
            scope.launch {
                try {
                    context.startActivity(reportSheet(report()))
                } finally {
                    building = false
                }
            }
        }
    }
    val hint = tr("פותח את אפשרויות השיתוף עם דוח האבחון", "Opens the share options with the diagnostics report")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("הבעיה סומנה", "Problem marked")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogText(ProblemNotes.marked(keptSound, keptCaptions), Modifier.weight(1f, fill = false))
                if (report != null) {
                    PrimaryButton(
                        tr("שליחה למי שעוזר לכם", "Send to whoever helps you"),
                        onClick = { send() },
                        icon = Icons.Rounded.Share,
                        enabled = !building,
                        modifier = Modifier.fillMaxWidth().semantics { onClick(label = hint) { send(); true } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("אישור", "OK")) } },
    )
}

/**
 * "Saving failed", as the iPhone's. Only the close button puts it away: a tap
 * on the screen is how the hidden buttons come back, and when the whole card
 * closed it, the warning was gone before the helper it names had seen it.
 */
@Composable
private fun SavingTroubleBanner(onDismiss: () -> Unit) {
    NoticeBanner(
        Icons.Rounded.Warning,
        NoticeTone.Warning,
        SavingTroubleNotice.title,
        SavingTroubleNotice.detail,
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) { BannerClose(tr("סגירה", "Close"), LocalContentColor.current, onDismiss) }
}

/** The system ended the sharing of the phone's sound; the microphone is back, and the close button puts this away. */
@Composable
private fun PhoneSoundStoppedBanner(onDismiss: () -> Unit) {
    NoticeBanner(
        Icons.Rounded.MicOff,
        NoticeTone.Warning,
        PhoneSound.stoppedNotice,
        "",
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) { BannerClose(tr("סגירה", "Close"), LocalContentColor.current, onDismiss) }
}

/** "Battery at 19%", as the iPhone's: deeper red when the phone may shut down, the close button puts it away. */
@Composable
private fun BatteryBanner(warning: BatteryWarning, onDismiss: () -> Unit) {
    val critical = warning is BatteryWarning.Critical
    NoticeBanner(
        if (critical) Icons.Rounded.BatteryAlert else Icons.Rounded.BatteryChargingFull,
        if (critical) NoticeTone.Emergency else NoticeTone.Warning,
        tr("הסוללה ב־%1%", "Battery at %1%", listOf("${warning.percent}")),
        if (critical) {
            tr("הטלפון עלול להיכבות באמצע השיחה. חברו אותו למטען.", "The phone might shut down mid-conversation. Plug in a charger.")
        } else {
            tr("כדאי לחבר את הטלפון למטען.", "Consider plugging in a charger.")
        },
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) { BannerClose(tr("סגירה", "Close"), LocalContentColor.current, onDismiss) }
}

/** As the iPhone's: a tap on the words opens the microphone picker, the close button only puts the notice away. */
@Composable
private fun MicrophoneDropBanner(title: String, detail: String, onChooseMicrophone: () -> Unit, onDismiss: () -> Unit) {
    NoticeBanner(
        Icons.Rounded.MicOff,
        NoticeTone.Warning,
        title,
        detail,
        Modifier
            .clickable(onClickLabel = tr("בחירת מיקרופון", "Choose microphone"), role = Role.Button, onClick = onChooseMicrophone)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) { BannerClose(tr("סגירה", "Close"), LocalContentColor.current, onDismiss) }
}

/** As the iPhone's card, for a phone whose walkthrough was skipped: the buzz for her name only works once the name is in. */
@Composable
private fun NameAlertOffer(look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 24.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh),
    ) {
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(start = OzenSpacing.Page, top = OzenSpacing.Page, bottom = OzenSpacing.Unit, end = OzenSpacing.Unit)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Text(tr("שהטלפון ירטוט כשקוראים לכם?", "Have the phone buzz when someone calls your name?"), color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                tr("הקישו כדי לכתוב את השם שלכם", "Tap to write your name"),
                color = colors.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.End).heightIn(min = OzenSpacing.MinTouch).wrapContentHeight(Alignment.CenterVertically),
            )
        }
        BannerClose(tr("לא עכשיו", "Not now"), colors.onSurfaceVariant, onDismiss)
    }
}

/** The running model kept falling behind the speech and a lighter one fits this phone; see `ModelFitWatch`. */
@Composable
private fun LighterModelOffer(model: PhoneModel, waiting: Boolean, inUseBytes: Long?, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 24.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh).testTag("model-fit-offer"),
    ) {
        Column(Modifier.weight(1f).padding(start = OzenSpacing.Page, top = OzenSpacing.Page, bottom = OzenSpacing.Unit, end = OzenSpacing.Unit)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("הכתוביות התעכבו בטלפון הזה", "Captions ran late on this phone"), color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("המודל ״\u2068%1\u2069״ (%2) קל יותר ועומד בקצב טוב יותר בטלפון הזה.", "%1 (%2) is lighter and keeps up better here.", listOf(model.displayName, ByteSize.text(model.bytes))),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ReplacedModelLine(inUseBytes)
            if (waiting) Text(
                tr("ממתינים ל־Wi‑Fi כדי להוריד. בהגדרות אפשר לאשר הורדה גם בחבילת הגלישה.", "Waiting for Wi‑Fi to download it. Downloads on mobile data can be allowed in Settings."),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = OzenSpacing.Unit).testTag("model-fit-waiting"),
            ) else Text(
                tr("החלפה", "Switch"),
                color = colors.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.End).clickable(role = Role.Button, onClick = onSwitch).testTag("model-fit-switch").heightIn(min = OzenSpacing.MinTouch).wrapContentHeight(Alignment.CenterVertically),
            )
        }
        BannerClose(tr("לא עכשיו", "Not now"), colors.onSurfaceVariant, onDismiss)
    }
}

/** The app was ended while it set up the chosen model, most likely for lack of memory; the captions wait for her choice instead of starting on their own. */
@Composable
private fun ModelLoadCrashCard(model: PhoneModel, lighter: PhoneModel, inUseBytes: Long?, switching: PhoneModelSwitch?, onSwitch: () -> Unit, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = tr("אוזן נסגרה בזמן שהכינה את מודל הדיבור", "Ozen closed while setting up the speech model")
    val body = tr(
        "ייתכן שאין בטלפון הזה מספיק זיכרון בשביל המודל ״\u2068%1\u2069״. המודל ״\u2068%2\u2069״ (%3) קל יותר.",
        "This phone may not have enough memory for %1. %2 (%3) is lighter.",
        listOf(model.displayName, lighter.displayName, ByteSize.text(lighter.bytes)),
    )
    // Shown in place of captions that would have started by themselves, below
    // the empty screen's text: a screen reader hears why, as on the iPhone.
    val context = LocalContext.current
    LaunchedEffect(model.file) {
        if (ScreenReader.isOn(context)) ScreenReader.announce(context, "$title. $body")
    }
    Column(
        modifier = Modifier.padding(top = 24.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh)
            .padding(start = OzenSpacing.Page, top = OzenSpacing.Page, bottom = OzenSpacing.Unit, end = OzenSpacing.Page).testTag("model-load-crash"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(
                    body,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        ReplacedModelLine(inUseBytes)
        switching?.let { LoadCrashSwitchStatus(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Page, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            Text(
                tr("נסו שוב", "Try again"),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRetry).testTag("model-load-crash-retry").heightIn(min = OzenSpacing.MinTouch).wrapContentHeight(Alignment.CenterVertically),
            )
            // While the lighter model downloads, or waits for a connection
            // it resumes on by itself, a second tap would do nothing.
            if (switching == null || switching is PhoneModelSwitch.Idle || switching is PhoneModelSwitch.Failed || switching is PhoneModelSwitch.NotEnoughRoom) Text(
                tr("החלפה", "Switch"),
                color = colors.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(role = Role.Button, onClick = onSwitch).testTag("model-load-crash-switch").heightIn(min = OzenSpacing.MinTouch).wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * Where the switch to the lighter model stands, on the card itself: the
 * card once vanished on Switch, leaving an empty screen and, when the
 * download could not start, no notification either.
 */
@Composable
private fun LoadCrashSwitchStatus(switching: PhoneModelSwitch) {
    val text = when (switching) {
        PhoneModelSwitch.Idle -> null
        is PhoneModelSwitch.Downloading -> if (switching.checking) {
            tr("בודקים את הקובץ…", "Checking the file…")
        } else {
            tr("מורידים… %1%", "Downloading… %1%", listOf("${(switching.fraction * 100).toInt()}"))
        }
        is PhoneModelSwitch.NotEnoughRoom -> tr("אין מקום להורדה. צריך לפנות עוד %1 MB.", "There is no room to download it. Free up %1 MB more.", listOf("${switching.megabytes}"))
        is PhoneModelSwitch.WaitingForWiFi -> tr("ממתינים ל־Wi‑Fi כדי להוריד. בהגדרות אפשר לאשר הורדה גם בחבילת הגלישה.", "Waiting for Wi‑Fi to download it. Downloads on mobile data can be allowed in Settings.")
        is PhoneModelSwitch.Offline -> tr("אין חיבור לאינטרנט, ולכן אי אפשר להוריד.", "There is no internet connection to download it.")
        is PhoneModelSwitch.Retrying -> tr("ההורדה נעצרה. מנסים שוב אוטומטית.", "The download stopped. Trying again on its own.")
        is PhoneModelSwitch.Failed -> tr("הורדת המודל נכשלה. כדאי לבדוק את החיבור ולנסות שוב.", "The model download failed. Check the connection and try again.")
    } ?: return
    val context = LocalContext.current
    LaunchedEffect(switching::class) {
        if (ScreenReader.isOn(context)) ScreenReader.announce(context, text)
    }
    Text(
        text,
        color = if (switching is PhoneModelSwitch.Downloading) MaterialTheme.colorScheme.onSurfaceVariant else cautionOrange(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp).testTag("model-load-crash-status"),
    )
}

/** Listening was cut off while the app was closed; she is told when and for how long, and the card goes with a tap. */
@Composable
private fun ListeningCutCard(text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    LaunchedEffect(text) {
        if (ScreenReader.isOn(context)) ScreenReader.announce(context, text)
    }
    Column(
        modifier = Modifier.padding(top = 24.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh)
            .padding(start = OzenSpacing.Page, top = OzenSpacing.Page, bottom = OzenSpacing.Unit, end = OzenSpacing.Page).testTag("listening-cut"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Text(text, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("listening-cut-text"))
        }
        Text(
            tr("אישור", "OK"),
            color = colors.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.End).clickable(role = Role.Button, onClick = onDismiss).testTag("listening-cut-dismiss").heightIn(min = OzenSpacing.MinTouch).wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** Said before the tap of a card that switches models: the one in use now is deleted once the new one is ready. */
@Composable
private fun ReplacedModelLine(bytes: Long?) {
    if (bytes == null) return
    Text(
        tr("אחרי ההחלפה, המודל שפועל עכשיו (%1) יימחק מהטלפון כדי לפנות מקום.", "The model in use now (%1) is then removed from the phone to free up space.", listOf(ByteSize.text(bytes))),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp).testTag("model-replaced-line"),
    )
}

/** The conversation the app closed in the middle of, a tap away, as on the iPhone. */
@Composable
private fun RecentConversationCard(recent: TranscriptSessionSummary, look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val minutes = RecentConversation.minutesAgo(recent, now = secondsNow())
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(start = OzenSpacing.Page, top = OzenSpacing.Page, bottom = OzenSpacing.Page),
        ) {
            IconBadge(Icons.Rounded.ChatBubble)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("השיחה מ%1 נשמרה", "The conversation from %1 was saved", listOf(HebrewTime.minutesAgo(minutes))), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                Text(CaptionLayout.directed(recent.title ?: recent.preview), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("הקישו כדי לקרוא אותה", "Tap to read it"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        BannerClose(tr("סגירה", "Close"), MaterialTheme.colorScheme.onSurfaceVariant, onDismiss)
    }
}

/**
 * "Said: grandma" for five seconds at the top, as the iPhone's pill, with
 * who said it when the line's voice is known; the screen reader reads it
 * as it appears.
 */
@Composable
private fun HeardPill(said: String, speaker: String?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh).padding(horizontal = OzenSpacing.Page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(Icons.Rounded.Hearing)
        Text(
            speaker?.let { tr("%1 — נאמר: %2", "%1 said: %2", listOf(it, said)) } ?: tr("נאמר: %1", "Said: %1", listOf(said)),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
    }
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
                contentDescription = tr("מסומנת כחשובה. ", "Marked as important. ").trim()
                hideFromAccessibility()
            },
    )
}

@Composable
internal fun UnsureMark(fontSize: Double, colour: Color) {
    val label = tr("ייתכן שלא נשמעה נכון. ", "May not have been heard correctly. ").trim()
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
private fun StatusLine(status: PhasePresentation, look: CaptionTheme, live: Boolean, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // As on the iPhone: paused, off or stopped shows its own icon and colour.
    // Only the small green dot went away before, and the old lines stayed,
    // so a glance at the phone took the silence for nobody talking.
    val quiet = !status.isBusy && !live
    val tone = when (status.tint) {
        PhaseTint.Problem -> colors.error
        PhaseTint.Attention -> OzenColors.statusWarn(dark = !look.isLight)
        PhaseTint.Inactive -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    // The state on its own line, what a tap does on a real button beside it:
    // a green "Listening" with "Tap to pause" in small print didn't look
    // like a button, and the hint went away with large text.
    val button = statusAction(status.action)
    val detail = status.stateDetail
    val state = @Composable { modifier: Modifier ->
        Column(
            // A download's bar is read with the state, in one stop: the bar
            // alone is an item of its own to TalkBack.
            modifier = modifier.testTag(STATUS_STATE).semantics(mergeDescendants = true) { status.progress?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..1f) } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
                // The title already says what it is busy with; only a download's bar is read out.
                if (status.isBusy) {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp).testTag(STATUS_BUSY).clearAndSetSemantics {})
                } else if (live) {
                    LiveDot(OzenColors.statusGood(dark = !look.isLight))
                } else {
                    Icon(status.icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp).testTag(STATUS_ICON))
                }
                val inline = detail != null && !status.isBusy && status.progress == null
                Text(
                    text = status.title,
                    color = if (quiet) tone else colors.onSurface,
                    style = if (quiet) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (inline) {
                    Text(text = "·", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
                    Text(text = detail.orEmpty(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (detail != null && (status.isBusy || status.progress != null)) {
                Text(text = detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            status.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress.toFloat() }, color = colors.primary, trackColor = colors.outlineVariant, modifier = Modifier.padding(top = 8.dp).widthIn(max = 200.dp).clearAndSetSemantics {})
            }
        }
    }
    val action = @Composable { modifier: Modifier ->
        button?.let { (label, icon) ->
            Button(
                onClick = onTap,
                modifier = modifier.heightIn(min = STATUS_BUTTON_HEIGHT).testTag(STATUS_BUTTON).semantics { stateDescription = status.title },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(OzenSpacing.Unit))
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
    val padding = Modifier.fillMaxWidth().heightIn(min = OzenSpacing.MinTouch).padding(horizontal = OzenSpacing.Page, vertical = OzenSpacing.Unit)
    // With large text the button gets a row of its own rather than squeezing
    // its words; it is never only an icon.
    if (button == null || LocalDensity.current.fontScale > 1.3f) {
        Column(padding, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit, Alignment.CenterVertically)) {
            state(Modifier)
            action(Modifier.fillMaxWidth())
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Page)) {
            state(Modifier.weight(1f))
            action(Modifier)
        }
    }
}

internal val STATUS_BUTTON_HEIGHT = 56.dp

internal const val STATUS_BUTTON = "statusButton"

internal const val STATUS_STATE = "statusState"

internal const val STATUS_BUSY = "statusBusy"

internal const val STATUS_ICON = "statusIcon"

/**
 * A dot beside "Listening" that breathes while the microphone is on, as
 * a recording light does; still for a phone that asks for less motion.
 */
@Composable
private fun LiveDot(colour: Color) {
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
    Box(Modifier.size(8.dp).graphicsLayer { alpha = glow }.background(colour, CircleShape).clearAndSetSemantics {})
}

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

private val PhoneModelSwitch.downloadingFile: String?
    get() = (this as? PhoneModelSwitch.Downloading)?.file
