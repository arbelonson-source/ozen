package com.arbelonson.ozen.core

class SavingTroubleNotice {
    var isFailing: Boolean = false
        private set
    var settingsUnreadable: Boolean = false
        private set
    private var isDismissed: Boolean = false

    val shouldShow: Boolean get() = isFailing && !isDismissed

    fun update(settingsFailed: Boolean, historyFailed: Boolean, settingsUnreadable: Boolean = false) {
        val failing = settingsFailed || historyFailed
        if (!failing) isDismissed = false
        isFailing = failing
        this.settingsUnreadable = settingsFailed && settingsUnreadable && !historyFailed
    }

    val shownDetail: String get() = detailFor(settingsUnreadable)

    fun dismiss() {
        isDismissed = true
    }

    companion object {
        val title: String get() = tr("השמירה בטלפון נכשלה", "Saving on the phone failed")
        val detail: String
            get() = tr(
                "כנראה שהטלפון מלא. שיחות והגדרות חדשות לא יישמרו עד שיתפנה מקום. בקשו ממי שעוזר לכם עם הטלפון לפנות מקום (הגדרות ← אחסון).",
                "The phone is probably full. New conversations and settings won’t be saved until space frees up. Ask whoever helps you with the phone to free some (Settings → Storage).",
            )

        fun detailFor(settingsUnreadable: Boolean): String = if (settingsUnreadable) unreadableSettingsDetail else detail

        val unreadableSettingsDetail: String
            get() = tr(
                "האפליקציה לא מצליחה לקרוא כרגע את ההגדרות השמורות. הן לא אבדו: עד שאפשר יהיה לקרוא אותן, האפליקציה משתמשת בהגדרות הרגילות. שינויים שעושים עכשיו נשארים כל עוד האפליקציה פתוחה, ונשמרים עם השינוי הבא כשאפשר יהיה לקרוא אותן.",
                "Ozen can’t read its saved settings right now. They aren’t lost: until it can, Ozen uses the standard settings. Changes made now stay while Ozen is open, and are saved with the next change once it can read them.",
            )
    }
}
