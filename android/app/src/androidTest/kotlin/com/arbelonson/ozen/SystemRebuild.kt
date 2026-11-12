package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

fun ComposeTestRule.waitForTheSystemRebuildAt(fontScale: Float) {
    waitUntil(10_000) {
        var scale: Float? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            scale = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.resources?.configuration?.fontScale
        }
        scale == fontScale
    }
}
