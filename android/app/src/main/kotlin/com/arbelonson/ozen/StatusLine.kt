package com.arbelonson.ozen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.ui.OzenColors
import com.arbelonson.ozen.ui.OzenSpacing

@Composable
internal fun StatusLine(status: PhasePresentation, look: CaptionTheme, live: Boolean, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // As on the iPhone: paused, off or stopped shows its own icon and colour.
    // Only the small green dot went away before, and the old lines stayed,
    // so a glance at the phone took the silence for nobody talking.
    val quiet = !status.isBusy && !live
    val tone = when (status.tint) {
        PhaseTint.Problem -> colors.error
        PhaseTint.Attention -> OzenColors.statusWarn(dark = !look.isLight)
        PhaseTint.Inactive -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    // The state on its own line, what a tap does on a real button beside it:
    // a green "Listening" with "Tap to pause" in small print didn't look
    // like a button, and the hint went away with large text.
    val button = statusAction(status.action)
    val detail = status.stateDetail
    val state = @Composable { modifier: Modifier ->
        Column(
            // A download's bar is read with the state, in one stop: the bar
            // alone is an item of its own to TalkBack.
            modifier = modifier.testTag(STATUS_STATE).semantics(mergeDescendants = true) { status.progress?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..1f) } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
                // The title already says what it is busy with; only a download's bar is read out.
                if (status.isBusy) {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp).testTag(STATUS_BUSY).clearAndSetSemantics {})
                } else if (live) {
                    LiveDot(OzenColors.statusGood(dark = !look.isLight))
                } else {
                    Icon(status.icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp).testTag(STATUS_ICON))
                }
                val inline = detail != null && !status.isBusy && status.progress == null
                Text(
                    text = status.title,
                    color = if (quiet) tone else colors.onSurface,
                    style = if (quiet) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (inline) {
                    Text(text = "·", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
                    Text(text = detail.orEmpty(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (detail != null && (status.isBusy || status.progress != null)) {
                Text(text = detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            status.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress.toFloat() }, color = colors.primary, trackColor = colors.outlineVariant, modifier = Modifier.padding(top = 8.dp).widthIn(max = 200.dp).clearAndSetSemantics {})
            }
        }
    }
    val action = @Composable { modifier: Modifier ->
        button?.let { (label, icon) ->
            Button(
                onClick = onTap,
                modifier = modifier.heightIn(min = STATUS_BUTTON_HEIGHT).testTag(STATUS_BUTTON).semantics { stateDescription = status.title },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(OzenSpacing.Unit))
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
    val padding = Modifier.fillMaxWidth().heightIn(min = OzenSpacing.MinTouch).padding(horizontal = OzenSpacing.Page, vertical = OzenSpacing.Unit)
    // With large text the button gets a row of its own rather than squeezing
    // its words; it is never only an icon.
    if (button == null || LocalDensity.current.fontScale > 1.3f) {
        Column(padding, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit, Alignment.CenterVertically)) {
            state(Modifier)
            action(Modifier.fillMaxWidth())
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Page)) {
            state(Modifier.weight(1f))
            action(Modifier)
        }
    }
}

internal val STATUS_BUTTON_HEIGHT = 56.dp

internal const val STATUS_BUTTON = "statusButton"

internal const val STATUS_STATE = "statusState"

internal const val STATUS_BUSY = "statusBusy"

internal const val STATUS_ICON = "statusIcon"

/**
 * A dot beside "Listening" that breathes while the microphone is on, as
 * a recording light does; still for a phone that asks for less motion.
 */
@Composable
private fun LiveDot(colour: Color) {
    val breathing = !ReducedMotion.isOn()
    val glow = if (breathing) {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "live dot",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(8.dp).graphicsLayer { alpha = glow }.background(colour, CircleShape).clearAndSetSemantics {})
}
