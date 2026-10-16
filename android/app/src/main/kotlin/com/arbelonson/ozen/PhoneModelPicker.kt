package com.arbelonson.ozen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.PhoneModelEstimate
import com.arbelonson.ozen.core.PhoneModelFit
import com.arbelonson.ozen.core.PhoneModelShelf
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.SettingsGroup
import com.arbelonson.ozen.ui.TonalButton
import kotlinx.coroutines.launch

@Composable
internal fun PhoneModelPicker(settings: AppSettings, models: PhoneModels, chooseBestAfterTest: Boolean) {
    val installed by models.installed.collectAsStateWithLifecycle()
    val partial by models.partial.collectAsStateWithLifecycle()
    val switching by models.switching.collectAsStateWithLifecycle()
    val check by models.check.collectAsStateWithLifecycle()
    val busy by models.captionsBusy.collectAsStateWithLifecycle()
    val memory = models.totalMemoryBytes
    val language = settings.spokenLanguage
    val speed = settings.phoneSpeed?.gflops
    val chosen = (switching.fileOrNull ?: settings.phoneModel.file)
    val best = speed?.let { PhoneModelEstimate.recommend(language, it, memory) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PhoneTest(check, tested = speed != null, captionsBusy = busy) { models.runCheck(chooseBestAfterTest) }
        SettingsGroup(modifier = Modifier.selectableGroup().testTag("phone-model-list")) {
            val hidden = PhoneModelShelf.notOffered(language, installed, partial)
            val downloading = (switching as? PhoneModelSwitch.Downloading)?.file
            for (model in PhoneModelCatalog.offered(language) + hidden) {
                item {
                    val deletable = PhoneModelShelf.canDelete(model.file, installed, partial, model.file == settings.phoneModel.file, downloading)
                    ModelRow(model, language, speed, memory, best, model.file == chosen, model.file in installed, partial[model.file], model !in hidden, deletable, switching, onDelete = { models.delete(model.file) }) { models.choose(model.file) }
                }
            }
        }
        val picked = PhoneModelCatalog.effective(language, chosen)
        if (speed != null && PhoneModelEstimate.fit(picked, speed, memory) != PhoneModelFit.KeepsUp) {
            Caution(tr("בטלפון הזה המודל הזה עלול לפגר אחרי הדיבור. אפשר להשתמש בו בכל זאת.", "On this phone this model may lag behind the speech. You can still use it."))
        }
        SwitchNote(switching)
    }
}

private val PhoneModelSwitch.fileOrNull: String?
    get() = when (this) {
        PhoneModelSwitch.Idle -> null
        is PhoneModelSwitch.Downloading -> file
        is PhoneModelSwitch.NotEnoughRoom -> file
        is PhoneModelSwitch.WaitingForWiFi -> file
        is PhoneModelSwitch.Offline -> file
        is PhoneModelSwitch.Failed -> file
    }

