package com.arbelonson.ozen

import android.content.ComponentCallbacks2

@Suppress("DEPRECATION")
object MemoryPressure {
    fun isWarning(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
}
