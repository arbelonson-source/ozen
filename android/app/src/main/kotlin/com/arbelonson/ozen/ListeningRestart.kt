package com.arbelonson.ozen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arbelonson.ozen.core.AlertNotificationContent
import com.arbelonson.ozen.core.TimeArticle
import com.arbelonson.ozen.core.tr
import java.time.ZoneId

object ListeningRestart {
    private const val FUTURE_SLACK_SECONDS = 60.0

    data class Notice(val stoppedAt: Double) {
        fun title(): String = tr("התראות הקול כבויות", "Sound alerts are off")

        fun text(zone: ZoneId = ZoneId.systemDefault()): String {
            val time = clockTime(stoppedAt, zone)
            val text = tr(
                "ההאזנה נעצרה בערך ב־%1 כי הטלפון הופעל מחדש או ש־Ozen עודכן. התראות הקול והכתוביות כבויות. אפשר להקיש כדי להפעיל אותן שוב.",
                "Listening stopped at about %1 because the phone restarted or Ozen was updated. Sound alerts and captions are off. Tap to turn them back on.",
                listOf(time),
            )
            return TimeArticle.agreeing(text, time)
        }
    }

    fun notice(raw: String?, now: Double): Notice? {
        val record = ListeningCut.parse(raw) ?: return null
        if (record.aliveAt > now + FUTURE_SLACK_SECONDS) return null
        return Notice(record.aliveAt)
    }

    fun isRestartAction(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

    fun tell(context: Context, store: ListeningRecordStore = PreferencesListeningStore(context), now: Double = System.currentTimeMillis() / 1_000.0) {
        val notice = notice(runCatching { store.read() }.getOrNull(), now) ?: return
        val app = context.applicationContext as OzenApplication
        if (!app.settings.current.value.notifyWhenInBackground) return
        StartCaptionsRequest.applyAppLanguage(app.settings)
        val content = AlertNotificationContent(CaptionSession.LISTENING_CUT_IDENTIFIER, notice.title(), notice.text(), "status", isUrgent = false)
        AlertNotifier(context).post(content)
    }
}

class ListeningRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ListeningRestart.isRestartAction(intent.action)) return
        val pending = goAsync()
        Thread({
            try {
                runCatching { ListeningRestart.tell(context) }
            } finally {
                pending.finish()
            }
        }, "listening-restart").start()
    }
}
