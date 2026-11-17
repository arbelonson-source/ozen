package com.arbelonson.ozen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Re-says the names of the channels the app already made, in the language it
 * speaks now. Android keeps the importance, sound and vibration the person
 * set on an existing channel and takes only the new name, so nothing they
 * chose is undone; a channel never made is not made here.
 */
internal object NotificationChannelNames {
    fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        refresh(
            listOf(AlertNotifier.alertsChannel(), AlertNotifier.alarmChannel(), ListeningService.listeningChannel(), ModelDownloadService.downloadChannel()),
            { it.id },
            { manager.getNotificationChannel(it) != null },
            manager::createNotificationChannel,
        )
    }

    internal fun <C> refresh(channels: List<C>, idOf: (C) -> String, exists: (String) -> Boolean, update: (C) -> Unit) {
        channels.filter { exists(idOf(it)) }.forEach(update)
    }
}
