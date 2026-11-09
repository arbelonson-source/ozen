package com.arbelonson.ozen

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.SettingsGroup

/**
 * What has to hold for an alert to come in the night: Sounds at home on and
 * Ozen left listening, then what the phone itself can do to stop it: its
 * battery rules, its maker's battery manager, Do Not Disturb, and silent
 * mode. An app can't change the phone's rules for itself, so each is checked
 * where it can be, said plainly, and led to the phone's own page. Checked
 * again on coming back, where she may just have changed one. Notifications
 * turned off in the phone stop all of it, so that comes first.
 */
@Composable
internal fun NightAlertsScreen(soundsOn: Boolean, onSounds: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var battery by remember { mutableStateOf(NightAlerts.batteryExempt(context)) }
    var dnd by remember { mutableStateOf(AlertNotifier.alarmsOverrideDoNotDisturb(context)) }
    var alarmsAllowed by remember { mutableStateOf(AlertNotifier.alarmsAllowed(context)) }
    var blocked by remember { mutableStateOf(!AlertNotifier.allowed(context)) }
    var sent by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        battery = NightAlerts.batteryExempt(context)
        dnd = AlertNotifier.alarmsOverrideDoNotDisturb(context)
        alarmsAllowed = AlertNotifier.alarmsAllowed(context)
        blocked = !AlertNotifier.allowed(context)
        onPauseOrDispose { }
    }
    val maker = NightAlerts.makerAdvice(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
    SettingsPageFrame(tr("התראות במהלך הלילה", "Alerts through the night"), onClose = onClose, closeLabel = tr("חזרה", "Back")) {
        SettingsGroup(dividerInset = 56.dp) {
            if (blocked) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(
                            tr("ההתראות של אוזן כבויות בהגדרות הטלפון, ולכן כשהמסך כבוי לא תגיע שום התראה.", "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off."),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        TextButton(onClick = { openNotificationSettings(context) }) { Text(tr("פתיחת הגדרות הטלפון", "Open the phone’s settings")) }
                    }
                }
            }
            item {
                Step(
                    done = soundsOn,
                    title = tr("צלילים בבית", "Sounds at home"),
                    text = NightAlerts.soundsText(soundsOn),
                    button = if (soundsOn) null else tr("פתחו את ״צלילים בבית״", "Open Sounds at home"),
                    onButton = onSounds,
                )
            }
            item {
                Step(
                    done = battery,
                    title = tr("סוללה", "Battery"),
                    text = NightAlerts.batteryText(battery),
                    button = if (battery) null else tr("פתחו את רשימת הסוללה", "Open the battery list"),
                    onButton = { NightAlerts.openBatteryList(context) },
                )
            }
            if (maker != null) {
                item {
                    Step(
                        done = null,
                        title = tr("מנהל הסוללה של יצרן הטלפון", "The phone maker’s battery manager"),
                        text = maker,
                        button = tr("פתחו את פרטי האפליקציה של אוזן", "Open Ozen’s app info"),
                        onButton = { NightAlerts.openAppInfo(context) },
                    )
                }
            }
            item {
                Step(
                    done = dnd,
                    title = tr("צלילים דחופים ב״נא לא להפריע״", "Urgent sounds in Do Not Disturb"),
                    text = NightAlerts.dndText(dnd, alarmsAllowed),
                    button = if (dnd) null else tr("פתחו את הגדרות החירום", "Open the Emergency settings"),
                    onButton = { AlertNotifier.openAlarmSettings(context) },
                )
            }
            item {
                Step(
                    done = null,
                    title = tr("מצב שקט וטעינה", "Silent mode and charging"),
                    text = tr(
                        "במצב שקט גם הרטט של ההתראות מושתק, אלא אם הן מגיעות דרך ״נא לא להפריע״. השאירו את הטלפון במצב רטט, בטעינה וקרוב אליכם.",
                        "On silent, alerts don’t vibrate either, unless they come through Do Not Disturb. Keep the phone on vibrate, charging, near you.",
                    ),
                    button = null,
                    onButton = { },
                )
            }
            if (!blocked) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TestAlertButton(sent, BackgroundAlertPolicy.nightTestNotification) { sent = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(done: Boolean?, title: String, text: String, button: String?, onButton: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val icon: ImageVector = when (done) {
            true -> Icons.Rounded.CheckCircle
            false -> Icons.Rounded.ErrorOutline
            null -> Icons.Rounded.Info
        }
        val label = when (done) {
            true -> tr("הושלם", "Completed")
            false -> tr("דורש בדיקה", "Needs checking")
            null -> null
        }
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null) TextButton(onClick = onButton) { Text(button) }
        }
    }
}
