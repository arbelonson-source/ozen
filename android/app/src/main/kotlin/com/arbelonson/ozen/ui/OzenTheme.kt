package com.arbelonson.ozen.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.OzenTypography

/** Page margin and the 8 dp rhythm every screen uses. */
object OzenSpacing {
    val Page = 16.dp
    val Unit = 8.dp
    val Section = 24.dp
    val MinTouch = 48.dp
}

object OzenShapes {
    val Material = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** Wraps MaterialTheme with the blue Ozen schemes, the Ozen type scale and shapes. */
@Composable
fun OzenTheme(dark: Boolean, highContrast: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OzenColors.scheme(dark, highContrast),
        typography = OzenTypography.of(MaterialTheme.typography),
        shapes = OzenShapes.Material,
        content = content,
    )
}
