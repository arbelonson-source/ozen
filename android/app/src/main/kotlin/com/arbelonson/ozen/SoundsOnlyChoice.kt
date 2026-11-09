package com.arbelonson.ozen

import android.content.Context
import android.content.SharedPreferences

interface SoundsOnlyStore {
    var chosen: Boolean
}

class PreferencesSoundsOnlyStore(private val preferences: SharedPreferences) : SoundsOnlyStore {
    constructor(context: Context) : this(context.getSharedPreferences("ozen-sounds-only", Context.MODE_PRIVATE))

    override var chosen: Boolean
        get() = runCatching { preferences.getBoolean(KEY, false) }.getOrDefault(false)
        set(value) {
            runCatching { preferences.edit().putBoolean(KEY, value).commit() }
        }

    private companion object {
        const val KEY = "chosen"
    }
}
