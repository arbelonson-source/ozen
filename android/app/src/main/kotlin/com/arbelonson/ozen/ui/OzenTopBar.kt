package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.arbelonson.ozen.core.tr

/**
 * The one top bar. Give [onBack] for a back arrow (its label is read by TalkBack),
 * or [onClose] for a visible text action ([closeLabel], "Done" by default), or neither.
 * [actions] sit at the end; use icon + label or an IconButton with a contentDescription.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OzenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = tr("סיום", "Done"),
    actions: @Composable RowScope.() -> Unit = {},
    insets: Boolean = true,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        // The title keeps its longest word whole: when the actions would squeeze it,
        // they drop to a line of their own beneath it.
        Layout(
            content = {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.layoutId("back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("חזרה", "Back"))
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.layoutId("title").semantics { heading() },
                )
                Row(Modifier.layoutId("actions"), verticalAlignment = Alignment.CenterVertically) {
                    actions()
                    if (onClose != null) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = OzenSpacing.MinTouch)) { Text(closeLabel) }
                }
            },
            modifier = Modifier.then(if (insets) Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets) else Modifier).heightIn(min = 64.dp).padding(horizontal = 4.dp),
        ) { measurables, constraints ->
            val inset = 12.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val backM = measurables.firstOrNull { it.layoutId == "back" }
            val titleM = measurables.first { it.layoutId == "title" }
            val actionsM = measurables.first { it.layoutId == "actions" }
            val backP = backM?.measure(loose)
            val lead = backP?.width ?: inset
            val actionsP = actionsM.measure(loose)
            val titleFloor = titleM.minIntrinsicWidth(constraints.maxHeight)
            val stacked = actionsP.width > 0 && lead + titleFloor + actionsP.width + inset > constraints.maxWidth
            val titleRoom = if (stacked || actionsP.width == 0) constraints.maxWidth - lead - inset else constraints.maxWidth - lead - actionsP.width
            val titleP = titleM.measure(loose.copy(maxWidth = titleRoom.coerceAtLeast(0)))
            val row = maxOf(titleP.height + 16.dp.roundToPx(), backP?.height ?: 0, if (stacked) 0 else actionsP.height, 64.dp.roundToPx())
            val height = if (stacked) row + actionsP.height else row
            layout(constraints.maxWidth, height.coerceAtLeast(constraints.minHeight)) {
                backP?.placeRelative(0, (row - backP.height) / 2)
                titleP.placeRelative(lead, (row - titleP.height) / 2)
                if (stacked) actionsP.placeRelative(constraints.maxWidth - actionsP.width, row) else actionsP.placeRelative(constraints.maxWidth - actionsP.width, (row - actionsP.height) / 2)
            }
        }
    }
}
