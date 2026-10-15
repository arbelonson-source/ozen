package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class SettingsGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    /** One row (usually a SettingRow). Thin dividers are drawn between items. */
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * A rounded card (surfaceContainer) with [content] rows and inset dividers between them:
 * `SettingsGroup { item { SettingRow(...) }; item { SettingRow(...) } }`.
 * [dividerInset] is 16 dp for text-only rows and 72 dp when every row starts with an icon.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    dividerInset: Dp = 16.dp,
    content: SettingsGroupScope.() -> Unit,
) {
    val rows = SettingsGroupScope().apply(content).items
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = dividerInset, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                row()
            }
        }
    }
}

/** Small grey caption above a group. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = OzenSpacing.Section - OzenSpacing.Unit, bottom = 0.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Explanatory text under a group. */
@Composable
fun GroupFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
