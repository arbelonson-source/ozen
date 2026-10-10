package com.arbelonson.ozen

import android.content.ComponentCallbacks2
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
class MemoryPressureTest {
    @Test
    fun `only levels that say memory is short count as a warning, not leaving the app`() {
        val levels = mapOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE to false,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to true,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL to true,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN to false,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND to true,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE to true,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE to true,
        )
        assertEquals(levels, levels.mapValues { (level, _) -> MemoryPressure.isWarning(level) })
    }
}
