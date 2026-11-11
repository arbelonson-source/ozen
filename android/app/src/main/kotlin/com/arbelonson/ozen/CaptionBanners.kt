package com.arbelonson.ozen

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.HebrewTime
import com.arbelonson.ozen.core.KeywordPill
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.RecentConversation
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.DialogText
import com.arbelonson.ozen.ui.IconBadge
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.PrimaryButton
import kotlinx.coroutines.launch

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
internal fun SavingTroubleBanner(onDismiss: () -> Unit) {
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
internal fun PhoneSoundStoppedBanner(onDismiss: () -> Unit) {
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
internal fun BatteryBanner(warning: BatteryWarning, onDismiss: () -> Unit) {
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
internal fun MicrophoneDropBanner(title: String, detail: String, onChooseMicrophone: () -> Unit, onDismiss: () -> Unit) {
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
internal fun NameAlertOffer(look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
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
internal fun LighterModelOffer(model: PhoneModel, waiting: Boolean, inUseBytes: Long?, onSwitch: () -> Unit, onDismiss: () -> Unit) {
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
internal fun ModelLoadCrashCard(model: PhoneModel, lighter: PhoneModel, inUseBytes: Long?, switching: PhoneModelSwitch?, onSwitch: () -> Unit, onRetry: () -> Unit) {
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
internal fun ListeningCutCard(text: String, onDismiss: () -> Unit) {
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
internal fun RecentConversationCard(recent: TranscriptSessionSummary, look: CaptionTheme, onOpen: () -> Unit, onDismiss: () -> Unit) {
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
 * "Said: grandma" at the top, as the iPhone's pill, with who said it when
 * the line's voice is known: five seconds, longer at the largest text sizes
 * (see `KeywordPill`). The screen reader reads it as it appears.
 */
@Composable
internal fun HeardPill(text: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh).padding(horizontal = OzenSpacing.Page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(Icons.Rounded.Hearing)
        Text(
            text,
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
internal fun <T : Any> ArrivingBanner(shown: T?, content: @Composable (T) -> Unit) {
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

internal val PhoneModelSwitch.downloadingFile: String?
    get() = (this as? PhoneModelSwitch.Downloading)?.file
