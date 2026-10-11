package com.arbelonson.ozen

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
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.tr

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
