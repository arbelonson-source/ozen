package com.arbelonson.ozen

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptionLine
import com.arbelonson.ozen.core.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningNotificationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun Notification.text(key: String) = extras.getCharSequence(key)?.toString()

    @Test
    fun theLockScreenShowsTheNewestLineWithItsSpeakerBothLinesOpenedAndHowLongAgo() {
        val content = LockScreenCaptionContent(
            lines = listOf(
                LockScreenCaptionLine(speaker = "Dana", text = "How did you sleep", isFinal = true),
                LockScreenCaptionLine(speaker = null, text = "Fine thanks", isFinal = true, sameSpeakerAsAbove = true),
            ),
            status = "Listening",
            ageNote = "said 2 minutes ago",
        )
        val shown = ListeningService.notification(context, content, public = true)
        assertEquals("Listening", shown.text(Notification.EXTRA_TITLE))
        assertEquals("Dana: Fine thanks", shown.text(Notification.EXTRA_TEXT))
        assertEquals("Dana: How did you sleep\nFine thanks", shown.text(Notification.EXTRA_BIG_TEXT))
        assertEquals("said 2 minutes ago", shown.text(Notification.EXTRA_SUB_TEXT))
        assertEquals(Notification.VISIBILITY_PUBLIC, shown.visibility)
    }

    @Test
    fun withCaptionsKeptOffTheLockScreenTheLockedPhoneShowsOnlyTheStatus() {
        val content = LockScreenCaptionContent(
            lines = listOf(
                LockScreenCaptionLine(speaker = "Dana", text = "See you at dinner", isFinal = true),
                LockScreenCaptionLine(speaker = null, text = "Bring the cake", isFinal = true),
            ),
            status = "Listening",
            ageNote = "said 2 minutes ago",
        )
        val shown = ListeningService.notification(context, content, public = false)
        assertEquals("Listening", shown.text(Notification.EXTRA_TITLE))
        val everything = listOfNotNull(shown, shown.publicVersion).flatMap { notification ->
            notification.extras.keySet().mapNotNull { key -> notification.extras.get(key)?.toString() }
        }
        assertEquals(emptyList<String>(), everything.filter { "dinner" in it || "cake" in it || "minutes ago" in it })
        assertNull(shown.text(Notification.EXTRA_TEXT))
        assertNull(shown.text(Notification.EXTRA_SUB_TEXT))
    }

    private fun assertCarriesOnlyTheStopButton(shown: Notification) {
        val actions = shown.actions
        assertEquals(1, actions?.size ?: 0)
        assertEquals(tr("עצירה", "Stop"), actions[0].title.toString())
        val pause = Intent(context, ListeningService::class.java).setAction(ListeningService.ACTION_PAUSE)
        val same = PendingIntent.getService(context, 1, pause, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(same)
        assertEquals(same, actions[0].actionIntent)
    }

    @Test
    fun theNotificationHasAStopButtonThatPausesListeningWithAndWithoutLockScreenCaptions() {
        val content = LockScreenCaptionContent(
            lines = listOf(LockScreenCaptionLine(speaker = null, text = "Hello", isFinal = true)),
            status = "Listening",
            ageNote = null,
        )
        assertCarriesOnlyTheStopButton(ListeningService.notification(context, content, public = true))
        assertCarriesOnlyTheStopButton(ListeningService.notification(context, content, public = false))
    }
}
