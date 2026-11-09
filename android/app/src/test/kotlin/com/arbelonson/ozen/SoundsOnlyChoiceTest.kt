package com.arbelonson.ozen

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class DiskPreferences(val disk: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = disk.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = disk[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = disk[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = disk[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = disk[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = disk[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key in disk
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            changes[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor {
            disk.keys.forEach { changes[it] = null }
            return this
        }

        override fun commit(): Boolean {
            disk.putAll(changes)
            return true
        }

        override fun apply() = Unit
    }
}

class SoundsOnlyChoiceTest {
    @Test
    fun `the choice is on disk as soon as it is made, so a process killed right after restarts into sounds alone`() {
        val preferences = DiskPreferences()
        PreferencesSoundsOnlyStore(preferences).chosen = true

        val afterKill = PreferencesSoundsOnlyStore(DiskPreferences(preferences.disk))
        assertTrue(afterKill.chosen)

        afterKill.chosen = false
        assertFalse(PreferencesSoundsOnlyStore(DiskPreferences(preferences.disk)).chosen)
    }

    @Test
    fun `sounds alone asks for the same permissions as captions, notifications included from Android 13`() {
        assertEquals(listOf("android.permission.RECORD_AUDIO"), ListeningPermissions.wanted(32))
        assertEquals(listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"), ListeningPermissions.wanted(33))
    }
}
