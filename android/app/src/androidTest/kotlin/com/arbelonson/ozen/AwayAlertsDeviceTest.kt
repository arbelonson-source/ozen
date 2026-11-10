package com.arbelonson.ozen

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AlertNotificationContent
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundEventCatalog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        assertTrue("$identifier was not posted", AlertNotifier(context).post(content))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag == content.identifier }?.let { return it.notification }
            Thread.sleep(100)
        }
        throw AssertionError("$identifier was never posted")
    }

    // The test alert waits 10 seconds for the phone to be locked; that wait
    // stopped while the processor slept, so the test came late or never.
    @Test
    fun theTestAlertKeepsThePhoneAwakeUntilItIsPosted() {
        val before = AlertNotifier.testLock
        val held = AtomicBoolean(false)
        val heldFor = AtomicLong(0)
        AlertNotifier.testLock = {
            object : TimedLock {
                override fun acquire(timeoutMillis: Long) {
                    heldFor.set(timeoutMillis)
                    held.set(true)
                }

                override fun release() = held.set(false)
            }
        }
        val content = BackgroundAlertPolicy.testNotification
        manager.cancel(content.identifier, 0)
        posted += content.identifier
        try {
            AlertNotifier(context).postTest(content)
            assertTrue("the phone was not kept awake for the wait", held.get())
            assertTrue("kept awake for only ${heldFor.get()} ms", heldFor.get() > AlertNotifier.TEST_DELAY_MILLIS)
            val deadline = System.currentTimeMillis() + AlertNotifier.TEST_DELAY_MILLIS + 5_000
            while (held.get() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertFalse("the phone was kept awake after the test", held.get())
            assertNotNull("the test alert was never posted", manager.activeNotifications.firstOrNull { it.tag == content.identifier })
        } finally {
            AlertNotifier.testLock = before
        }
    }

    // On a phone that hides notification contents when locked, a smoke alarm
    // showed only "Ozen", like a doorbell or any other alert.
    @Test
    fun soundAlertsCanBeReadOnALockedPhone() {
        for (identifier in listOf("door_bell", "smoke_detector")) {
            assertEquals(identifier, Notification.VISIBILITY_PUBLIC, postedWhileAway(identifier).visibility)
        }
    }

    // A word alert quotes the line, which can be private: a locked phone that
    // hides contents shows only the word, never the line.
    @Test
    fun aWordAlertShowsOnlyTheWordOnALockedPhoneThatHidesContents() {
        val content = AlertNotificationContent("keyword-DEVICE-TEST", "Said: Dana", "Dana, the test results came back", AlertNotifier.KEYWORDS_THREAD, false)
        manager.cancel(content.identifier, 0)
        posted += content.identifier
        assertTrue(AlertNotifier(context).post(content))
        val deadline = System.currentTimeMillis() + 5_000
        var notification: Notification? = null
        while (notification == null && System.currentTimeMillis() < deadline) {
            notification = manager.activeNotifications.firstOrNull { it.tag == content.identifier }?.notification
            if (notification == null) Thread.sleep(100)
        }
        checkNotNull(notification) { "the word alert was never posted" }
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = checkNotNull(notification.publicVersion) { "no lock-screen version" }
        assertEquals("Said: Dana", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
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

    @Test
    fun aSmokeAlarmStillComesWithTheOrdinaryAlertsTurnedOffInThePhonesSettings() {
        val before = AlertNotifier.allowed
        try {
            AlertNotifier.allowed = { false }
            assertEquals(Notification.CATEGORY_ALARM, postedWhileAway("smoke_detector").category)
        } finally {
            AlertNotifier.allowed = before
        }
    }

    @Test
    fun theEmergencySettingsPageIsTheEmergencyChannelsOwnAndTheChannelIsThereBeforeAnyAlarm() {
        manager.deleteNotificationChannel(AlertNotifier.ALARM_CHANNEL_ID)
        val page = AlertNotifier.alarmSettings(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, page.action)
        assertEquals(context.packageName, page.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(AlertNotifier.ALARM_CHANNEL_ID, page.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertNotNull(page.resolveActivity(context.packageManager))
        val channel = manager.getNotificationChannel(AlertNotifier.ALARM_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertArrayEquals(AlertVibration.pattern(SoundEvent.Importance.Critical).waveform().first, channel.vibrationPattern)
        assertEquals(channel.canBypassDnd(), AlertNotifier.alarmsOverrideDoNotDisturb(context))
    }
}
