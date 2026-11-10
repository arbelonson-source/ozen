package com.arbelonson.ozen

import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr

internal object SoundAlertsText {
    fun safetyNote(language: UILanguage): String = tr(
        "אוזן היא כלי עזר, ולא מערכת התרעה מאושרת. אל תסתמכו עליה במצבים שבהם אפשר להיפגע או בשעת חירום: השאירו את גלאי העשן במקומם, ואת ההתרעות של פיקוד העורף פעילות.",
        "Ozen is a helper, not a certified alarm. Don’t rely on it where you could be hurt or in an emergency: keep your smoke alarms, and keep official emergency alerts turned on.",
        language,
    )

    fun silencedGroupNote(alertsOn: Boolean, floorName: String): String = if (alertsOn) {
        tr("על הצלילים האלה לא תקבלו התראה עכשיו, כי למעלה נבחר ״%1״.", "You won’t be alerted to these sounds now, because “%1” is chosen above.", listOf(floorName))
    } else {
        tr("ההתראות על צלילים כבויות, ולכן לא תקבלו התראה על הצלילים האלה.", "Sound alerts are off, so you won’t be alerted to these sounds.")
    }
}
