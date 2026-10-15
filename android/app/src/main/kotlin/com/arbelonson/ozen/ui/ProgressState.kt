package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.ReducedMotion

/**
 * Centered progress layout: [icon] badge, [title], a bar with "NN%" when [percent] (0..100)
 * is known (an indeterminate bar otherwise; none when the phone reduces motion), a [detail]
 * line, then [actions] (a TonalButton for Cancel or a PrimaryButton for Retry).
 * Fill the whole page with it, or use ProgressCard inside a scrolling page.
 */
@Composable
fun ProgressState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    percent: Int? = null,
    detail: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(OzenSpacing.Page),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit * 2, Alignment.CenterVertically),
    ) {
        IconBadge(icon, size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        if (percent != null) {
            val shown = percent.coerceIn(0, 100)
            LinearProgressIndicator(progress = { shown / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$shown%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        } else if (!ReducedMotion.isOn()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** ProgressState on a rounded surfaceContainer card, for use inside a page. */
@Composable
fun ProgressCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    percent: Int? = null,
    detail: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        ProgressState(icon, title, percent = percent, detail = detail, actions = actions)
    }
}
