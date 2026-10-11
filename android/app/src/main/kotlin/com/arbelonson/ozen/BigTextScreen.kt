package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.tr

/**
 * Words in letters big enough to read across a table, as on the iPhone.
 * Where captions can't keep up, the other person types on her phone and
 * she reads it here; and what she typed can be turned upside down to her,
 * so the person facing her reads it instead of hearing it. It edits the
 * Say screen's own field, so nothing typed on either is lost on the other.
 */
@Composable
internal fun BigTextScreen(
    text: String,
    onText: (String) -> Unit,
    look: CaptionTheme,
    captionSize: Double,
    canSpeak: Boolean,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var flipped by rememberSaveable { mutableStateOf(false) }
    val empty = text.isBlank()
    // Never smaller than her own captions, which can be set larger.
    val words = TextStyle(color = look.text, fontSize = maxOf(FONT_SIZE, captionSize).sp, fontWeight = FontWeight.Bold)
    val focus = remember { FocusRequester() }
    LaunchedEffect(flipped) { if (!flipped) focus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(look.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (flipped) {
            // Read-only while upside down: a cursor drawn rotated would sit
            // where no one expects it. Centred, so a short phrase isn't left
            // against the buttons with the rest of the screen blank above it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().rotate(180f)) {
                Box(
                    modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = words)
                }
            }
        } else {
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = words,
                cursorBrush = SolidColor(look.text),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = tr("טקסט גדול", "Big text") },
                decorationBox = { field ->
                    Box {
                        if (empty) Text(tr("כתבו כאן…", "Type here…"), style = words.copy(color = look.pendingText))
                        field()
                    }
                },
            )
        }
        val buttons = buildList<Pair<String, (() -> Unit)?>> {
            add(tr("ניקוי", "Clear") to { onText(""); flipped = false }.takeUnless { empty })
            add((if (flipped) tr("חזרה", "Back") else tr("להפוך", "Flip")) to { flipped = !flipped }.takeUnless { empty })
            if (canSpeak) add(tr("להשמיע", "Speak") to onSpeak.takeUnless { empty })
            add(tr("סגירה", "Close") to onClose)
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in buttons.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((title, action) in row) {
                        OutlinedButton(
                            onClick = { action?.invoke() },
                            enabled = action != null,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = look.chrome, disabledContentColor = look.chrome.copy(alpha = 0.38f)),
                            border = BorderStroke(1.dp, look.chrome.copy(alpha = if (action != null) 0.6f else 0.2f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private const val FONT_SIZE = 52.0