@Composable
private fun PhoneTest(check: PhoneCheckState, tested: Boolean, captionsBusy: Boolean, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
        val running = check as? PhoneCheckState.Running
        val label = if (tested) tr("בדיקה חוזרת של הטלפון", "Test this phone again") else tr("בדיקת הטלפון הזה", "Test this phone")
        TonalButton(label, onTest, Modifier.fillMaxWidth().testTag("phone-test"), icon = Icons.Rounded.Speed, enabled = running == null && !captionsBusy)
        if (running != null) {
            val percent = (running.fraction * 100).toInt()
            val text = tr("בודק את הטלפון… %1%", "Testing this phone… %1%", listOf("$percent"))
            LinearProgressIndicator(progress = { running.fraction }, modifier = Modifier.fillMaxWidth())
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (check == PhoneCheckState.Failed) {
            Warning(tr("הבדיקה לא הצליחה. אפשר לנסות שוב.", "The test could not run. Try again."))
        } else if (captionsBusy) {
            Text(
                tr("כדי לבדוק את הטלפון הזה, עוצרים את הכתוביות. הבדיקה צריכה את כל כוחו.", "Stop the captions to test this phone. The test needs all of its power."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                tr("הבדיקה לוקחת כעשר שניות ולא מורידה שום דבר.", "The test takes about ten seconds and downloads nothing."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: PhoneModel,
    language: UILanguage,
    speed: Double?,
    memory: Long?,
    best: PhoneModel?,
    selected: Boolean,
    onPhone: Boolean,
    leftoverBytes: Long?,
    choosable: Boolean,
    deletable: Boolean,
    switching: PhoneModelSwitch,
    onDelete: suspend () -> PhoneModelDeletion.Result,
    onSelect: () -> Unit,
) {
    val tag = when {
        best?.file == model.file -> tr("מומלץ לטלפון הזה", "Recommended for this phone")
        best == null && model.file == PhoneModelCatalog.recommended(language).file -> tr("מומלץ", "Recommended")
        else -> null
    }
    val progress = (switching as? PhoneModelSwitch.Downloading)?.takeIf { it.file == model.file }
    var asking by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = choosable, onClick = onSelect, role = Role.RadioButton)
            .testTag("phone-model-" + model.file)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            tag?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            val place = when {
                progress != null -> tr("מוריד… %1%", "Downloading… %1%", listOf("${(progress.fraction * 100).toInt()}"))
                onPhone -> tr("כבר בטלפון", "Already on the phone")
                leftoverBytes != null -> tr("הורדה שלא הושלמה, %1 בטלפון", "Unfinished download, %1 on the phone", listOf(ByteSize.text(leftoverBytes)))
                else -> tr("הורדה של %1", "Download of %1", listOf(ByteSize.text(model.bytes)))
            }
            val hebrewOnly = if (model.isHebrewOnly && choosable) " · " + tr("עברית בלבד", "Hebrew only") else ""
            Text(place + hebrewOnly, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!choosable) {
                Text(tr("מתאים לעברית בלבד, ולכן אי אפשר לבחור בו בשפה הזו.", "For Hebrew only, so it can't be chosen in this language."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("phone-model-hebrew-only-note-" + model.file))
            }
            Text(model.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (choosable) Rating(if (language == UILanguage.Hebrew) tr("עברית", "Hebrew") else tr("דיוק", "Accuracy"), model.quality(language))
            if (choosable) speed?.let { FitLabel(PhoneModelEstimate.fit(model, it, memory)) }
        }
        RadioButton(selected = selected, onClick = null, enabled = choosable, modifier = Modifier.padding(12.dp))
    }
    if (deletable) {
        val action = tr("מחיקה של %1", "Delete %1", listOf(model.displayName))
        TextButton(
            onClick = { asking = true },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("phone-model-delete-" + model.file).semantics { contentDescription = action },
        ) { Text(tr("מחיקה", "Delete")) }
    }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("למחוק את %1 מהטלפון?", "Delete %1 from the phone?", listOf(model.displayName))) },
            text = { Text(tr("אפשר להוריד אותו שוב בכל עת.", "It can be downloaded again anytime.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        scope.launch { if (onDelete() == PhoneModelDeletion.Result.Failed) failed = true }
                    },
                    modifier = Modifier.testTag("phone-model-delete-confirm"),
                ) { Text(tr("מחיקה", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text(tr("המחיקה נכשלה", "Delete failed")) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(tr("אישור", "OK")) } },
        )
    }
}

@Composable
private fun Rating(label: String, value: Int) {
    val spoken = tr("%1 %2 מתוך 5", "%1 %2 out of 5", listOf(label, "$value"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("●".repeat(value) + "○".repeat(5 - value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FitLabel(fit: PhoneModelFit) {
    when (fit) {
        PhoneModelFit.KeepsUp -> Text(tr("עומד בקצב", "Keeps up"), style = MaterialTheme.typography.labelLarge, color = savedGreen())
        PhoneModelFit.ALittleBehind -> Text(tr("מפגר קצת", "A little behind"), style = MaterialTheme.typography.labelLarge, color = cautionOrange())
        PhoneModelFit.TooSlow -> Text(tr("איטי מדי בטלפון הזה", "Too slow on this phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SwitchNote(switching: PhoneModelSwitch) {
    val text = when (switching) {
        PhoneModelSwitch.Idle -> null
        is PhoneModelSwitch.Downloading -> tr("הכתוביות ממשיכות עם המודל הנוכחי עד שהחדש יהיה מוכן.", "Captions keep using the current model until the new one is ready.")
        is PhoneModelSwitch.NotEnoughRoom -> tr("אין מקום להורדה. צריך לפנות עוד %1 MB.", "There is no room to download it. Free up %1 MB more.", listOf("${switching.megabytes}"))
        is PhoneModelSwitch.WaitingForWiFi -> tr("ממתין ל‑Wi‑Fi כדי להוריד. אפשר לאשר הורדה בגלישה סלולרית בהגדרות.", "Waiting for Wi‑Fi to download it. Downloads on cellular data can be allowed in Settings.")
        is PhoneModelSwitch.Offline -> tr("אין חיבור לאינטרנט להורדה.", "There is no internet connection to download it.")
        is PhoneModelSwitch.Failed -> tr("ההורדה נכשלה. בחרו את המודל שוב כדי לנסות שוב.", "The download failed. Choose the model again to try again.")
    }
    text?.let {
        val cautious = switching !is PhoneModelSwitch.Downloading
        if (cautious) Caution(it) else Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
