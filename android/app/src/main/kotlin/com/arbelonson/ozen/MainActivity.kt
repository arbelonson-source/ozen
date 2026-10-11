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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.MicrophoneDropNotice
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.UncertainWords
import com.arbelonson.ozen.core.tr
import java.text.Bidi
import java.util.UUID
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val showingSettings = mutableStateOf(false)
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
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = AppLanguage.System.resolved(phoneLanguages)
        enableEdgeToEdge()
        val app = application as OzenApplication
        setContent { OzenApp(app, showingSettings, onboarding(), onStatusTap = ::statusTapped, onLaunch = ::startListening, onLightScreen = ::systemBarsFor) }
        openLink(intent)
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
        (application as OzenApplication).captions.appIsActive = true
    }

    override fun onStop() {
        (application as OzenApplication).captions.appIsActive = false
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
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        askForMicrophone.launch(wanted.toTypedArray())
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
private fun OzenApp(app: OzenApplication, showingSettings: MutableState<Boolean>, onboarding: OnboardingHooks, onStatusTap: () -> Unit, onLaunch: () -> Unit, onLightScreen: (Boolean) -> Unit) {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val state by CaptionState.screen.collectAsStateWithLifecycle()
    val current by app.settings.current.collectAsStateWithLifecycle()
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<Pair<UUID, UUID>?>(null) }
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
    // end it partway, as the iPhone found.
    KeepScreenOn(!current.hasCompletedOnboarding || state.keepsScreenOn(current.display.keepScreenAwake, awakeClock))
    val look = CaptionTheme.of(current.display.theme, isSystemInDarkTheme())
    LaunchedEffect(look.isLight) { onLightScreen(look.isLight) }
    MaterialTheme(colorScheme = if (look.isLight) lightColorScheme() else darkColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            val opened = reading
            when {
                !current.hasCompletedOnboarding -> {
                    LaunchedEffect(Unit) { onboarding.shown() }
                    OnboardingScreen(
                        settings = current,
                        microphone = onboarding.microphone.value,
                        notifications = onboarding.notifications.value,
                        onApproveMicrophone = onboarding.approveMicrophone,
                        onApproveNotifications = onboarding.approveNotifications,
                        onOpenPhoneSettings = onboarding.openPhoneSettings,
                        onAddName = app.captions::addNameAlert,
                        onAddAlert = app.captions::addAlert,
                        onFinish = onboarding.finish,
                    )
                }
                showingSettings.value -> SettingsScreen(app.engineSettings, app.settings, onClose = { showingSettings.value = false }, onWords = { app.captions.setWords(it) }, onImportantWords = { app.captions.setImportantWords(it) }, canSay = app.captions::canSay, onSpeak = { app.captions.speak(it) }, diagnostics = app.captions, heard = state.recentlyHeard)
                opened != null -> ConversationScreen(
                    app.captions.savedConversations,
                    opened.first,
                    query = "",
                    marksUncertain = current.display.markUncertainLines,
                    emphasizeNumbers = current.display.emphasizeNumbers,
                    onClose = { reading = null },
                    line = opened.second,
                )
                namingAlert -> NameAlertScreen(current, onAddName = app.captions::addNameAlert, onAddAlert = app.captions::addAlert, onClose = { namingAlert = false })
                choosingMicrophone -> MicPickerScreen(
                    inputs = state.inputs,
                    selected = state.selectedInput,
                    listening = state.phase.isListening,
                    level = { app.captions.pipeline.inputLevel },
                    onChoose = { app.captions.selectInput(it.uid) },
                    onRefresh = app.captions::refreshInputs,
                    onClose = { choosingMicrophone = false },
                )
                saying -> SayScreen(
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
                )
                showingHistory -> HistoryScreen(
                    app.captions.savedConversations,
                    saving = current.saveHistory,
                    retention = current.historyRetention,
                    marksUncertain = current.display.markUncertainLines,
                    emphasizeNumbers = current.display.emphasizeNumbers,
                    onClose = { showingHistory = false },
                )
                else -> {
                    LaunchedEffect(Unit) { if (app.captions.takeFirstAppearance()) onLaunch() }
                    CaptionScreen(
                        state,
                        current.display,
                        look,
                        onStatusTap,
                        onSettings = { showingSettings.value = true },
                        onHistory = { showingHistory = true },
                        onStar = { app.captions.toggleStar(it) },
                        onOpen = { conversation, line ->
                            app.captions.saveBeforeReading()
                            reading = conversation to line
                        },
                        onAddWord = { app.captions.addWord(it) },
                        onAskToRepeat = { app.captions.askToRepeat() }.takeIf { app.captions.canAskToRepeat() },
                        onSay = { if (state.speaking) app.captions.stopSpeaking() else saying = true },
                        onDismissSavingTrouble = app.captions::dismissSavingTrouble,
                        onDismissMicrophoneDrop = app.captions::dismissMicrophoneDrop,
                        onMarkProblem = app.captions::markProblem,
                        onMicrophone = { choosingMicrophone = true },
                        offersNameAlert = current.offersNameAlert,
                        onNameAlert = { namingAlert = true },
                        onDismissNameAlert = app.captions::dismissNameAlertOffer,
                    )
                }
            }
            PairingDialogs(app.pairing)
        }
    }
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
    onAskToRepeat: (() -> Unit)?,
    onSay: () -> Unit,
    onDismissSavingTrouble: () -> Unit,
    onDismissMicrophoneDrop: () -> Unit,
    onMarkProblem: () -> Boolean,
    onMicrophone: () -> Unit,
    offersNameAlert: Boolean,
    onNameAlert: () -> Unit,
    onDismissNameAlert: () -> Unit,
) {
    val list = rememberLazyListState()
    var problemMarked by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    var shownWord by remember { mutableStateOf<HeardWord?>(null) }
    // Only a word said just now: one heard while the screen was away buzzing
    // on coming back would send her looking for something long over.
    LaunchedEffect(state.heard?.id) {
        val word = state.heard ?: return@LaunchedEffect
        if (secondsNow() - word.at > HEARD_SHOWN_SECONDS) return@LaunchedEffect
        Buzz.play(context, AlertVibration.keyword)
        shownWord = word
        delay((HEARD_SHOWN_SECONDS * 1_000).toLong())
        if (shownWord?.id == word.id) shownWord = null
    }
    var fixing by remember { mutableStateOf<CaptionLine?>(null) }
    fixing?.let { line ->
        FixWordDialog(line.text, onAdd = { onAddWord(it); fixing = null }, onDismiss = { fixing = null })
    }
    problemMarked?.let { keptSound -> ProblemMarkedDialog(keptSound, onDismiss = { problemMarked = null }) }
    var following by remember { mutableStateOf(true) }
    var lastWhenLeft by remember { mutableStateOf<UUID?>(null) }
    val newest by rememberUpdatedState(state.lines.lastOrNull()?.id)
    // Only her own scrolling passes through here, not the screen following
    // the newest line, so a line growing past the bottom for a moment
    // doesn't stop the following, as it once did on the iPhone.
    val rereading = remember(list) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val atEnd = !list.canScrollForward
                if (following && !atEnd) lastWhenLeft = newest
                following = atEnd
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state.lines.isEmpty()) { if (state.lines.isEmpty()) following = true }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.lines.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                        color = look.text.copy(alpha = 0.6f),
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (offersNameAlert) NameAlertOffer(look, onNameAlert, onDismissNameAlert)
                }
            } else {
                CaptionLines(state.lines, display, look, list, following, Modifier.nestedScroll(rereading), onStar, onOpen, onFix = { fixing = it }, onAskToRepeat, onMarkProblem = { problemMarked = onMarkProblem() })
                if (!following) {
                    JumpToLatest(jumpToLatestTitle(linesSince(state.lines, lastWhenLeft)), look, Modifier.align(Alignment.BottomCenter)) {
                        following = true
                    }
                }
            }
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                shownWord?.let { HeardPill(it.said, Modifier) }
                if (state.savingTrouble) SavingTroubleBanner(onDismissSavingTrouble)
                state.dropped?.let { lost ->
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
            }
        }
        AnimatedVisibility(visible = !hidden) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusButton(state.status, look.chrome, onStatusTap)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onHistory) {
                        Text(tr("היסטוריה", "History"), fontSize = 18.sp)
                    }
                    val choose = tr("בחירת מיקרופון", "Choose microphone")
                    TextButton(
                        onClick = onMicrophone,
                        modifier = Modifier.widthIn(max = 120.dp).semantics {
                            contentDescription = choose
                            stateDescription = state.selectedInput?.portName ?: ""
                        },
                    ) {
                        // Shrunk to fit as on the iPhone; only a name too long even then is cut.
                        Text(
                            state.selectedInput?.let(::inputShortName) ?: tr("מיקרופון", "Microphone"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp),
                        )
                    }
                    // While the phone talks, this stops it at once instead of
                    // opening the Say screen, as on the iPhone: captions stay
                    // paused for as long as it talks.
                    val say = if (state.speaking) tr("עצירת הדיבור", "Stop speaking") else tr("להגיד משהו בקול", "Say something out loud")
                    TextButton(onClick = onSay, modifier = Modifier.semantics { contentDescription = say }) {
                        Text(if (state.speaking) "■ " + tr("להגיד", "Say") else tr("להגיד", "Say"), fontSize = 18.sp)
                    }
                    TextButton(onClick = onSettings) {
                        Text(tr("הגדרות", "Settings"), fontSize = 18.sp)
                    }
                }
            }
        }
        if (hidden) ShowButtonsButton(look.chrome) { lastTouchAt = secondsNow() }
    }
}

