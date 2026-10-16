package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AlertSuggestions
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.FirstRunNote
import com.arbelonson.ozen.core.HebrewText
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.audioLeavesPhone
import com.arbelonson.ozen.core.firstRunNote
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.IconBadge
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.TonalButton
import kotlinx.coroutines.launch

/**
 * First launch, as on the iPhone: short pages in large type saying what the
 * app is and how it works, the language people around her speak, the
 * microphone asked for with a reason (and the
 * notifications that reach her while the phone is put away), her name for
 * the alert that buzzes when she is called, and go. The iPhone's page for
 * choosing the engine and its model download is left out until where the
 * phone downloads its model from is decided; Settings has the choice.
 *
 * [microphone] and [notifications] are null until asked, then whether the
 * phone said yes. Skip and Start both finish; the caption screen then
 * starts the captions on its first appearance, as on the iPhone.
 */
@Composable
internal fun OnboardingScreen(
    settings: AppSettings,
    microphone: Boolean?,
    notifications: Boolean?,
    onApproveMicrophone: () -> Unit,
    onApproveNotifications: () -> Unit,
    onOpenPhoneSettings: () -> Unit,
    onSpokenLanguage: (UILanguage) -> Unit,
    onAddName: (String) -> Unit,
    onAddAlert: (String) -> Unit,
    onFinish: () -> Unit,
) {
    val pager = rememberPagerState(pageCount = { PAGES })
    val turning = rememberCoroutineScope()
    // The iPhone's pages swipe back to the one before; Back does the same
    // here, and only on the first page leaves the app.
    BackHandler(enabled = pager.currentPage > 0) {
        turning.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    val nameDraft = rememberSaveable { mutableStateOf("") }
    val addName by rememberUpdatedState(onAddName)
    val addDraft = {
        if (nameDraft.value.isNotBlank()) addName(nameDraft.value.trim())
        nameDraft.value = ""
    }
    // A name typed and then "Next" or a swipe instead of "Add" was dropped
    // on the iPhone, and the name alert never came.
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { if (it != NAME_PAGE) addDraft() }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Page(Icons.Rounded.Hearing, tr("אוזן", "Ozen")) {
                        Body(tr("כתוביות חיות לשיחה.", "Live captions for conversation."))
                        Body(tr("מה שאומרים לידך מופיע על המסך, באותיות גדולות, תוך כדי הדיבור.", "What’s said near you appears on the screen, in large letters, as it’s spoken."))
                        if (settings.engine == TranscriptionEngineKind.HomeServer) {
                            Body(tr("הקול נשלח רק למחשב של המשפחה בבית, ורק בזמן שהכתוביות פועלות.", "The audio goes only to the family’s computer at home, and only while captions are on."))
                        } else if (!settings.audioLeavesPhone) {
                            Body(tr("כברירת מחדל, הכל קורה בתוך הטלפון.", "By default it all happens on the phone."))
                        }
                    }
                    1 -> Page(Icons.Rounded.Forum, tr("איך זה עובד", "How it works")) {
                        SettingsGroup(dividerInset = 72.dp) {
                            item { Point(Icons.Rounded.Mic, tr("מניחים את הטלפון על השולחן, והכתוביות רצות לבד.", "Set the phone on the table, and the captions run on their own.")) }
                            item { Point(Icons.Rounded.Groups, tr("האפליקציה מבדילה בין דוברים ויכולה ללמוד את השמות שלהם.", "The app tells speakers apart and can learn their names.")) }
                            item { Point(Icons.Rounded.NotificationsActive, tr("היא מתריעה על שמות שחשובים לך, ועל צלצול בדלת או אזעקה, ברטט שונה לכל אחד.", "It alerts you to names that matter to you, and to a doorbell or alarm, with a different vibration for each.")) }
                            item { Point(Icons.Rounded.Keyboard, tr("ואפשר להקליד תשובה, והטלפון יגיד אותה בקול.", "And you can type a reply, and the phone will speak it aloud.")) }
                        }
                    }
                    2 -> LanguagePage(settings.spokenLanguage, onSpokenLanguage)
                    3 -> MicrophonePage(settings, microphone, notifications, onApproveMicrophone, onApproveNotifications, onOpenPhoneSettings)
                    NAME_PAGE -> NamePage(settings, nameDraft, addDraft, onAddAlert)
                    else -> Page(Icons.Rounded.CheckCircle, tr("מוכן", "Ready")) {
                        if (settings.engine.firstRunNote == FirstRunNote.ModelDownload) {
                            Body(tr("בהפעלה הראשונה אוזן תוריד את מודל השפה. זה לוקח כמה דקות ומוצג על המסך. אחר כך — מיד.", "The first time it runs, Ozen will download the language model. This takes a few minutes and shows on the screen. After that — instantly."))
                        }
                        if (settings.engine.firstRunNote == FirstRunNote.HomeComputer) {
                            Body(tr("מחובר בקוד שסרקתם. הכתוביות מגיעות מהמחשב בבית.", "Connected with the code you scanned. Captions come from the computer at home."))
                        }
                        Body(tr("הכפתור למטה מתחיל את הכתוביות. בהצלחה, סבתא.", "The button below starts the captions. Good luck, Grandma."))
                    }
                }
            }
            Dots(pager)
            Footer(pager, onFinish = {
                addDraft()
                onFinish()
            })
        }
    }
}

