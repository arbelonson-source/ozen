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
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .background(soundTint(alert.event.importance).copy(alpha = 0.92f), shape)
            .clickable(onClickLabel = tr("סגירה", "Close"), role = Role.Button, onClick = onDismiss)
            .semantics { contentDescription = tr("התראה: %1", "Alert: %1", listOf(alert.event.name)) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(soundSymbol(alert.event.systemImage), fontSize = 30.sp, modifier = Modifier.clearAndSetSemantics { })
        Column(Modifier.weight(1f)) {
            Text(alert.event.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (alert.event.importance == SoundEvent.Importance.Critical) tr("שימו לב!", "Attention!") else tr("נשמע עכשיו", "Heard just now"),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
            )
        }
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
    }
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
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
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
