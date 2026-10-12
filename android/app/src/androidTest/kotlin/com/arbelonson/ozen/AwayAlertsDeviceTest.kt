package com.arbelonson.ozen

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundEventCatalog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AwayAlertsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted = mutableListOf<String>()

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun takeThemAway() {
        for (tag in posted) manager.cancel(tag, 0)
    }

    private fun postedWhileAway(identifier: String): Notification {
        val now = System.currentTimeMillis() / 1_000.0
        val alert = SoundAlert(event = SoundEventCatalog.event(identifier)!!, confidence = 0.9, timestamp = now)
        val content = checkNotNull(AwayAlerts().forSound(alert, appIsActive = false, AppSettings.default, now, 0)) { "no notification for $identifier" }
        manager.cancel(content.identifier, 0)
        posted += content.identifier
        assertTrue(AlertNotifier(context).post(content))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag == content.identifier }?.let { return it.notification }
            Thread.sleep(100)
        }
        throw AssertionError("$identifier was never posted")
    }

    @Test
    fun aSmokeAlarmHeardWhileTheAppIsAwayBuzzesAsLongAsInFrontAndComesThroughDoNotDisturbAsAnAlarm() {
        val smoke = postedWhileAway("smoke_detector")
        assertEquals(Notification.CATEGORY_ALARM, smoke.category)
        val channel = manager.getNotificationChannel(smoke.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertArrayEquals(AlertVibration.pattern(SoundEvent.Importance.Critical).waveform().first, channel.vibrationPattern)

        val door = postedWhileAway("door_bell")
        assertNotEquals(Notification.CATEGORY_ALARM, door.category)
        assertEquals(AlertNotifier.CHANNEL_ID, door.channelId)
        assertArrayEquals(AlertVibration.keyword.waveform().first, manager.getNotificationChannel(door.channelId).vibrationPattern)
    }
}
