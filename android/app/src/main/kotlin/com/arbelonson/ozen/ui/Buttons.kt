package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

/** Big filled brand button (min 56 dp high), optional leading [icon]. Wraps to two lines at large font scale. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.extraLarge, contentPadding = ButtonPadding) {
        ButtonContent(text, icon)
    }
}

/** Quieter filled button for secondary actions, same size as PrimaryButton. */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    FilledTonalButton(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.extraLarge, contentPadding = ButtonPadding) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}
