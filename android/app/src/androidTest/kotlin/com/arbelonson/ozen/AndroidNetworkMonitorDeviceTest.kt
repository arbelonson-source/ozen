package com.arbelonson.ozen

import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.NetworkConditions
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication

    @After
    fun backOnline() = airplaneMode(false)

    @Test
    fun theConnectionGoingAndComingBackReachesThePipelineOnTheMainThread() {
        val reported = CopyOnWriteArrayList<NetworkConditions>()
        val offMain = CopyOnWriteArrayList<String>()
        lateinit var monitor: AndroidNetworkMonitor
        instrumentation.runOnMainSync {
            monitor = AndroidNetworkMonitor(app)
            monitor.onChange = { conditions ->
                reported += conditions
                if (Looper.myLooper() != Looper.getMainLooper()) offMain += Thread.currentThread().name
            }
        }
        try {
            assertTrue("never online at the start", within(10_000) { monitor.current?.isConnected == true })
            assertEquals(true, app.captions.pipeline.networkConditions?.isConnected)

            airplaneMode(true)
            assertTrue("going offline never reported", within(20_000) { reported.lastOrNull()?.isConnected == false })
            assertTrue("the pipeline never heard it went", within(20_000) { app.captions.pipeline.networkConditions?.isConnected == false })

            airplaneMode(false)
            assertTrue("coming back never reported", within(60_000) { reported.lastOrNull()?.isConnected == true })
            assertTrue("the pipeline never heard it came back", within(20_000) { app.captions.pipeline.networkConditions?.isConnected == true })
            assertEquals(emptyList<String>(), offMain)
        } finally {
            instrumentation.runOnMainSync { monitor.close() }
        }
    }

    private fun airplaneMode(on: Boolean) {
        instrumentation.uiAutomation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").close()
    }

    private fun within(millis: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
