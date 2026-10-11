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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UncertainWords
import com.arbelonson.ozen.core.tr
import java.text.Bidi
import java.util.UUID
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val showingSettings = mutableStateOf(false)

    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            ListeningService.start(this)
        } else {
            (application as OzenApplication).captions.listen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = AppLanguage.System.resolved(phoneLanguages)
        enableEdgeToEdge()
        val app = application as OzenApplication
        setContent { OzenApp(app, showingSettings, onStatusTap = ::statusTapped, onLightScreen = ::systemBarsFor) }
        openLink(intent)
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
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        // The scrims enableEdgeToEdge draws behind three-button navigation by default.
        val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

@Composable
private fun OzenApp(app: OzenApplication, showingSettings: MutableState<Boolean>, onStatusTap: () -> Unit, onLightScreen: (Boolean) -> Unit) {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val state by CaptionState.screen.collectAsStateWithLifecycle()
    val current by app.settings.current.collectAsStateWithLifecycle()
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<Pair<UUID, UUID>?>(null) }
    var awakeClock by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1_000.0) }
    LaunchedEffect(state.phase.isListening) {
        while (true) {
            awakeClock = System.currentTimeMillis() / 1_000.0
            delay(30_000)
        }
    }
    KeepScreenOn(state.keepsScreenOn(current.display.keepScreenAwake, awakeClock))
    val look = CaptionTheme.of(current.display.theme, isSystemInDarkTheme())
    LaunchedEffect(look.isLight) { onLightScreen(look.isLight) }
    MaterialTheme(colorScheme = if (look.isLight) lightColorScheme() else darkColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            val opened = reading
            when {
                showingSettings.value -> SettingsScreen(app.engineSettings, app.settings, onClose = { showingSettings.value = false }, onWords = { app.captions.setWords(it) })
                opened != null -> ConversationScreen(
                    app.captions.savedConversations,
                    opened.first,
                    query = "",
                    marksUncertain = current.display.markUncertainLines,
                    emphasizeNumbers = current.display.emphasizeNumbers,
                    onClose = { reading = null },
                    line = opened.second,
                )
                showingHistory -> HistoryScreen(
                    app.captions.savedConversations,
                    saving = current.saveHistory,
                    retention = current.historyRetention,
                    marksUncertain = current.display.markUncertainLines,
                    emphasizeNumbers = current.display.emphasizeNumbers,
                    onClose = { showingHistory = false },
                )
                else -> CaptionScreen(
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
                )
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
) {
    val list = rememberLazyListState()
    var fixing by remember { mutableStateOf<CaptionLine?>(null) }
    fixing?.let { line ->
        FixWordDialog(line.text, onAdd = { onAddWord(it); fixing = null }, onDismiss = { fixing = null })
    }
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
                Text(
                    text = tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                    color = look.text.copy(alpha = 0.6f),
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                CaptionLines(state.lines, display, look, list, following, Modifier.nestedScroll(rereading), onStar, onOpen, onFix = { fixing = it })
                if (!following) {
                    JumpToLatest(jumpToLatestTitle(linesSince(state.lines, lastWhenLeft)), look, Modifier.align(Alignment.BottomCenter)) {
                        following = true
                    }
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
) {
    LaunchedEffect(lines.size, lines.lastOrNull()?.text, following, display.fontSize) {
        if (following) list.scrollToEnd(lines.lastIndex)
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines, key = { it.id }) { line -> CaptionLineRow(line, display, look, onStar = { onStar(line.id) }, onOpen = { conversation -> onOpen(conversation, line.id) }, onFix = { onFix(line) }) }
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
 * be marked as important (a star) or copied; a screen reader has both too.
 */
@Composable
private fun CaptionLineRow(
    line: CaptionLine,
    display: DisplayPreferences,
    look: CaptionTheme,
    onStar: () -> Unit,
    onOpen: (UUID) -> Unit,
    onFix: () -> Unit,
) {
    val doubtful = if (display.markUncertainLines) line.doubtfulWords else emptyList()
    val spoken = UncertainWords.spoken(line.text, doubtful)
    val colour = if (line.isFinal) look.text else look.pendingText
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val star = if (line.starred) tr("ביטול הסימון", "Remove mark") else tr("סימון כחשוב", "Mark as important")
    val copy = tr("העתקה", "Copy")
    val fix = tr("תיקון מילה למילון", "Fix a word for next time")
    val open = tr("פתיחת השיחה כולה, לשיתוף או לחיפוש", "Open the whole conversation, to share or search")
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
                CustomAccessibilityAction(fix) { onFix(); true },
                CustomAccessibilityAction(copy) { copyLine(); true },
            ) + listOfNotNull(line.conversation).map { conversation ->
                CustomAccessibilityAction(open) { onOpen(conversation); true }
            } + line.phones.map { phone ->
                CustomAccessibilityAction(tr("להתקשר ל־%1", "Call %1", listOf(phone.dialable))) { uris.openUri(phone.url); true }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(star) }, onClick = { menu = false; onStar() })
            DropdownMenuItem(text = { Text(fix) }, onClick = { menu = false; onFix() })
            DropdownMenuItem(text = { Text(copy) }, onClick = { menu = false; copyLine() })
            line.conversation?.let { conversation ->
                DropdownMenuItem(text = { Text(open) }, onClick = { menu = false; onOpen(conversation) })
            }
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
