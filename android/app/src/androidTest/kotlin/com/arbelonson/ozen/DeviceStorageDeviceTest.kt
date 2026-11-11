package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceStorageDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val slack = 10_000_000L

    @Test
    fun theSpaceTheModelDownloadsSeeIsAtLeastWhatIsFreeNow() {
        val before = app.filesDir.usableSpace
        val seen = app.captions.freeSpace()
        val usable = minOf(before, app.filesDir.usableSpace)
        assertTrue("$seen is less than $usable", seen >= usable - slack)
    }

    @Test
    fun askingThePhoneToMakeRoomReturnsWithoutThrowing() {
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "room-asker") {
            try {
                app.captions.makeRoom(1_000_000)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.join(60_000)
        assertTrue("still waiting for the phone", !worker.isAlive)
        assertNull(failure.get())
    }
}
