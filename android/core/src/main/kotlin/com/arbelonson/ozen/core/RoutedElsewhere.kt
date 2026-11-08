package com.arbelonson.ozen.core

data class RoutedElsewhere(val picked: AudioInputDescriptor, val inUse: AudioInputDescriptor) {
    val title: String get() = titleFor(picked)

    val detail: String get() = detailFor(inUse)

    companion object {
        const val SETTLE_SECONDS = 2

        fun titleFor(picked: AudioInputDescriptor): String =
            tr("המיקרופון ⁨%1⁩ לא מקליט", "%1 isn't recording", listOf(picked.portName))

        fun detailFor(inUse: AudioInputDescriptor): String = tr(
            "הכתוביות מגיעות במקום זאת דרך ⁨%1⁩. מיקרופוני Bluetooth לא עובדים בכל הטלפונים, אבל מיקרופון עם כבל או USB-C עובד.",
            "Captions come from %1 instead. Bluetooth microphones don't work with every phone; a wired or USB-C microphone does.",
            listOf(inUse.portName),
        )
    }
}
