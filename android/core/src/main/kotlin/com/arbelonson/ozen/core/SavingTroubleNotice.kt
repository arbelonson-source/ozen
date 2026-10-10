package com.arbelonson.ozen.core

class SavingTroubleNotice {
    var isFailing: Boolean = false
        private set
    private var isDismissed: Boolean = false

    val shouldShow: Boolean get() = isFailing && !isDismissed

    fun update(settingsFailed: Boolean, historyFailed: Boolean) {
        val failing = settingsFailed || historyFailed
        if (!failing) isDismissed = false
        isFailing = failing
    }

    fun dismiss() {
        isDismissed = true
    }

    companion object {
        val title: String get() = tr("השמירה בטלפון נכשלה", "Saving on the phone failed")
        val detail: String
            get() = tr(
                "כנראה שהטלפון מלא. שיחות והגדרות חדשות לא יישמרו עד שיתפנה מקום. בקשו ממי שעוזר לך עם הטלפון לפנות מקום (הגדרות ← כללי ← אחסון iPhone).",
                "The phone is probably full. New conversations and settings won't be saved until space frees up. Ask whoever helps you with the phone to free some (Settings → General → iPhone Storage).",
            )
    }
}
