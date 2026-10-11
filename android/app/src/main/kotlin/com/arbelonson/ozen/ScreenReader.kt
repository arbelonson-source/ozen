package com.arbelonson.ozen

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

object ScreenReader {
    var isOn: (Context) -> Boolean = { context ->
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }

    var announce: (Context, String) -> Unit = { context, text ->
        val manager = context.getSystemService(AccessibilityManager::class.java)
        if (manager?.isEnabled == true) {
            val event = if (Build.VERSION.SDK_INT >= 30) {
                AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            } else {
                @Suppress("DEPRECATION")
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            }
            event.text.add(text)
            event.packageName = context.packageName
            manager.sendAccessibilityEvent(event)
        }
    }
}
