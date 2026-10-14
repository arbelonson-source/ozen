package com.arbelonson.ozen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One row inside a SettingsGroup: optional leading [icon] (24 dp, primary tint), [title],
 * optional [subtitle], and a [trailing] slot (Switch, value text, Chevron()). With [onClick]
 * the whole row is a 48 dp+ touch target. Rows with icons want SettingsGroup(dividerInset = 56.dp).
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    RowLayout(
        modifier.then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier),
        title, icon, subtitle, enabled, trailing,
    )
}

/** A row with a Switch at the end; the whole row toggles and is announced as a switch. */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    RowLayout(
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        title, icon, subtitle, enabled,
    ) { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
}

/** Mirrored chevron for rows that open another screen. */
@Composable
fun Chevron() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Grey value text, optionally followed by a Chevron, for a SettingRow trailing slot. */
@Composable
fun TrailingValue(text: String, chevron: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) Chevron()
    }
}

@Composable
private fun RowLayout(
    modifier: Modifier,
    title: String,
    icon: ImageVector?,
    subtitle: String?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    Layout(
        content = {
            if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.layoutId("icon"))
            Column(Modifier.layoutId("title")) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) Box(Modifier.layoutId("trailing")) { trailing() }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp, vertical = 8.dp),
    ) { measurables, constraints ->
        val gap = 16.dp.roundToPx()
        val iconM = measurables.firstOrNull { it.layoutId == "icon" }
        val titleM = measurables.first { it.layoutId == "title" }
        val trailingM = measurables.firstOrNull { it.layoutId == "trailing" }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val iconP = iconM?.measure(loose)
        var used = if (iconP != null) iconP.width + gap else 0
        // The title keeps its longest word whole; the value takes what is left and wraps.
        val titleFloor = titleM.minIntrinsicWidth(constraints.maxHeight).coerceAtMost(constraints.maxWidth - used)
        val trailingP = trailingM?.measure(loose.copy(maxWidth = (constraints.maxWidth - used - titleFloor - gap).coerceAtLeast(0)))
        if (trailingP != null) used += trailingP.width + gap
        val titleP = titleM.measure(loose.copy(maxWidth = (constraints.maxWidth - used).coerceAtLeast(0)))
        val height = maxOf(titleP.height, iconP?.height ?: 0, trailingP?.height ?: 0).coerceAtLeast(constraints.minHeight)
        layout(constraints.maxWidth, height) {
            fun place(p: Placeable, start: Int) = p.placeRelative(start, (height - p.height) / 2)
            var x = 0
            if (iconP != null) { place(iconP, x); x += iconP.width + gap }
            place(titleP, x)
            if (trailingP != null) place(trailingP, constraints.maxWidth - trailingP.width)
        }
    }
}