private fun secondsNow() = System.currentTimeMillis() / 1_000.0

private const val HEARD_SHOWN_SECONDS = 5.0

// A deep orange as on the iPhone, with white words on it at 5.5:1.
private val TROUBLE_ORANGE = Color(red = 0.7f, green = 0.28f, blue = 0f)

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
    display: DisplayPreferences,
    look: CaptionTheme,
    list: LazyListState,
    following: Boolean,
    modifier: Modifier,
    onStar: (UUID) -> Unit,
    onOpen: (conversation: UUID, line: UUID) -> Unit,
    onFix: (CaptionLine) -> Unit,
    onAskToRepeat: (() -> Unit)?,
    onMarkProblem: () -> Unit,
) {
    LaunchedEffect(lines.size, lines.lastOrNull()?.text, following, display.fontSize) {
        if (following) list.scrollToEnd(lines.lastIndex)
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines, key = { it.id }) { line -> CaptionLineRow(line, display, look, onStar = { onStar(line.id) }, onOpen = { conversation -> onOpen(conversation, line.id) }, onFix = { onFix(line) }, onAskToRepeat, onMarkProblem) }
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
 * One caption line, laid out in its own reading direction as on the iPhone,
 * so a Hebrew line starts at the right whatever language the buttons are
 * in. A line the engine wasn't sure of gets a question mark at its start
 * and dots under the words it doubted, without changing their colour: the
 * words stay as readable as any other line. Held, as on the iPhone, it can
 * be marked as important (a star) or copied, and, with a voice on the phone
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
    val open = tr("פתיחת השיחה כולה, לשיתוף או לחיפוש", "Open the whole conversation, to share or search")
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
    CompositionLocalProvider(LocalLayoutDirection provides readingDirection(line.text)) {
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
                    .semantics {
                        if (spoken.isNotEmpty()) {
                            stateDescription = tr("מילים שאולי לא נשמעו נכון: %1", "Words that may be wrong: %1", listOf(spoken.joinToString(", ")))
                        }
                        customActions = actions
                    },
            )
        }
    }
}

