package com.arbelonson.ozen.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AwayAlertsNoticeTest {
    private val table = Json.parseToJsonElement(File(System.getProperty("ozen.fixtures"), "logic/away-alerts-blocked.json").readText(Charsets.UTF_8)).jsonObject

    @Test
    fun `the caption screen warns only when away alerts are on and notifications are off, as on the other phone`() {
        val cases = table.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { item ->
            val on = item.getValue("awayAlertsOn").jsonPrimitive.boolean
            val allowed = item.getValue("notificationsAllowed").jsonPrimitive.boolean
            val wanted = item.getValue("blocked").jsonPrimitive.boolean
            if (AwayAlertsNotice.blocked(on, allowed) == wanted) null else "on=$on allowed=$allowed: wanted $wanted"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertEquals(4, cases.size)
    }

    @Test
    fun `the warning says in the same English words on both phones that alerts will not arrive`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(table.getValue("title").jsonPrimitive.content, AwayAlertsNotice.title())
        assertEquals(table.getValue("detail").jsonPrimitive.content, AwayAlertsNotice.detail())
    }
}
