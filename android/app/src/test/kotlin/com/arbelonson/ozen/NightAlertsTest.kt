package com.arbelonson.ozen

import android.app.NotificationManager
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightAlertsTest {
    private fun advice(manufacturer: String, brand: String = manufacturer) =
        Localization.withLanguage(UILanguage.English) { NightAlerts.makerAdvice(manufacturer, brand) }

    @Test
    fun `xiaomi advice points to battery saver and autostart`() {
        val text = advice("Xiaomi")
        assertNotNull(text)
        assertTrue(text.contains("No restrictions") && text.contains("Autostart"), text)
    }

    @Test
    fun `samsung advice points to unrestricted and the sleeping lists`() {
        val text = advice("samsung")
        assertNotNull(text)
        assertTrue(text.contains("Unrestricted") && text.contains("Deep sleeping"), text)
    }

    @Test
    fun `oppo advice points to battery usage`() {
        val text = advice("OPPO")
        assertNotNull(text)
        assertTrue(text.contains("Battery usage"), text)
    }

    @Test
    fun `tecno and infinix share advice about phone master`() {
        val tecno = advice("TECNO MOBILE LIMITED", "TECNO")
        assertNotNull(tecno)
        assertTrue(tecno.contains("Phone Master"), tecno)
        assertEquals(tecno, advice("INFINIX MOBILITY LIMITED", "Infinix"))
    }

    @Test
    fun `the brand alone can name the family`() {
        val redmi = advice("Unknown Maker", "Redmi")
        assertEquals(advice("Xiaomi"), redmi)
        assertNotNull(advice("HMD Global", "vivo"))
    }

    @Test
    fun `other makers and an empty maker get no advice`() {
        assertNull(advice("Google"))
        assertNull(advice("", ""))
    }

    @Test
    fun `samsung advice names the app as the sleeping lists show it`() {
        val text = Localization.withLanguage(UILanguage.Hebrew) { NightAlerts.makerAdvice("samsung", "samsung") }
        assertNotNull(text)
        assertTrue(text.contains("ש־Ozen לא ברשימות"), text)
    }

    @Test
    fun `open items count the battery and Do Not Disturb, not the maker menu`() {
        assertEquals(0, NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = false, dndThrough = true, soundsOn = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = false, soundsOn = true))
        assertEquals(2, NightAlerts.openItems(batteryExempt = false, dndThrough = false, soundsOn = true))
    }

    @Test
    fun `Ozen's notifications turned off in the phone's settings are one more to check, as on the iPhone`() {
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = true, notificationsAllowed = false))
        assertEquals("1 to check", Localization.withLanguage(UILanguage.English) { NightAlerts.summary(NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = true, notificationsAllowed = false)) })
        assertEquals(4, NightAlerts.openItems(batteryExempt = false, dndThrough = false, soundsOn = false, notificationsAllowed = false))
    }

    @Test
    fun `sounds at home turned off is one more to check`() {
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = false))
        assertEquals(3, NightAlerts.openItems(batteryExempt = false, dndThrough = false, soundsOn = false))
    }

    @Test
    fun `the sounds step says to leave Ozen listening, and to turn sounds on while they are off`() {
        Localization.withLanguage(UILanguage.English) {
            val off = NightAlerts.soundsText(on = false)
            assertTrue(off.contains("Turn on Sounds at home") && off.contains("leave Ozen listening"), off)
            val on = NightAlerts.soundsText(on = true)
            assertTrue(on.contains("leave Ozen listening", ignoreCase = true) && !on.contains("Turn on"), on)
        }
    }

    @Test
    fun `battery advice says what to pick in the list`() {
        Localization.withLanguage(UILanguage.English) {
            val text = NightAlerts.batteryText(exempt = false, sdk = 34)
            assertTrue(text.contains("All apps") && text.contains("Ozen") && text.contains("Don’t optimize"), text)
            assertTrue(text.startsWith("To save battery, Android may stop Ozen"), text)
            val newer = NightAlerts.batteryText(exempt = false, sdk = 35)
            assertTrue(newer.contains("tap Ozen") && newer.contains("Allow background usage") && newer.contains("Unrestricted") && !newer.contains("Don’t optimize"), newer)
            assertTrue(newer.startsWith("To save battery, Android may stop Ozen"), newer)
            assertEquals("Android lets Ozen keep running while the phone sleeps.", NightAlerts.batteryText(exempt = true))
        }
        val hebrew = Localization.withLanguage(UILanguage.Hebrew) { NightAlerts.batteryText(exempt = false, sdk = 34) }
        assertTrue(hebrew.contains("מצאו את Ozen"), hebrew)
        val hebrewNewer = Localization.withLanguage(UILanguage.Hebrew) { NightAlerts.batteryText(exempt = false, sdk = 35) }
        assertTrue(hebrewNewer.contains("הקישו על Ozen"), hebrewNewer)
    }

    @Test
    fun `do not disturb advice names Emergency alerts turned off in the phone as the cause`() {
        val none = NotificationManager.IMPORTANCE_NONE
        val high = NotificationManager.IMPORTANCE_HIGH
        assertFalse(AlertNotifier.alarmsOn(notificationsOn = true, importance = none))
        assertFalse(AlertNotifier.alarmsOn(notificationsOn = false, importance = high))
        assertTrue(AlertNotifier.alarmsOn(notificationsOn = true, importance = high))
        assertTrue(AlertNotifier.alarmsOn(notificationsOn = true, importance = null))
        Localization.withLanguage(UILanguage.English) {
            val off = NightAlerts.dndText(through = false, alarmsAllowed = false)
            assertTrue(off.contains("turned off") && off.contains("Turn them on"), off)
            val override = NightAlerts.dndText(through = false, alarmsAllowed = true)
            assertTrue(!override.contains("turned off") && override.contains("override"), override)
            assertEquals("Urgent sounds, like a smoke alarm, come through Do Not Disturb.", NightAlerts.dndText(through = true, alarmsAllowed = true))
        }
    }

    private class Labels(
        val language: UILanguage,
        val allApps: String,
        val dontOptimize: String,
        val allowBackground: String,
        val unrestricted: String,
        val overrideDnd: String,
    )

    // As Android's own Settings app names them in each language (res/values-*/strings.xml:
    // filter_all_apps, ignore_optimizations_on, manager_battery_usage_allow_background_usage_title,
    // manager_battery_usage_unrestricted_title, app_notification_override_dnd_title).
    private val androidLabels = listOf(
        Labels(UILanguage.Hebrew, "כל האפליקציות", "אין לבצע אופטימיזציה", "הרשאה לשימוש ברקע", "ללא הגבלות", "התעלמות ממצב 'נא לא להפריע'"),
        Labels(UILanguage.English, "All apps", "Don’t optimize", "Allow background usage", "Unrestricted", "Override Do Not Disturb"),
        Labels(UILanguage.Arabic, "جميع التطبيقات", "عدم تحسين", "السماح بالاستخدام في الخلفية", "الاستخدام غير المحدّد بقيود", "تجاوز وضع \"عدم الإزعاج\""),
        Labels(UILanguage.Russian, "Все приложения", "Не экономить", "Разрешить использование в фоновом режиме", "Без ограничений", "Уведомления в режиме \"Не беспокоить\""),
        Labels(UILanguage.Amharic, "ሁሉም መተግበሪያዎች", "አታትባ", "የዳራ አጠቃቀምን ይፍቀዱ", "ያልተገደበ", "«አትረብሽ»ን ሻር"),
        Labels(UILanguage.French, "Toutes les applis", "Ne pas optimiser", "Autoriser l'utilisation en arrière-plan", "Sans restriction", "Ignorer Ne pas déranger"),
        Labels(UILanguage.Spanish, "Todas las aplicaciones", "No optimizar", "Permitir uso en segundo plano", "Sin restricciones", "Ignorar modo No molestar"),
        Labels(UILanguage.Ukrainian, "Усі додатки", "Не оптимізувати", "Дозволити використання у фоновому режимі", "Без обмежень", "Дозволити в режимі \"Не турбувати\""),
        Labels(UILanguage.German, "Alle Apps", "Nicht optimieren", "Hintergrundnutzung zulassen", "Uneingeschränkt", "„Bitte nicht stören“ ignorieren"),
        Labels(UILanguage.Portuguese, "Todas as apps", "Não otimizar", "Permitir utilização em segundo plano", "Sem restrições", "Ignorar o modo Não incomodar"),
        Labels(UILanguage.ChineseSimplified, "所有应用", "不优化", "允许后台使用", "无限制", "覆盖“勿扰”设置"),
        Labels(UILanguage.Hindi, "सभी ऐप्लिकेशन", "ऑप्‍टिमाइज़ ना करें", "बैकग्राउंड में ऐप्लिकेशन के चलने की सुविधा चालू करें", "बैटरी के इस्तेमाल पर कोई पाबंदी नहीं", "'परेशान न करें' मोड को बायपास करें"),
    )

    private fun words(text: String) = text.filterNot { it in "\"'“”„«»״‘’" }

    @Test
    fun `the battery and Do Not Disturb steps name the switches as Android does in each language and version`() {
        for (labels in androidLabels) {
            Localization.withLanguage(labels.language) {
                val battery = words(NightAlerts.batteryText(exempt = false, sdk = 34))
                assertTrue(battery.contains(words(labels.allApps)), "${labels.language}: $battery")
                assertTrue(battery.contains(words(labels.dontOptimize)), "${labels.language}: $battery")
                val newer = words(NightAlerts.batteryText(exempt = false, sdk = 35))
                assertTrue(newer.contains(words(labels.allowBackground)), "${labels.language}, Android 15: $newer")
                assertTrue(newer.contains(words(labels.unrestricted)), "${labels.language}, Android 15: $newer")
                for (allowed in listOf(true, false)) {
                    val dnd = words(NightAlerts.dndText(through = false, alarmsAllowed = allowed))
                    assertTrue(dnd.contains(words(labels.overrideDnd)), "${labels.language}, alerts allowed $allowed: $dnd")
                }
            }
        }
    }

    @Test
    fun `the row says all set or how many to check`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("All set", NightAlerts.summary(0))
            assertEquals("1 to check", NightAlerts.summary(1))
            assertEquals("2 to check", NightAlerts.summary(2))
        }
    }
}