private fun readingDirection(shown: String) =
    if (Bidi(shown, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()) LayoutDirection.Ltr else LayoutDirection.Rtl

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
            .clearAndSetSemantics { contentDescription = tr("מכילה מילה חשובה", "Contains an important word") },
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
            Text(
                tr(
                    "כנראה שהטלפון מלא. שיחות והגדרות חדשות לא יישמרו עד שיתפנה מקום. בקשו ממי שעוזר לך עם הטלפון לפנות מקום.",
                    "The phone is probably full. New conversations and settings won't be saved until space frees up. Ask whoever helps you with the phone to free some.",
                ),
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

/** "Said: grandma" for five seconds at the top, as the iPhone's pill; the screen reader reads it as it appears. */
@Composable
private fun HeardPill(said: String, modifier: Modifier) {
    Text(
        tr("נאמר: %1", "Said: %1", listOf(said)),
        color = Color.Black,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(Color.Yellow.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
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
            .clearAndSetSemantics { contentDescription = tr("מסומן כחשוב. ", "Marked as important. ").trim() },
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
            .clearAndSetSemantics { contentDescription = label },
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
private fun StatusButton(status: PhasePresentation, chrome: Color, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(chrome.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .clickable(onClick = onTap)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = status.title, color = chrome, fontSize = 22.sp, textAlign = TextAlign.Center)
        status.detail?.let { detail ->
            Text(text = detail, color = chrome.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}
