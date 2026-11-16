package com.arbelonson.ozen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AnnounceOnChange {
    private var shown: Boolean? = null
    private var lastText: String? = null

    fun observe(now: Boolean, text: String): Boolean {
        val before = shown
        val reworded = now && before == true && lastText != text
        val appeared = observe(now)
        lastText = text
        return appeared || reworded
    }

    fun observe(now: Boolean): Boolean {
        val appeared = shown == false && now
        shown = now
        return appeared
    }
}

@Composable
fun AnnounceWhenShown(shown: Boolean, text: String) {
    val context = LocalContext.current
    val tracker = remember { AnnounceOnChange() }
    LaunchedEffect(shown, text) {
        if (tracker.observe(shown, text)) ScreenReader.said(context, text)
    }
}
