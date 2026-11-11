package com.arbelonson.ozen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.arbelonson.ozen.core.Localization
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

    fun openItems(batteryExempt: Boolean, dndThrough: Boolean, soundsOn: Boolean, notificationsAllowed: Boolean = true): Int =
        listOf(batteryExempt, dndThrough, soundsOn, notificationsAllowed).count { !it }

    fun soundsText(on: Boolean): String =
        if (on) {
            tr("השאירו את אוזן מאזינה כשהולכים לישון, כדי שתשמע צלילים כמו אזעקה.", "Leave Ozen listening when you go to sleep, so it hears sounds like a smoke alarm.")
        } else {
            tr("הפעילו את ״צלילים בבית״, והשאירו את אוזן מאזינה כשהולכים לישון.", "Turn on Sounds at home, and leave Ozen listening when you go to sleep.")
        }

    /**
     * Android 15 opens a different list for the same request: an app's own
     * battery page, where Ozen is let run under Allow background usage, not
     * the older list's Don't optimize.
     *
     * Each switch is named as the phone's Settings app writes it, in the
     * phone's language ([phoneLabel], from [SystemLabels]); Ozen's language
     * can be another, and then its own names would not be on the screen.
     */
    fun batteryText(exempt: Boolean, sdk: Int = Build.VERSION.SDK_INT, phoneLabel: (String) -> String? = { null }): String {
        if (exempt) return tr("Android מאפשר לאוזן להמשיך לפעול בזמן שהטלפון במצב שינה.", "Android lets Ozen keep running while the phone sleeps.")
        if (sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val allow = label(SystemLabels.ALLOW_BACKGROUND, tr("הרשאה לשימוש ברקע", "Allow background usage"), phoneLabel)
            val unrestricted = label(SystemLabels.UNRESTRICTED, tr("ללא הגבלות", "Unrestricted"), phoneLabel)
            return tr(
                "כדי לחסוך בסוללה, אנדרואיד עלול לעצור את אוזן כשהטלפון במצב שינה, וההתראות ייעצרו איתה. ברשימה שתיפתח הקישו על Ozen, אחר כך על ״%1״, ובחרו ״%2״.",
                "To save battery, Android may stop Ozen while the phone sleeps, and alerts stop with it. In the list that opens, tap Ozen, then %1, and choose %2.",
                listOf(allow, unrestricted),
            )
        }
        val allApps = label(SystemLabels.ALL_APPS, tr("כל האפליקציות", "All apps"), phoneLabel)
        val dontOptimize = label(SystemLabels.DONT_OPTIMIZE, tr("אין לבצע אופטימיזציה", "Don’t optimize"), phoneLabel)
        return tr(
            "כדי לחסוך בסוללה, אנדרואיד עלול לעצור את אוזן כשהטלפון במצב שינה, וההתראות ייעצרו איתה. ברשימה שתיפתח בחרו למעלה ״%1״, מצאו את Ozen ובחרו ״%2״.",
            "To save battery, Android may stop Ozen while the phone sleeps, and alerts stop with it. In the list that opens, choose %1 at the top, find Ozen and choose %2.",
            listOf(allApps, dontOptimize),
        )
    }

    fun dndText(through: Boolean, alarmsAllowed: Boolean, phoneLabel: (String) -> String? = { null }): String {
        if (through) return tr("צלילים דחופים, כמו גלאי עשן, מגיעים גם ב״נא לא להפריע״.", "Urgent sounds, like a smoke alarm, come through Do Not Disturb.")
        val override = label(SystemLabels.OVERRIDE_DND, tr("התעלמות ממצב 'נא לא להפריע'", "Override Do Not Disturb"), phoneLabel)
        return if (!alarmsAllowed) {
            tr(
                "התראות ״חירום״ של אוזן כבויות בטלפון. הפעילו אותן, ובאותו דף הפעילו גם את ״%1״.",
                "Ozen’s Emergency alerts are turned off in the phone. Turn them on, then turn on %1 on the same page.",
                listOf(override),
            )
        } else {
            tr(
                "ב״נא לא להפריע״ ייתכן שגלאי עשן לא יעיר אתכם. אפשרו להתראות ״חירום״ של אוזן לעקוף את ״נא לא להפריע״: בדף שייפתח הפעילו את ״%1״.",
                "In Do Not Disturb, a smoke alarm may not wake you. Let Ozen’s Emergency alerts override it: on the page that opens, turn on %1.",
                listOf(override),
            )
        }
    }

    private fun label(name: String, own: String, phoneLabel: (String) -> String?): String =
        phoneLabel(name)?.let { SystemLabels.inSentence(it, Localization.language.isRightToLeft) } ?: own

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
