package com.arbelonson.ozen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbelonson.ozen.core.tr

/**
 * A standard scrolling page: top bar, then [content] with 16 dp side margins
 * and 8 dp between children (use Spacer(24.dp) or SectionHeader between groups).
 * Pass scrolls = false for content that manages its own scrolling or fills the page.
 */
@Composable
fun OzenScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = tr("סיום", "Done"),
    actions: @Composable RowScope.() -> Unit = {},
    scrolls: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            OzenTopBar(title, onBack = onBack, onClose = onClose, closeLabel = closeLabel, actions = actions)
            Column(
                Modifier
                    .weight(1f)
                    .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = OzenSpacing.Page)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(OzenSpacing.Unit),
                content = content,
            )
        }
    }
}
