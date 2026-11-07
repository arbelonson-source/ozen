package com.arbelonson.ozen.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DialogText(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val background = AlertDialogDefaults.containerColor
    Text(
        text,
        modifier
            .drawWithContent {
                drawContent()
                if (scroll.canScrollForward) {
                    val fade = 32.dp.toPx().coerceAtMost(size.height / 2)
                    val top = size.height - fade
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, background), startY = top, endY = size.height),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, fade),
                    )
                }
            }
            .verticalScroll(scroll),
    )
}
