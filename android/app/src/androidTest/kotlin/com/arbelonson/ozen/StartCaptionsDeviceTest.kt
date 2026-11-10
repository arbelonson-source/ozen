package com.arbelonson.ozen

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartCaptionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as OzenApplication

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitStarted() {
        val until = System.currentTimeMillis() + 15_000
        while (app.captions.pipeline.phase == PipelinePhase.Idle && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("never started: ${app.captions.pipeline.phase}", app.captions.pipeline.phase != PipelinePhase.Idle)
    }

    @After
    fun stopCaptions() {
        instrumentation.runOnMainSync {
            if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop()
            app.captions.launchHandled = true
        }
    }

    private fun startIntent() = Intent(context, MainActivity::class.java).setAction(StartCaptionsRequest.ACTION)

    private fun stopped() {
        instrumentation.runOnMainSync {
            app.captions.launchHandled = true
            if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop()
        }
        val until = System.currentTimeMillis() + 10_000
        while (app.captions.pipeline.phase != PipelinePhase.Idle && System.currentTimeMillis() < until) Thread.sleep(100)
        assertEquals(PipelinePhase.Idle, app.captions.pipeline.phase)
    }

    @Test
    fun openingTheAppWithTheStartActionWhileCaptionsAreStoppedStartsThem() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        stopped()
        ActivityScenario.launch<MainActivity>(startIntent()).use {
            val until = System.currentTimeMillis() + 15_000
            while (app.captions.pipeline.phase == PipelinePhase.Idle && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue("never started: ${app.captions.pipeline.phase}", app.captions.pipeline.phase != PipelinePhase.Idle)
        }
    }

    @Test
    fun openingTheAppWithTheStartActionDuringTheWalkthroughDoesNotStartCaptions() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        stopped()
        app.settings.change { it.hasCompletedOnboarding = false }
        try {
            ActivityScenario.launch<MainActivity>(startIntent()).use {
                val until = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < until) {
                    assertEquals("the captions started", PipelinePhase.Idle, app.captions.pipeline.phase)
                    Thread.sleep(100)
                }
            }
        } finally {
            app.settings.change { it.hasCompletedOnboarding = true }
        }
    }

    @Test
    fun anOpenAppBroughtForwardWithTheStartActionStartsStoppedCaptions() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            stopped()
            scenario.onActivity { it.startActivity(startIntent()) }
            val until = System.currentTimeMillis() + 15_000
            while (app.captions.pipeline.phase == PipelinePhase.Idle && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue("never started: ${app.captions.pipeline.phase}", app.captions.pipeline.phase != PipelinePhase.Idle)
        }
    }

    @Test
    fun theStartActionWithSettingsOrHistoryOpenBringsTheCaptionsToTheFront() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            stopped()
            compose.onNodeWithText("Settings").performClick()
            compose.waitUntil(5_000) { shown("Diagnostics") }
            scenario.onActivity { it.startActivity(startIntent()) }
            waitStarted()
            compose.waitUntil(10_000) { !shown("Diagnostics") }

            stopped()
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(5_000) { shown("Delete all") }
            scenario.onActivity { it.startActivity(startIntent()) }
            waitStarted()
            compose.waitUntil(10_000) { !shown("Delete all") }
            compose.onNodeWithText("Settings").assertExists()
        }
    }

    @Test
    fun theAppIconHasAStartCaptionsShortcutThatCarriesTheStartAction() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == "start-captions" }
            assertNotNull("no start-captions shortcut", shortcut)
            assertEquals(tr("התחלת כתוביות", "Start captions"), shortcut!!.shortLabel.toString())
            assertEquals(tr("התחלת כתוביות", "Start captions"), shortcut.longLabel.toString())
            assertEquals(StartCaptionsRequest.ACTION, shortcut.intent.action)
            assertEquals(MainActivity::class.java.name, shortcut.intent.component?.className)
        }
    }

    @Test
    fun theQuickSettingsTileOpensTheAppWithTheStartActionThroughAnImmutablePendingIntent() {
        val launch = StartCaptionsRequest.launchIntent(context)
        assertEquals(StartCaptionsRequest.ACTION, launch.action)
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val made = StartCaptionsRequest.launchPendingIntent(context)
        val same = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(same)
        assertEquals(made, same)
    }

    @Test
    fun theTileServiceIsDeclaredForQuickSettingsAndGuardedByTheSystemPermission() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, CaptionsTileService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission)
        assertTrue(info.exported)
        val handlers = context.packageManager.queryIntentServices(
            Intent("android.service.quicksettings.action.QS_TILE").setPackage(context.packageName),
            0,
        )
        assertTrue(handlers.any { it.serviceInfo.name == CaptionsTileService::class.java.name })
    }
}
