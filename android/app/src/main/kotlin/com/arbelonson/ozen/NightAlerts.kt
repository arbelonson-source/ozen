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

    fun openItems(batteryExempt: Boolean, dndThrough: Boolean): Int = listOf(batteryExempt, dndThrough).count { !it }

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
                "חפשו בפרטי האפליקציה של אוזן את ״סוללה״ ובחרו ״ללא הגבלה״, ובדקו שאוזן לא ברשימות ״אפליקציות במצב שינה״ או ״שינה עמוקה״.",
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
                "חפשו את ״הפעלת אפליקציות״, עברו לניהול ידני עבור אוזן, והפעילו את כל המתגים.",
                "Look for App launch, set Ozen to manage manually, and turn all the switches on.",
            )
            names.any { it in setOf("tecno", "infinix", "itel") } -> tr(
                "חפשו בפרטי האפליקציה של אוזן את ״סוללה״ ואפשרו פעילות ברקע, וב־Phone Master חפשו הפעלה אוטומטית ואפשרו אותה לאוזן.",
                "Look for Battery in Ozen’s app info and allow background activity; in Phone Master, look for auto-start and allow it for Ozen.",
            )
            else -> null
        }
    }
}