@Composable
private fun MicrophonePage(
    settings: AppSettings,
    microphone: Boolean?,
    notifications: Boolean?,
    onApproveMicrophone: () -> Unit,
    onApproveNotifications: () -> Unit,
    onOpenPhoneSettings: () -> Unit,
) {
    Page(Icons.Rounded.Mic, tr("המיקרופון", "The microphone")) {
        Body(tr("כדי להציג כתוביות לשיחה, אוזן צריכה להאזין דרך המיקרופון.", "To caption the conversation, Ozen needs to listen through the microphone."))
        if (!settings.audioLeavesPhone) {
            Body(tr("הקול לא יוצא מהטלפון, ולא נשמר אלא אם מסמנים בעיה.", "The sound doesn’t leave the phone, and it isn’t kept unless you mark a problem."))
        }
        when (microphone) {
            true -> {
                Status("✓ " + tr("המיקרופון מאושר", "Microphone approved"), good = true)
                Body(tr("ועוד דבר אחד: כשהטלפון בכיס או נעול, אוזן יכולה להודיע על צלצול בדלת, אזעקה או השם שלך.", "One more thing: when the phone is in a pocket or locked, Ozen can notify you about a doorbell, an alarm, or your name."))
                when (notifications) {
                    true -> Status("✓ " + tr("ההתראות מאושרות", "Notifications approved"), good = true)
                    false -> Body(tr("בלי התראות. אפשר לשנות בהגדרות.", "No notifications. This can be changed in Settings."))
                    null -> TonalButton(tr("לאשר התראות", "Approve notifications"), onApproveNotifications, Modifier.fillMaxWidth(), icon = Icons.Rounded.NotificationsActive)
                }
            }
            false -> {
                Status("✕ " + tr("המיקרופון חסום", "Microphone blocked"), good = false)
                Body(tr("בלי מיקרופון אין כתוביות. אפשר לאשר בהגדרות הטלפון.", "Without a microphone there are no captions. It can be approved in the phone’s Settings."))
                TonalButton(tr("פתיחת הגדרות הטלפון", "Open phone settings"), onOpenPhoneSettings, Modifier.fillMaxWidth(), icon = Icons.Rounded.Settings)
            }
            null -> PrimaryButton(tr("לאשר את המיקרופון", "Approve the microphone"), onApproveMicrophone, Modifier.fillMaxWidth(), icon = Icons.Rounded.Mic)
        }
    }
}

/**
 * The alert for her name only works once someone has typed the name in,
 * and that screen is three levels deep in Settings; here it's asked for
 * while the family member setting the phone up is still holding it.
 */
