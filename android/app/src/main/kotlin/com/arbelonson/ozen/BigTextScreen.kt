package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        // As on the iPhone, a screen reader hears what flipping is for.
        val flipHint = tr("להפוך את הטקסט כדי שמי שיושב מולך יוכל לקרוא", "Flip the text so the person sitting across from you can read it")
        val buttons = buildList<BigAction> {
            add(BigAction(Icons.Rounded.Delete, tr("ניקוי", "Clear"), null, { onText(""); flipped = false }.takeUnless { empty }))
            add(
                BigAction(
                    if (flipped) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Flip,
                    if (flipped) tr("חזרה", "Back") else tr("להפוך", "Flip"),
                    flipHint.takeUnless { flipped },
                    { flipped = !flipped }.takeUnless { empty },
                ),
            )
            if (canSpeak) add(BigAction(Icons.AutoMirrored.Rounded.VolumeUp, tr("להשמיע", "Speak"), null, onSpeak.takeUnless { empty }))
            add(BigAction(Icons.Rounded.Close, tr("סגירה", "Close"), null, onClose))
        }
        HorizontalDivider(color = look.chrome.copy(alpha = 0.2f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (button in buttons) {
                val tint = look.chrome.copy(alpha = if (button.action != null) 1f else 0.38f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = button.action != null, role = Role.Button, onClickLabel = button.hint) { button.action?.invoke() }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(button.icon, contentDescription = null, tint = tint)
                    Text(button.title, color = tint, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp))
                }
            }
        }
    }
}

private class BigAction(val icon: ImageVector, val title: String, val hint: String?, val action: (() -> Unit)?)

private const val FONT_SIZE = 52.0
