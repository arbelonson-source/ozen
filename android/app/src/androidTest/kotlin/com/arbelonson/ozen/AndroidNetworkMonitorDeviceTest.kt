package com.arbelonson.ozen

import android.net.ConnectivityManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.NetworkConditions
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    // Data Saver holds back only metered networks, as the iPhone's Low
    // Data Mode belongs to one network: on home Wi-Fi with it on, the model
    // was kept "waiting for Wi-Fi".
    @Test
    fun dataSaverOnWiFiDoesNotHoldTheModelDownloadBack() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        var monitor: AndroidNetworkMonitor? = null
        try {
            shell("cmd netpolicy set restrict-background true")
            assertEquals(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, connectivity.restrictBackgroundStatus)
            instrumentation.runOnMainSync { monitor = AndroidNetworkMonitor(app) }
            assertTrue("never online", within(10_000) { monitor?.current?.isConnected == true })
            val wifi = monitor!!.current!!
            assumeTrue("the test phone's network is unmetered Wi-Fi", !wifi.isExpensive)
            assertFalse("Wi-Fi was taken for a limited network", wifi.isConstrained)
            assertEquals(ModelDownloadGate.Decision.Proceed, ModelDownloadGate.decide(wifi, allowCellular = false))
        } finally {
            instrumentation.runOnMainSync { monitor?.close() }
            shell("cmd netpolicy set restrict-background false")
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
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
