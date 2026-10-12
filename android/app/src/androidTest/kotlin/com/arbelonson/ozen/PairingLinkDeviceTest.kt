package com.arbelonson.ozen

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingLinkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val link = Intent(Intent.ACTION_VIEW, Uri.parse("ozen://pair?address=ws://10.0.2.2:9&code=devicetest")).setClass(app, MainActivity::class.java)

    @After
    fun forget() {
        instrumentation.runOnMainSync { app.pairing.pending.value = null }
    }

    private fun asked(): Boolean {
        val until = System.currentTimeMillis() + 5_000
        while (app.pairing.pending.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
        return app.pairing.pending.value != null
    }

    @Test
    fun aPairingLinkAnsweredOnceIsNotAskedAgainWhenTheScreenIsRebuilt() {
        ActivityScenario.launch<MainActivity>(link).use { scenario ->
            assertNotNull("the link never asked", app.pairing.pending.value.takeIf { asked() })
            instrumentation.runOnMainSync { app.pairing.pending.value = null }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            assertNull("the answered link was asked again after the screen was rebuilt", app.pairing.pending.value)
        }
    }

    @Test
    fun aPairingLinkAnsweredOnceIsNotAskedAgainWhenTheAppIsOpenedFromRecentApps() {
        ActivityScenario.launch<MainActivity>(Intent(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).use {
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            assertNull("a link from before was asked again on opening from recent apps", app.pairing.pending.value)
        }
    }
}
