package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.tr

val NoActions: @Composable RowScope.() -> Unit = {}

/**
 * The one top bar, in two lines when there is a back arrow or [actions] (a row with
 * them, then the title, so a long title never gets squeezed), and in one line, the title and
 * a text action at the end, when there is neither. A back arrow with the word "Back" sits at
 * the start for [onBack], or for [onClose] when [closeLabel] is the word "Back"; any other
 * [closeLabel] ("Close", "Cancel", "Done") is a text action at the end, for a page that
 * is a sheet rather than a step. Arrow mirrors in right-to-left. Actions are icon buttons with a
 * contentDescription, or icon + label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OzenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = tr("סיום", "Done"),
    actions: @Composable RowScope.() -> Unit = NoActions,
    insets: Boolean = true,
) {
    val backLabel = tr("חזרה", "Back")
    val back = onBack ?: onClose.takeIf { closeLabel == backLabel }
    val closeAction = onClose.takeIf { back == null || onBack != null }
    val oneRow = back == null && actions === NoActions
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        if (oneRow) {
            Row(
                Modifier.then(if (insets) Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets) else Modifier).fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
                )
                if (closeAction != null) {
                    TextButton(onClick = closeAction, modifier = Modifier.heightIn(min = OzenSpacing.MinTouch)) { Text(closeLabel) }
                }
            }
            return@Surface
        }
        Column(Modifier.then(if (insets) Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets) else Modifier).padding(horizontal = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) {
                    TextButton(onClick = back, modifier = Modifier.heightIn(min = OzenSpacing.MinTouch)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(backLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.weight(1f))
                actions()
                if (closeAction != null) {
                    TextButton(onClick = closeAction, modifier = Modifier.heightIn(min = OzenSpacing.MinTouch)) { Text(closeLabel) }
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).semantics { heading() },
            )
        }
    }
}
