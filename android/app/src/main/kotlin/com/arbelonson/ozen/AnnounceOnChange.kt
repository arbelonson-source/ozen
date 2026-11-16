package com.arbelonson.ozen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AnnounceOnChange {
    private var shown: Boolean? = null

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
    LaunchedEffect(shown) {
        if (tracker.observe(shown)) ScreenReader.said(context, text)
    }
}
