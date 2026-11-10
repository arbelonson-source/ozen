package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Microwave
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Plumbing
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.SoundNearMisses
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.SettingRow
import com.arbelonson.ozen.ui.SettingSwitchRow
import com.arbelonson.ozen.ui.SettingsGroup
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * "Sounds at home", as on the iPhone: whether sounds alert at all, how
 * important a sound must be, each kind of buzz to feel, and each sound on
 * its own. The model the app carries knows every sound in the catalog, so
 * none is shown as unsupported here.
 */
@Composable
internal fun SoundAlertsScreen(
    preferences: SoundAlertPreferences,
    onChange: (SoundAlertPreferences) -> Unit,
    nearMiss: (SoundEvent) -> SoundNearMisses.Entry?,
    onFeel: (AlertVibration) -> Unit,
    onClose: () -> Unit,
    flashLight: Boolean = false,
    onFlashLight: (Boolean) -> Unit = {},
    onTryLight: (refused: () -> Unit) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    // Sounds go on being heard while the page is open, and one heard too
    // faintly shows its note at once, as on the iPhone; the list they are
    // kept in is no state the screen can watch.
    val misses by produceState(SoundEventCatalog.listed.associateWith(nearMiss)) {
        while (true) {
            delay(1_000)
            value = SoundEventCatalog.listed.associateWith(nearMiss)
        }
    }
    SettingsPageFrame(tr("צלילים בבית", "Sounds at home"), onClose = onClose, closeLabel = tr("חזרה", "Back")) {
        SettingsGroup(dividerInset = 56.dp) {
            item {
                SettingSwitchRow(tr("התראות על צלילים", "Sound alerts"), preferences.isEnabled, { on -> onChange(preferences.copy(isEnabled = on)) }, icon = Icons.Rounded.NotificationsActive)
            }
        }
        SectionHeader(tr("להתריע על", "Alert for"))
        SettingsGroup(modifier = Modifier.selectableGroup()) {
            for (importance in SoundEvent.Importance.entries.reversed()) {
                item {
                    ChoiceRow(soundFloorName(importance), null, preferences.minimumImportance == importance, enabled = preferences.isEnabled) {
                        onChange(preferences.copy(minimumImportance = importance))
                    }
                }
            }
        }
        Footnote(SoundAlertsText.safetyNote(Localization.language))
        Footnote(
            tr(
                "הזיהוי נעשה בטלפון בלבד, על אותו קול שמשמש לכתוביות. אותו צליל לא יופיע שוב במשך 20 שניות.",
                "Detection happens only on the phone, using the same audio as the captions. The same sound won’t appear again for 20 seconds.",
            ),
        )
        LetAlarmsThroughDoNotDisturb()
        CameraLightRows(preferences.isEnabled, flashLight, onFlashLight, onTryLight)

        // Each kind of alert buzzes differently, which only helps once
        // she knows which is which.
        SectionHeader(tr("הרטט של כל התראה", "How each alert feels"))
        val samples = listOf(
            Triple(tr("חירום: אזעקה, גלאי עשן", "Emergency: alarm, smoke detector"), AlertVibration.pattern(SoundEvent.Importance.Critical), Icons.Rounded.LocalFireDepartment),
            Triple(tr("חשוב: פעמון בדלת, דפיקה בדלת, בכי של תינוק", "Important: doorbell, knock at the door, baby crying"), AlertVibration.pattern(SoundEvent.Importance.High), Icons.Rounded.DoorFront),
            Triple(tr("צליל אחר בבית", "Other sound at home"), AlertVibration.pattern(SoundEvent.Importance.Medium), Icons.Rounded.Home),
            Triple(tr("מילה מהרשימה, כמו השם שלכם", "A word from the list, like your name"), AlertVibration.keyword, Icons.Rounded.NotificationsActive),
            Triple(tr("מישהו מתחיל לדבר אחרי שקט", "Someone starts speaking after silence"), AlertVibration.speechResumed, Icons.Rounded.RecordVoiceOver),
        )
        SettingsGroup(dividerInset = 56.dp) {
            for ((title, vibration, icon) in samples) {
                item { SettingRow(title, icon = icon, onClick = { onFeel(vibration) }, trailing = { Icon(Icons.Rounded.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
            }
        }
        Footnote(
            tr(
                "כשהאפליקציה פתוחה, כל סוג התראה רוטט אחרת, כך שאפשר לדעת מה קרה גם בלי להסתכל. הקישו כדי לנסות.",
                "When the app is open, each kind of alert vibrates differently, so you can tell what happened without looking. Tap to feel it.",
            ),
        )

        for (importance in SoundEvent.Importance.entries.reversed()) {
            val events = SoundEventCatalog.listed.filter { it.importance == importance }
            if (events.isEmpty()) continue
            SectionHeader(soundGroupName(importance))
            val enabled = preferences.isEnabled && importance >= preferences.minimumImportance
            SettingsGroup(dividerInset = 56.dp) {
                for (event in events) soundRows(event, preferences, enabled, misses[event], onChange)
            }
        }
    }
}

/**
 * An alarm alert already comes through Do Not Disturb as an alarm, unless
 * alarms are silenced there too; letting its channel override Do Not
 * Disturb is the one sure way, and only the phone's settings can do it,
 * as the iPhone's Focus note says for its own. Checked again on coming back.
 */
@Composable
private fun LetAlarmsThroughDoNotDisturb() {
    val context = LocalContext.current
    var through by remember { mutableStateOf(AlertNotifier.alarmsOverrideDoNotDisturb(context)) }
    LifecycleResumeEffect(Unit) {
        through = AlertNotifier.alarmsOverrideDoNotDisturb(context)
        onPauseOrDispose { }
    }
    if (through) return
    SettingsGroup(dividerInset = 56.dp) {
        item { SettingRow(tr("התראות חירום גם ב״נא לא להפריע״", "Emergency alerts in Do Not Disturb"), icon = Icons.Rounded.DoNotDisturbOn, onClick = { AlertNotifier.openAlarmSettings(context) }, trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
    }
    Footnote(
        tr(
            "כדי שאזעקה או גלאי עשן יעירו אתכם גם ב״נא לא להפריע״ או במצב שינה: הקישו למעלה ואפשרו להתראות ״חירום״ של אוזן לעקוף את ״נא לא להפריע״.",
            "So an alarm or smoke detector still wakes you in Do Not Disturb or a sleep mode: tap above and let Ozen’s Emergency alerts override Do Not Disturb.",
        ),
    )
}

/**
 * The camera light, Android only: it blinks for an emergency or the door
 * while Ozen listens away from the screen (see `TorchFlash`). A phone
 * without one says so instead, and the phone's own flash for every
 * notification is pointed to either way, since one of the two is enough.
 */
@Composable
private fun CameraLightRows(soundsOn: Boolean, on: Boolean, onChange: (Boolean) -> Unit, onTry: (refused: () -> Unit) -> Unit) {
    val context = LocalContext.current
    var available by remember { mutableStateOf(CameraLight.isAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        available = CameraLight.isAvailable(context)
        onPauseOrDispose { }
    }
    var inUse by remember { mutableStateOf(false) }
    SettingsGroup(dividerInset = 56.dp) {
        if (!available) {
            item { SettingRow(tr("בטלפון הזה אין פנס מצלמה", "This phone has no camera light"), icon = Icons.Rounded.FlashlightOff) }
            return@SettingsGroup
        }
        item {
            SettingSwitchRow(
                tr("הבהוב בפנס המצלמה", "Flash the camera light"),
                on,
                { checked ->
                    inUse = false
                    onChange(checked)
                },
                icon = Icons.Rounded.FlashlightOn,
                enabled = soundsOn,
            )
        }
        if (on) {
            item {
                SettingRow(tr("לנסות", "Try it"), icon = Icons.Rounded.FlashOn, enabled = soundsOn, onClick = {
                    inUse = false
                    if (ScreenReader.isOn(context)) ScreenReader.announce(context, tr("פנס המצלמה מהבהב", "Flashing the camera light"))
                    onTry { inUse = true }
                })
            }
            if (inUse) {
                item {
                    Text(
                        tr("פנס המצלמה בשימוש כרגע. אפשר לנסות שוב אחרי סגירת המצלמה.", "The camera light is in use right now. Try again after closing the camera."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    if (available) {
        Footnote(
            tr(
                "בזמן שאוזן מאזינה והטלפון נעול או שאוזן לא מוצגת על המסך, פנס המצלמה מהבהב כ־10 שניות כשיש התראה על צליל חירום או צליל חשוב: הבהובים ארוכים במקרה חירום, ושניים קצרים לדלת. בשעות השקטות הפנס מהבהב רק במקרה חירום. עובד הכי טוב כשהטלפון מונח עם המסך כלפי מטה. ההבהוב עלול להעיר גם אנשים אחרים בחדר.",
                "While Ozen is listening and the phone is locked or Ozen isn’t on screen, the camera light blinks for about 10 seconds when an emergency or important sound alerts: long flashes for an emergency, two short ones for the door. During quiet hours, only emergencies flash. Works best with the phone face down. The light can wake others in the room too.",
            ),
        )
    }
    Footnote(
        tr(
            "בחלק מהטלפונים אפשר להפעיל הבהוב גם לכל התראה: חפשו הגדרת הבהוב בהגדרות הנגישות של הטלפון. אם שתיהן פועלות, ההבהובים עלולים להתערבב, ולכן מספיקה אחת מהן.",
            "Some phones can also flash for every notification: look for a flash setting in the phone’s Accessibility settings. If both are on, the flashes can mix, so one is enough.",
        ),
    )
}

private fun com.arbelonson.ozen.ui.SettingsGroupScope.soundRows(
    event: SoundEvent,
    preferences: SoundAlertPreferences,
    enabled: Boolean,
    nearMiss: SoundNearMisses.Entry?,
    onChange: (SoundAlertPreferences) -> Unit,
) {
    val on = !preferences.isMuted(event.identifier)
    // Offered after a near miss, and kept while it is on: a sound marked
    // sensitive stops being a near miss, and if the lower floor then brings
    // false alarms she needs the way back without turning the sound off.
    val sensitive = preferences.isSensitive(event.identifier)
    val offered = on && (nearMiss != null || sensitive)
    item {
        SettingSwitchRow(event.name, on, { onChange(preferences.withMuted(event.identifier, !it)) }, icon = soundIcon(event.systemImage), enabled = enabled)
    }
    if (offered) {
        item {
            Text(
                if (nearMiss != null) {
                    tr("נשמע ב־%1%, קצת חלש מדי", "Heard at %1%, a bit too faint", listOf("${(nearMiss.bestConfidence * 100).roundToInt()}"))
                } else {
                    tr("יש התראה גם על צליל חלש. הקישו כדי לבטל.", "Alerts on a faint sound too. Tap to undo.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        item {
            SettingSwitchRow(tr("להתריע גם על צליל חלש יותר", "Alert on a fainter sound too"), sensitive, { onChange(preferences.withSensitive(event.identifier, it)) }, enabled = enabled)
        }
    }
}

internal fun soundFloorName(importance: SoundEvent.Importance): String = when (importance) {
    SoundEvent.Importance.Critical -> tr("חירום בלבד", "Emergency only")
    SoundEvent.Importance.High -> tr("חשוב ומעלה", "Important and above")
    SoundEvent.Importance.Medium -> tr("צלילי בית ומעלה", "Home sounds and above")
    SoundEvent.Importance.Low -> tr("הכול", "Everything")
}

private fun soundGroupName(importance: SoundEvent.Importance): String = when (importance) {
    SoundEvent.Importance.Critical -> tr("חירום", "Emergency")
    SoundEvent.Importance.High -> tr("חשוב", "Important")
    SoundEvent.Importance.Medium -> tr("בבית", "At home")
    SoundEvent.Importance.Low -> tr("רקע", "Background")
}
