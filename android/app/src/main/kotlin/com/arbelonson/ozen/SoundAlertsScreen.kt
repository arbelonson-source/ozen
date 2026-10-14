package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoorFront
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
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.AlertVibration
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
        Footnote(
            tr(
                "הזיהוי נעשה בטלפון בלבד, על אותו אודיו שמשמש לכתוביות. אותו צליל לא יופיע שוב במשך 20 שניות.",
                "Detection happens only on the phone, using the same audio as the captions. The same sound won’t appear again for 20 seconds.",
            ),
        )

        // Each kind of alert buzzes differently, which only helps once
        // she knows which is which.
        SectionHeader(tr("איך כל התראה מרגישה", "How each alert feels"))
        val samples = listOf(
            tr("חירום: אזעקה, גלאי עשן", "Emergency: alarm, smoke detector") to AlertVibration.pattern(SoundEvent.Importance.Critical),
            tr("חשוב: פעמון, דפיקה בדלת, בכי של תינוק", "Important: doorbell, knock at the door, baby crying") to AlertVibration.pattern(SoundEvent.Importance.High),
            tr("צליל אחר בבית", "Other sound at home") to AlertVibration.pattern(SoundEvent.Importance.Medium),
            tr("מילה מהרשימה, כמו השם שלך", "A word from the list, like your name") to AlertVibration.keyword,
            tr("מישהו מתחיל לדבר אחרי שקט", "Someone starts speaking after silence") to AlertVibration.speechResumed,
        )
        SettingsGroup(dividerInset = 56.dp) {
            for ((title, vibration) in samples) {
                item { SettingRow(title, icon = Icons.Rounded.Vibration, onClick = { onFeel(vibration) }) }
            }
        }
        Footnote(
            tr(
                "כשהאפליקציה פתוחה, כל סוג התראה רוטט אחרת, כך שאפשר לדעת מה קרה גם בלי להסתכל. הקישו כדי להרגיש.",
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
                    tr("נשמע ב-%1%, קצת חלש מדי", "Heard at %1%, a bit too faint", listOf("${(nearMiss.bestConfidence * 100).roundToInt()}"))
                } else {
                    tr("מתריע גם על צליל חלש. הקישו כדי לבטל.", "Alerts on a faint sound too. Tap to undo.")
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
    SoundEvent.Importance.Critical -> tr("רק חירום", "Emergency only")
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
