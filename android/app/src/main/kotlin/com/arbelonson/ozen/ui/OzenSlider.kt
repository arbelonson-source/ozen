package com.arbelonson.ozen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A calm slider: a 4 dp track with no stop dots, no gap and a 20 dp round thumb.
 * [steps] still snap the value; they are not drawn. Mirrors in right-to-left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OzenSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { SliderDefaults.Thumb(interactionSource = remember { MutableInteractionSource() }, thumbSize = DpSize(20.dp, 20.dp)) },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span == 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val corner = CornerRadius(size.height / 2)
                val rtl = layoutDirection == LayoutDirection.Rtl
                drawRoundRect(outline, size = size, cornerRadius = corner)
                drawRoundRect(inactive, topLeft = Offset(0.5f, 0.5f), size = Size(size.width - 1f, size.height - 1f), cornerRadius = corner)
                val width = size.width * fraction
                drawRoundRect(active, topLeft = Offset(if (rtl) size.width - width else 0f, 0f), size = Size(width, size.height), cornerRadius = corner)
            }
        },
    )
}
