package com.arbelonson.ozen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.arbelonson.ozen.core.tr

object NightAlerts {
    var batteryExempt: (Context) -> Boolean = { context ->
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    var openBatteryList: (Context) -> Unit = { context ->
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openAppInfo(context)
        }
    }

    var openAppInfo: (Context) -> Unit = { context ->
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    fun openItems(batteryExempt: Boolean, dndThrough: Boolean, soundsOn: Boolean): Int =
        listOf(batteryExempt, dndThrough, soundsOn).count { !it }

    fun soundsText(on: Boolean): String =
        if (on) {
            tr("השאירו את אוזן מאזינה כשהולכים לישון, כדי שתשמע צלילים כמו אזעקה.", "Leave Ozen listening when you go to sleep, so it hears sounds like a smoke alarm.")
        } else {
            tr("הפעילו את ״צלילים בבית״, והשאירו את אוזן מאזינה כשהולכים לישון.", "Turn on Sounds at home, and leave Ozen listening when you go to sleep.")
        }

    fun batteryText(exempt: Boolean): String =
        if (exempt) {
            tr("Android מאפשר לאוזן להמשיך לפעול בזמן שהטלפון במצב שינה.", "Android lets Ozen keep running while the phone sleeps.")
        } else {
            tr(
                "כדי לחסוך בסוללה, אנדרואיד עלול לעצור את אוזן כשהטלפון במצב שינה, וההתראות ייעצרו איתה. ברשימה שתיפתח בחרו למעלה ״כל האפליקציות״, מצאו את Ozen ובחרו ״ללא אופטימיזציה״.",
                "To save battery, Android may stop Ozen while the phone sleeps, and alerts stop with it. In the list that opens, choose All apps at the top, find Ozen and choose Don’t optimize.",
            )
        }

    fun dndText(through: Boolean, alarmsAllowed: Boolean): String =
        when {
            through -> tr("צלילים דחופים, כמו גלאי עשן, מגיעים גם ב״נא לא להפריע״.", "Urgent sounds, like a smoke alarm, come through Do Not Disturb.")
            !alarmsAllowed -> tr(
                "התראות ״חירום״ של אוזן כבויות בטלפון. הפעילו אותן, ואז אפשרו להן לעקוף את ״נא לא להפריע״.",
                "Ozen’s Emergency alerts are turned off in the phone. Turn them on, then let them override Do Not Disturb.",
            )
            else -> tr("ב״נא לא להפריע״ ייתכן שגלאי עשן לא יעיר אתכם. אפשרו להתראות ״חירום״ של אוזן לעקוף את ״נא לא להפריע״.", "In Do Not Disturb, a smoke alarm may not wake you. Let Ozen’s Emergency alerts override it.")
        }

    fun summary(open: Int): String =
        if (open == 0) tr("הכול מוכן", "All set") else tr("%1 לבדיקה", "%1 to check", listOf("$open"))

    fun makerAdvice(manufacturer: String, brand: String): String? {
        val names = (manufacturer + " " + brand).lowercase().split(Regex("[^a-z0-9]+")).toSet()
        return when {
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״חיסכון בסוללה״ ובחרו ״ללא הגבלות״, וחפשו את ״הפעלה אוטומטית״ והפעילו אותה.",
                "Look for Battery saver in Ozen’s app info and choose No restrictions; also look for Autostart and turn it on.",
            )
            "samsung" in names -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״סוללה״ ובחרו ״ללא הגבלה״, ובדקו ש־Ozen לא ברשימות ״אפליקציות במצב שינה״ או ״שינה עמוקה״.",
                "Look for Battery in Ozen’s app info and choose Unrestricted; make sure Ozen isn’t in the Sleeping apps or Deep sleeping apps lists.",
            )
            names.any { it in setOf("oppo", "realme", "oneplus") } -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״שימוש בסוללה״ ואפשרו פעילות ברקע.",
                "Look for Battery usage in Ozen’s app info and allow background activity.",
            )
            names.any { it in setOf("vivo", "iqoo") } -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״סוללה״ ואפשרו צריכת חשמל גבוהה ברקע.",
                "Look for Battery in Ozen’s app info and allow high background power use.",
            )
            names.any { it in setOf("huawei", "honor") } -> tr(
                "חפשו את ״הפעלת אפליקציות״, עברו לניהול ידני עבור Ozen, והפעילו את כל המתגים.",
                "Look for App launch, set Ozen to manage manually, and turn all the switches on.",
            )
            names.any { it in setOf("tecno", "infinix", "itel") } -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״סוללה״ ואפשרו פעילות ברקע, וב־Phone Master חפשו הפעלה אוטומטית ואפשרו אותה ל־Ozen.",
                "Look for Battery in Ozen’s app info and allow background activity; in Phone Master, look for auto-start and allow it for Ozen.",
            )
            else -> null
        }
    }
}