@Composable
private fun NamePage(settings: AppSettings, draft: MutableState<String>, addDraft: () -> Unit, onAddAlert: (String) -> Unit) {
    Page(Icons.Rounded.NotificationsActive, tr("כשקוראים לך", "When you’re called")) {
        Body(tr("כשמישהו אומר את השם שלך, הטלפון רוטט והשורה מסומנת, גם כשלא מסתכלים על המסך.", "When someone says your name, the phone vibrates and the line is highlighted, even when no one is looking at the screen."))
        NameAlertForm(settings, draft, addDraft, onAddAlert)
        Text(
            tr("אפשר להוסיף עוד מילים, או למחוק, בהגדרות ← התראות ← מילים חשובות.", "More words can be added or removed in Settings → Alerts → Important words."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Her name typed, or one of the suggestions, as the iPhone's form: the
 * walkthrough's page and the caption screen's offer both use it. A typed
 * name goes through [addDraft]; a suggestion only becomes an alert.
 */
@Composable
internal fun NameAlertForm(settings: AppSettings, draft: MutableState<String>, addDraft: () -> Unit, onAddAlert: (String) -> Unit) {
    // Past the largest ordinary text size, one above the other, as the
    // iPhone's form at its accessibility sizes: beside it, German's
    // "Hinzufügen" left the field a few letters wide.
    val stacked = LocalDensity.current.fontScale > LARGEST_ORDINARY_FONT_SCALE
    val field = @Composable { modifier: Modifier ->
        OutlinedTextField(
            value = draft.value,
            onValueChange = { draft.value = it },
            placeholder = { Text(tr("השם שלך", "Your name")) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addDraft() }),
            modifier = modifier,
        )
    }
    val add = @Composable { modifier: Modifier ->
        TonalButton(tr("להוסיף", "Add"), addDraft, modifier, enabled = draft.value.isNotBlank())
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (stacked) {
            field(Modifier.fillMaxWidth())
            add(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.weight(1f))
                add(Modifier)
            }
        }
        if (HebrewText.isInOtherLetters(draft.value, settings.languageCode)) {
            Text(
                tr("הכתוביות באותיות עבריות, ולכן מילה שנכתבה באותיות אחרות עלולה לא להימצא אף פעם.", "Captions are in Hebrew letters, so a word written in other letters may never be found."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val suggestions = @Composable { modifier: Modifier ->
            for (word in AlertSuggestions.offeredNames(settings.spokenLanguage)) {
                val added = settings.keywordAlerts.any { it.isEnabled && HebrewText.normalize(it.phrase) == HebrewText.normalize(word) }
                OutlinedButton(onClick = { onAddAlert(word) }, enabled = !added, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.heightIn(min = 48.dp)) {
                    Text((if (added) "✓ " else "+ ") + AlertSuggestions.label(word, Localization.language), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { suggestions(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { suggestions(Modifier) }
        }
        AlertSuggestions.vibrationNote(settings.keywordAlerts, Localization.language)?.let { Status("✓ $it", good = true) }
    }
}

/** Before the microphone, as before the iPhone's engine page; Hebrew until she picks another. */
@Composable
private fun LanguagePage(chosen: UILanguage, onChoose: (UILanguage) -> Unit) {
    Page(Icons.Rounded.Translate, tr("באיזו שפה מדברים סביבך?", "Which language do people around you speak?")) {
        SettingsGroup(modifier = Modifier.selectableGroup()) {
            for (language in UILanguage.entries) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = language == chosen, role = Role.RadioButton) { onChoose(language) }
                            .padding(horizontal = OzenSpacing.Page),
                    ) {
                        Text(
                            language.spokenName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (language == chosen) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(selected = language == chosen, onClick = null)
                    }
                }
            }
        }
        Body(tr("הכתוביות נכתבות בשפה הזו. אפשר לשנות בכל רגע בהגדרות.", "The captions are written in this language. You can change it anytime in Settings."))
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, content: @Composable () -> Unit) = BoxWithConstraints {
    // A picture, not words: it stays one size at the phone's largest text,
    // and a smaller one on a short screen, where the page's button was
    // pushed out of sight below its text.
    val roomy = maxHeight >= 520.dp * LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().fadeWhenMoreBelow(scroll).verticalScroll(scroll).padding(horizontal = OzenSpacing.Page, vertical = if (roomy) 24.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (roomy) 16.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The page's picture springs into place as the page arrives, unless
        // the phone asks for less motion.
        var arrived by remember { mutableStateOf(ReducedMotion.isOn()) }
        LaunchedEffect(Unit) { arrived = true }
        val pop by animateFloatAsState(if (arrived) 1f else 0.5f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "page symbol")
        IconBadge(
            icon,
            size = if (roomy) 96.dp else 72.dp,
            modifier = Modifier
                .padding(top = if (roomy) 16.dp else 0.dp)
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                    alpha = ((pop - 0.5f) * 2f).coerceIn(0f, 1f)
                },
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { heading() },
        )
        Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = OzenSpacing.Page, vertical = 12.dp),
    ) {
        IconBadge(icon)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Status(text: String, good: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (good) scheme.primaryContainer else scheme.errorContainer,
        contentColor = if (good) scheme.onPrimaryContainer else scheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Dots(pager: PagerState) {
    val still = ReducedMotion.isOn()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics {
            contentDescription = tr("%1 מתוך %2", "%1 of %2", listOf("${pager.currentPage + 1}", "$PAGES"))
        },
    ) {
        repeat(PAGES) { page ->
            val current = page == pager.currentPage
            val colour = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            val width by animateDpAsState(if (current) 24.dp else 8.dp, if (still) snap() else tween(200), label = "page dot")
            Box(Modifier.width(width).height(8.dp).background(colour, CircleShape))
        }
    }
}

@Composable
private fun Footer(pager: PagerState, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = OzenSpacing.Page, vertical = 16.dp),
    ) {
        if (pager.currentPage < PAGES - 1) {
            SideBySideOrStacked(
                gap = 16.dp,
                start = {
                    // Bare text is too small a target beside Next, and a
                    // slip ends the walkthrough.
                    TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tr("דילוג", "Skip"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                end = {
                    PrimaryButton(
                        tr("הבא", "Next"),
                        onClick = {
                            // Let go of the name field first: focused, it
                            // pulled the pages back to itself mid-slide.
                            focusManager.clearFocus()
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        },
                        modifier = Modifier.widthIn(min = 120.dp),
                    )
                },
            )
        } else {
            PrimaryButton(tr("להתחיל", "Start"), onFinish, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
        }
    }
}

/**
 * [start] at the start and [end] at the end, or, where the two don't fit
 * side by side, [end] across the page with [start] under it: German at the
 * phone's largest text squeezed "Weiter" beside "Überspringen" until it
 * broke in the middle of the word.
 */
@Composable
private fun SideBySideOrStacked(gap: Dp, start: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(start, end)) { (starts, ends), constraints ->
        val first = starts.single()
        val second = ends.single()
        val space = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val wanted = first.maxIntrinsicWidth(Constraints.Infinity) + space + second.maxIntrinsicWidth(Constraints.Infinity)
        if (wanted <= constraints.maxWidth) {
            val a = first.measure(loose)
            val b = second.measure(loose)
            val height = maxOf(a.height, b.height)
            layout(constraints.maxWidth, height) {
                a.placeRelative(0, (height - a.height) / 2)
                b.placeRelative(constraints.maxWidth - b.width, (height - b.height) / 2)
            }
        } else {
            val b = second.measure(Constraints.fixedWidth(constraints.maxWidth))
            val a = first.measure(loose)
            layout(constraints.maxWidth, b.height + space + a.height) {
                b.placeRelative(0, 0)
                a.placeRelative((constraints.maxWidth - a.width) / 2, b.height + space)
            }
        }
    }
}

private const val PAGES = 6
private const val NAME_PAGE = 4
