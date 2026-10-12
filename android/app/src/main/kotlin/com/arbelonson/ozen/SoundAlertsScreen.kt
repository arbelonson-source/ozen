package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.SoundNearMisses
import com.arbelonson.ozen.core.tr
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
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenTitle(tr("צלילים בבית", "Sounds at home")) {
                TextButton(onClick = onClose) { Text(tr("חזרה", "Back")) }
            }
            SwitchRow(tr("התראות על צלילים", "Sound alerts"), preferences.isEnabled) { on -> onChange(preferences.copy(isEnabled = on)) }
            SectionHeader(tr("להתריע על", "Alert for"))
            for (importance in SoundEvent.Importance.entries.reversed()) {
                ChoiceRow(soundFloorName(importance), null, preferences.minimumImportance == importance, enabled = preferences.isEnabled) {
                    onChange(preferences.copy(minimumImportance = importance))
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
            for ((title, vibration) in samples) {
                TextButton(onClick = { onFeel(vibration) }, modifier = Modifier.fillMaxWidth()) { Text(title, modifier = Modifier.fillMaxWidth()) }
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
                for (event in events) SoundRow(event, preferences, enabled, misses[event], onChange)
            }
        }
    }
}

@Composable
private fun SoundRow(
    event: SoundEvent,
    preferences: SoundAlertPreferences,
    enabled: Boolean,
    nearMiss: SoundNearMisses.Entry?,
    onChange: (SoundAlertPreferences) -> Unit,
) {
    val on = !preferences.isMuted(event.identifier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch) { onChange(preferences.withMuted(event.identifier, !it)) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(soundSymbol(event.systemImage), fontSize = 22.sp, modifier = Modifier.width(40.dp).clearAndSetSemantics { })
        Text(event.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
    // Offered after a near miss, and kept while it is on: a sound marked
    // sensitive stops being a near miss, and if the lower floor then brings
    // false alarms she needs the way back without turning the sound off.
    val sensitive = preferences.isSensitive(event.identifier)
    if (on && (nearMiss != null || sensitive)) {
        Footnote(
            if (nearMiss != null) {
                tr("נשמע ב-%1%, קצת חלש מדי", "Heard at %1%, a bit too faint", listOf("${(nearMiss.bestConfidence * 100).roundToInt()}"))
            } else {
                tr("מתריע גם על צליל חלש. הקישו כדי לבטל.", "Alerts on a faint sound too. Tap to undo.")
            },
        )
        SwitchRow(tr("להתריע גם על צליל חלש יותר", "Alert on a fainter sound too"), sensitive, enabled) { onChange(preferences.withSensitive(event.identifier, it)) }
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
