package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.UncertainWords
import com.arbelonson.ozen.core.tr
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun CaptionLines(
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
internal fun PipelinePhase.modelGettingReady(): Boolean {
    val stage = (this as? PipelinePhase.PreparingEngine)?.progress?.stage ?: return false
    return stage != EnginePreparationProgress.Stage.CheckingSupport && stage != EnginePreparationProgress.Stage.RequestingPermission
}

/** Where the screen's own following last left the list, and how many of its moves are under way. */
internal class FollowMarks {
    var scrolling = 0
    var at: Pair<Any?, Int>? = null
}

/** The first line in sight and how far it is scrolled: lines added below or dropped above leave it alone. */
internal fun LazyListState.place(): Pair<Any?, Int> = layoutInfo.visibleItemsInfo.firstOrNull()?.key to firstVisibleItemScrollOffset

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
internal fun JumpToLatest(title: String, look: CaptionTheme, modifier: Modifier, onTap: () -> Unit) {
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
internal fun AwayJump(count: Int, look: CaptionTheme, onTap: () -> Unit) {
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
