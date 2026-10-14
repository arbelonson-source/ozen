package com.arbelonson.ozen

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.Plumbing
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalPolice
import androidx.compose.material.icons.rounded.Masks
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Microwave
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.arbelonson.ozen.ui.IconBadge
import com.arbelonson.ozen.ui.OzenSpacing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AlertFlash
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.delay

@Composable
internal fun SoundAlertBanner(alert: SoundAlert, onDismiss: () -> Unit) {
    val critical = alert.event.importance == SoundEvent.Importance.Critical
    NoticeBanner(
        soundIcon(alert.event.systemImage),
        soundTint(alert.event.importance),
        alert.event.name,
        if (critical) tr("שימו לב!", "Attention!") else tr("נשמע עכשיו", "Heard just now"),
        Modifier
            .shadow(4.dp, MaterialTheme.shapes.large)
            .clickable(onClickLabel = tr("סגירה", "Close"), role = Role.Button, onClick = onDismiss)
            .semantics { contentDescription = tr("התראה: %1", "Alert: %1", listOf(alert.event.name)) },
        large = true,
    ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.8f)) }
}

/**
 * A rounded notice tinted by how urgent it is, white words on [tint]: an
 * icon in a soft circle, a title and a line under it, and an optional
 * [trailing] mark. Its clicking and TalkBack words come in on [modifier].
 */
@Composable
internal fun NoticeBanner(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(tint).then(modifier).padding(horizontal = OzenSpacing.Page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, size = if (large) 48.dp else 40.dp, container = Color.White.copy(alpha = 0.2f), content = Color.White)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = if (large) 20.sp else 18.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color.White.copy(alpha = 0.92f), fontSize = if (large) 16.sp else 15.sp)
        }
        trailing()
    }
}

/** A 48 dp close button for the end of a banner, named for TalkBack by [label]. */
@Composable
internal fun BannerClose(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(OzenSpacing.MinTouch).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = tint)
    }
}

/** Whether the phone asks for less motion: animations switched off in its settings. */
internal object ReducedMotion {
    var isOn: () -> Boolean = { !ValueAnimator.areAnimatorsEnabled() }
}

/**
 * The colour and strength of the screen-edge flash for a safety or door
 * sound (see `AlertFlash`), as on the iPhone, so it is noticed without
 * looking at the banner. Only a sound raised while the screen is showing
 * flashes, not one already there when it appeared; a weaker sound heard
 * during a siren's flash does not cut it short.
 */
@Composable
internal fun alertFlash(heard: SoundAlert?): Pair<Color, Float> {
    val reduceMotion = ReducedMotion.isOn()
    val before = remember { heard?.id }
    var flashing by remember { mutableStateOf<SoundAlert?>(null) }
    var tint by remember { mutableStateOf(SoundEvent.Importance.Critical) }
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(heard?.id) {
        val alert = heard ?: return@LaunchedEffect
        if (alert.id == before || !AlertFlash.takesOver(flashing?.event?.importance, alert.event.importance, reduceMotion)) return@LaunchedEffect
        flashing = alert
        tint = alert.event.importance
    }
    LaunchedEffect(flashing?.id) {
        val alert = flashing ?: return@LaunchedEffect
        val flash = AlertFlash.pattern(alert.event.importance, reduceMotion) ?: return@LaunchedEffect
        // A newer sound cancels this run, and a cancelled run still ends:
        // only the run for the sound still flashing may touch the light.
        try {
            repeat(flash.count) {
                lit = true
                delay((flash.litSeconds * 1_000).toLong())
                lit = false
                delay((flash.darkSeconds * 1_000).toLong())
            }
        } finally {
            if (flashing?.id == alert.id) {
                lit = false
                flashing = null
            }
        }
    }
    val strength by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 80 else 150), label = "alert flash")
    return soundTint(tint) to strength
}

internal fun soundTint(importance: SoundEvent.Importance): Color = when (importance) {
    SoundEvent.Importance.Critical -> Color(0xFFBF0000)
    SoundEvent.Importance.High -> Color(0xFFB34D00)
    SoundEvent.Importance.Medium -> Color(0xFF0052BF)
    SoundEvent.Importance.Low -> Color(0xFF4D4D4D)
}

internal fun soundIcon(systemImage: String): ImageVector = when {
    systemImage == "drop.triangle.fill" -> Icons.Rounded.Whatshot
    else -> when (systemImage.substringBefore('.')) {
        "light" -> Icons.Rounded.Emergency
        "flame" -> Icons.Rounded.LocalFireDepartment
        "cross" -> Icons.Rounded.MedicalServices
        "shield" -> Icons.Rounded.LocalPolice
        "exclamationmark" -> Icons.Rounded.Warning
        "person" -> Icons.Rounded.RecordVoiceOver
        "car" -> Icons.Rounded.DirectionsCar
        "figure" -> Icons.Rounded.ChildCare
        "drop" -> Icons.Rounded.WaterDrop
        "hand" -> Icons.Rounded.PanTool
        "hands" -> Icons.Rounded.Celebration
        "phone" -> Icons.Rounded.Call
        "alarm" -> Icons.Rounded.Alarm
        "dog", "cat" -> Icons.Rounded.Pets
        "cloud" -> Icons.Rounded.Thunderstorm
        "sparkles" -> Icons.Rounded.AutoAwesome
        "door" -> Icons.Rounded.DoorFront
        "waveform" -> Icons.Rounded.GraphicEq
        "microwave" -> Icons.Rounded.Microwave
        "sink" -> Icons.Rounded.Plumbing
        "toilet" -> Icons.Rounded.Wc
        "fan" -> Icons.Rounded.CleaningServices
        "lungs" -> Icons.Rounded.Masks
        "wind" -> Icons.Rounded.Air
        "face" -> Icons.Rounded.SentimentSatisfied
        "music" -> Icons.Rounded.MusicNote
        else -> Icons.Rounded.NotificationsActive
    }
}

internal fun soundSymbol(systemImage: String): String = when {
    systemImage == "drop.triangle.fill" -> "♨️"
    else -> when (systemImage.substringBefore('.')) {
        "light" -> "🚨"
        "flame" -> "🔥"
        "cross" -> "🚑"
        "shield" -> "🚓"
        "exclamationmark" -> "⚠️"
        "person" -> "🗣️"
        "car" -> "🚗"
        "figure" -> "👶"
        "drop" -> "💧"
        "hand" -> "✊"
        "hands" -> "👏"
        "phone" -> "📞"
        "alarm" -> "⏰"
        "dog" -> "🐕"
        "cloud" -> "⛈️"
        "sparkles" -> "🎆"
        "door" -> "🚪"
        "waveform" -> "🔊"
        "microwave" -> "⏲️"
        "sink" -> "🚰"
        "toilet" -> "🚽"
        "fan" -> "🧹"
        "cat" -> "🐈"
        "lungs" -> "😷"
        "wind" -> "🤧"
        "face" -> "😄"
        "music" -> "🎵"
        else -> "🔔"
    }
}
