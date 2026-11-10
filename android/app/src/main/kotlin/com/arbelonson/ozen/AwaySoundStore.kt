package com.arbelonson.ozen

import android.content.Context
import android.content.SharedPreferences

interface AwaySoundStore {
    var record: String?
}

class PreferencesAwaySoundStore(private val preferences: SharedPreferences) : AwaySoundStore {
    constructor(context: Context) : this(context.getSharedPreferences("ozen-away-sound", Context.MODE_PRIVATE))

    override var record: String?
        get() = runCatching { preferences.getString(KEY, null) }.getOrNull()
        set(value) {
            runCatching { preferences.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.commit() }
        }

    private companion object {
        const val KEY = "record"
    }
}
