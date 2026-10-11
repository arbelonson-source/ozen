package com.arbelonson.ozen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AlertSuggestions
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.FirstRunNote
import com.arbelonson.ozen.core.HebrewText
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.audioLeavesPhone
import com.arbelonson.ozen.core.firstRunNote
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.launch

/**
 * First launch, as on the iPhone: short pages in large type saying what the
 * app is and how it works, the microphone asked for with a reason (and the
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
    onAddWord: (String) -> Unit,
    onFinish: () -> Unit,
) {
    val pager = rememberPagerState(pageCount = { PAGES })
    val nameDraft = rememberSaveable { mutableStateOf("") }
    val addWord by rememberUpdatedState(onAddWord)
    val addDraft = {
        if (nameDraft.value.isNotBlank()) addWord(nameDraft.value.trim())
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
                    0 -> Page("👂", tr("אוזן", "Ozen")) {
                        Body(tr("כתוביות חיות לשיחה.", "Live captions for conversation."))
                        Body(tr("מה שאומרים לידך מופיע על המסך, באותיות גדולות, תוך כדי הדיבור.", "What’s said near you appears on the screen, in large letters, as it’s spoken."))
                        if (settings.engine == TranscriptionEngineKind.HomeServer) {
                            Body(tr("הקול נשלח רק למחשב של המשפחה בבית, ורק בזמן שהכתוביות פועלות.", "The audio goes only to the family’s computer at home, and only while captions are on."))
                        } else if (!settings.audioLeavesPhone) {
                            Body(tr("הכל קורה בתוך הטלפון. שום דבר לא נשלח לאינטרנט.", "It all happens on the phone. Nothing is sent to the internet."))
                        }
                    }
                    1 -> Page("💬", tr("איך זה עובד", "How it works")) {
                        Point("🎙", tr("מניחים את הטלפון על השולחן, והכתוביות רצות לבד.", "Set the phone on the table, and the captions run on their own."))
                        Point("👥", tr("האפליקציה מבדילה בין דוברים ויכולה ללמוד את השמות שלהם.", "The app tells speakers apart and can learn their names."))
                        Point("🔔", tr("היא מתריעה על שמות שחשובים לך, ועל צלצול בדלת או אזעקה, ברטט שונה לכל אחד.", "It alerts you to names that matter to you, and to a doorbell or alarm, with a different vibration for each."))
                        Point("⌨", tr("ואפשר להקליד תשובה, והטלפון יגיד אותה בקול.", "And you can type a reply, and the phone will speak it aloud."))
                    }
                    2 -> MicrophonePage(settings, microphone, notifications, onApproveMicrophone, onApproveNotifications, onOpenPhoneSettings)
                    NAME_PAGE -> NamePage(settings, nameDraft, addDraft, onAddWord)
                    else -> Page("✅", tr("מוכן", "Ready")) {
                        // The app does not download the phone's model yet, so
                        // the iPhone's note about that first download would be
                        // untrue here.
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
    Page("🎙", tr("המיקרופון", "The microphone")) {
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
                    null -> OutlinedButton(onClick = onApproveNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(tr("לאשר התראות", "Approve notifications"), fontSize = 20.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            false -> {
                Status("✕ " + tr("המיקרופון חסום", "Microphone blocked"), good = false)
                Body(tr("בלי מיקרופון אין כתוביות. אפשר לאשר בהגדרות הטלפון.", "Without a microphone there are no captions. It can be approved in the phone’s Settings."))
                OutlinedButton(onClick = onOpenPhoneSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(tr("פתיחת הגדרות הטלפון", "Open phone settings"), fontSize = 20.sp, textAlign = TextAlign.Center)
                }
            }
            null -> Button(onClick = onApproveMicrophone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(tr("לאשר את המיקרופון", "Approve the microphone"), fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The alert for her name only works once someone has typed the name in,
 * and that screen is three levels deep in Settings; here it's asked for
 * while the family member setting the phone up is still holding it.
 */
@Composable
private fun NamePage(settings: AppSettings, draft: MutableState<String>, addDraft: () -> Unit, onAddWord: (String) -> Unit) {
    Page("🔔", tr("כשקוראים לך", "When you’re called")) {
        Body(tr("כשמישהו אומר את השם שלך, הטלפון רוטט והשורה מסומנת, גם כשלא מסתכלים על המסך.", "When someone says your name, the phone vibrates and the line is highlighted, even when no one is looking at the screen."))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.value,
                onValueChange = { draft.value = it },
                placeholder = { Text(tr("השם שלך", "Your name")) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addDraft() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = addDraft, enabled = draft.value.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) {
                Text(tr("להוסיף", "Add"), fontSize = 18.sp)
            }
        }
        if (HebrewText.isInOtherLetters(draft.value, settings.languageCode)) {
            Text(
                tr("הכתוביות באותיות עבריות, ולכן מילה שנכתבה באותיות אחרות עלולה לא להימצא אף פעם.", "Captions are in Hebrew letters, so a word written in other letters may never be found."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (word in AlertSuggestions.names) {
                val added = settings.keywordAlerts.any { it.isEnabled && HebrewText.normalize(it.phrase) == HebrewText.normalize(word) }
                OutlinedButton(onClick = { onAddWord(word) }, enabled = !added, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text((if (added) "✓ " else "+ ") + AlertSuggestions.label(word, Localization.language), fontSize = 18.sp)
                }
            }
        }
        AlertSuggestions.vibrationNote(settings.keywordAlerts, Localization.language)?.let { Status("✓ $it", good = true) }
        Text(
            tr("אפשר להוסיף עוד מילים, או למחוק, בהגדרות ← התראות ← מילים חשובות.", "More words can be added or removed in Settings → Alerts → Important words."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Page(symbol: String, title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(symbol, fontSize = 64.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clearAndSetSemantics {})
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Point(symbol: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(symbol, fontSize = 26.sp, modifier = Modifier.widthIn(min = 36.dp).clearAndSetSemantics {})
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Status(text: String, good: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Dots(pager: PagerState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics {},
    ) {
        repeat(PAGES) { page ->
            val colour = if (page == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(8.dp).background(colour, CircleShape))
        }
    }
}

@Composable
private fun Footer(pager: PagerState, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        if (pager.currentPage < PAGES - 1) {
            // Bare text is too small a target beside Next, and a slip ends
            // the walkthrough.
            TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tr("דילוג", "Skip"), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.widthIn(min = 120.dp).heightIn(min = 56.dp)) {
                Text(tr("הבא", "Next"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(tr("להתחיל", "Start"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private const val PAGES = 5
private const val NAME_PAGE = 3
