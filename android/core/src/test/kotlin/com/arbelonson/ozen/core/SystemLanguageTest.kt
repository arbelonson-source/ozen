package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryDefaults : SystemLanguageDefaults {
    val values = mutableMapOf<String, Any>()

    override fun setStringList(key: String, value: List<String>) {
        values[key] = value
    }

    override fun setBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false

    override fun remove(key: String) {
        values.remove(key)
    }

    fun stored(): Any? = values[SystemLanguage.KEY]
}

private fun repositoryRoot(): File =
    File(assertNotNull(System.getProperty("ozen.fixtures"))).parentFile.parentFile

class SystemLanguageTest {
    @Test
    fun `every language Ozen offers names a localization the app ships, so iOS can switch to it`() {
        for (file in listOf("App/Ozen/InfoPlist.xcstrings", "App/Shared/Localizable.xcstrings")) {
            val catalog = Json.parseToJsonElement(File(repositoryRoot(), file).readText()).jsonObject
            val strings = assertNotNull(catalog["strings"] as? JsonObject)
            val shipped = mutableSetOf(catalog["sourceLanguage"]?.jsonPrimitive?.content ?: "")
            for (entry in strings.values) {
                shipped += (entry.jsonObject["localizations"] as? JsonObject)?.keys ?: emptySet()
            }
            for (language in AppLanguage.entries.filter { it != AppLanguage.System }) {
                val code = assertNotNull(language.bundleLocalization, "$language")
                assertTrue(code in shipped, "$file: $language -> $code")
            }
        }
        assertNull(AppLanguage.System.bundleLocalization)
    }

    @Test
    fun `the language is handed over in the one key iOS itself reads for an app's language`() {
        assertEquals("AppleLanguages", SystemLanguage.KEY)
    }

    @Test
    fun `the note that Ozen wrote the phone's language keeps its saved name, so an update still knows what to take back`() {
        assertEquals("OzenWroteAppleLanguages", SystemLanguage.WRITTEN_KEY)
    }

    @Test
    fun `a chosen language is handed to iOS, like the phone takes back only what Ozen wrote`() {
        val defaults = MemoryDefaults()

        SystemLanguage.apply(AppLanguage.English, defaults)
        assertEquals(listOf("en"), defaults.stored())
        SystemLanguage.apply(AppLanguage.Hebrew, defaults)
        assertEquals(listOf("he"), defaults.stored())
        SystemLanguage.apply(AppLanguage.System, defaults)
        assertNull(defaults.stored())

        defaults.setStringList(SystemLanguage.KEY, listOf("fr"))
        SystemLanguage.apply(AppLanguage.System, defaults)
        assertEquals(listOf("fr"), defaults.stored())
    }
}
