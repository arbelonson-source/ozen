package com.arbelonson.ozen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.arbelonson.ozen.core.tr

object ScreenReader {
    var isOn: (Context) -> Boolean = { context ->
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }

    var enabledServiceIds: (Context) -> List<String> = { context ->
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.map { it.id }
            .orEmpty()
    }

    private val pointerServices = listOf("switchaccess", "voiceaccess", "universalswitch")

    fun pointsWithoutTouch(serviceIds: List<String>): Boolean =
        serviceIds.any { id -> pointerServices.any { id.contains(it, ignoreCase = true) } }

    fun said(context: Context, text: String) {
        if (isOn(context)) announce(context, text)
    }

    private val saidKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Said the first time this [key] is shown with a screen reader on, so a card that scrolls out and back, or is rebuilt, is not read again.
     * A card the user put away (see [forgetSaid]) is a new appearance when it returns, and is said again.
     */
    fun saidOnce(context: Context, key: String, text: String) {
        if (isOn(context) && saidKeys.add(key)) said(context, text)
    }

    fun forgetSaid() = saidKeys.clear()

    fun forgetSaid(key: String) {
        saidKeys.remove(key)
    }

    fun saidAdded(context: Context, word: String) = said(context, tr("נוסף: %1", "Added: %1", listOf(word)))

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
