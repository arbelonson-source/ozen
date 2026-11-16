package com.arbelonson.ozen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.OzenSpacing

/**
 * What starting sounds-only for the night looks at, with the words of the
 * Night alerts screen: the phone's battery rules, Do Not Disturb, and the
 * Emergency channel being off or silent. Listening starts whatever it finds.
 */
object NightStartCheck {
    enum class Problem { Battery, NotificationsOff, EmergencyOff, EmergencySilent, DoNotDisturb }

    fun problems(batteryExempt: Boolean, dndThrough: Boolean, alarmsAllowed: Boolean, alarmsPostable: Boolean, notificationsAllowed: Boolean = true): List<Problem> = buildList {
        if (!batteryExempt) add(Problem.Battery)
        if (!notificationsAllowed) {
            add(Problem.NotificationsOff)
        } else if (!alarmsAllowed) {
            add(if (alarmsPostable) Problem.EmergencySilent else Problem.EmergencyOff)
        } else if (!dndThrough) {
            add(Problem.DoNotDisturb)
        }
    }

    /** Once per start; and not for a problem set she already closed, while it is unchanged or smaller. */
    fun toShow(found: List<Problem>, dismissed: Set<Problem>): List<Problem> =
        if (found.any { it !in dismissed }) found else emptyList()

    /** A closed problem that has since been fixed is forgotten, so it shows again if it comes back. */
    fun stillDismissed(dismissed: List<Problem>, found: List<Problem>): List<Problem> = dismissed.filter { it in found }

    /** A card up while she fixes things in Settings: what is fixed leaves it. */
    fun stillOpen(shown: List<Problem>, found: List<Problem>): List<Problem> = shown.filter { it in found }

    fun encode(list: Collection<Problem>): String = list.joinToString(",") { it.name }

    fun decode(text: String): List<Problem> = text.split(",").mapNotNull { name -> Problem.entries.firstOrNull { it.name == name } }

    fun title(): String = tr("לפני שהולכים לישון", "Before you sleep")

    fun check(context: android.content.Context): List<Problem> = problems(
        NightAlerts.batteryExempt(context),
        AlertNotifier.alarmsOverrideDoNotDisturb(context),
        AlertNotifier.alarmsAllowed(context),
        AlertNotifier.alarmsPostable(context),
        AlertNotifier.allowed(context),
    )

    fun text(problem: Problem, phoneLabel: (String) -> String? = { null }): String = when (problem) {
        Problem.Battery -> NightAlerts.batteryText(false, phoneLabel = phoneLabel)
        Problem.NotificationsOff -> tr("ההתראות של אוזן כבויות בהגדרות הטלפון, ולכן כשהמסך כבוי לא תגיע שום התראה.", "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off.")
        Problem.DoNotDisturb -> NightAlerts.dndText(false, alarmsAllowed = true, phoneLabel = phoneLabel)
        Problem.EmergencySilent -> NightAlerts.dndText(false, alarmsAllowed = false, phoneLabel = phoneLabel, alarmsSilent = true)
        Problem.EmergencyOff -> NightAlerts.dndText(false, alarmsAllowed = false, phoneLabel = phoneLabel)
    }
}

/** Shown when sounds-only starts; dismissible, and it never blocks listening. */
@Composable
internal fun NightStartCard(soundsOnly: Boolean) {
    val context = LocalContext.current
    var wasOn by rememberSaveable { mutableStateOf(soundsOnly) }
    var shownText by rememberSaveable { mutableStateOf("") }
    var dismissedText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(soundsOnly) {
        if (soundsOnly && !wasOn) {
            val found = NightStartCheck.check(context)
            if (found.isEmpty()) dismissedText = ""
            shownText = NightStartCheck.encode(NightStartCheck.toShow(found, NightStartCheck.decode(dismissedText).toSet()))
        }
        if (!soundsOnly) shownText = ""
        wasOn = soundsOnly
    }
    LifecycleResumeEffect(Unit) {
        val found = NightStartCheck.check(context)
        dismissedText = NightStartCheck.encode(NightStartCheck.stillDismissed(NightStartCheck.decode(dismissedText), found))
        if (shownText.isNotEmpty()) shownText = NightStartCheck.encode(NightStartCheck.stillOpen(NightStartCheck.decode(shownText), found))
        onPauseOrDispose { }
    }
    val shown = NightStartCheck.decode(shownText)
    val phoneLabel = remember(context) { { name: String -> SystemLabels.lookup(context, name) } }
    val texts = shown.map { NightStartCheck.text(it, phoneLabel) }
    AnnounceWhenShown(shown.isNotEmpty(), (listOf(NightStartCheck.title()) + texts).joinToString(" "))
    ArrivingBanner(shown.takeIf { it.isNotEmpty() }) { problems ->
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHigh)
                .padding(OzenSpacing.Page).semantics { liveRegion = LiveRegionMode.Polite }.testTag("night-start-card"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(NightStartCheck.title(), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            problems.forEach { problem ->
                Text(NightStartCheck.text(problem, phoneLabel), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                val (label, open) = when (problem) {
                    NightStartCheck.Problem.Battery -> tr("פתחו את הגדרות הסוללה", "Open the battery list") to { NightAlerts.openBatteryList(context) }
                    NightStartCheck.Problem.NotificationsOff -> tr("פתיחת הגדרות הטלפון", "Open the phone’s settings") to { openNotificationSettings(context) }
                    else -> tr("פתחו את הגדרות החירום", "Open the Emergency settings") to { AlertNotifier.openAlarmSettings(context) }
                }
                TextButton(onClick = open) { Text(label) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        dismissedText = NightStartCheck.encode((NightStartCheck.decode(dismissedText) + problems).toSet())
                        shownText = ""
                    },
                    modifier = Modifier.widthIn(min = OzenSpacing.MinTouch).testTag("night-start-dismiss"),
                ) { Text(tr("אישור", "OK")) }
            }
        }
    }
}
