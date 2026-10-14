package com.arbelonson.ozen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp

/**
 * Icon-over-label toolbar button (visible label always; icon is decorative). [selected]
 * shows a tonal pill behind the icon and is announced to TalkBack. Give it `Modifier.weight(1f)`
 * inside OzenBottomToolbar.
 */
@Composable
fun ToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape).background(if (selected) colors.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.onSurface else colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp))
    }
}

/** The bar that holds ToolbarButtons: surfaceContainer, evenly spaced, above the navigation bar. */
@Composable
fun OzenBottomToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = OzenSpacing.Unit, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
            content = content,
        )
    }
}
