package com.arbelonson.ozen

import android.content.Context

interface SoundsOnlyStore {
    var chosen: Boolean
}

class PreferencesSoundsOnlyStore(context: Context) : SoundsOnlyStore {
    private val preferences = context.getSharedPreferences("ozen-sounds-only", Context.MODE_PRIVATE)

    override var chosen: Boolean
        get() = runCatching { preferences.getBoolean(KEY, false) }.getOrDefault(false)
        set(value) {
            runCatching { preferences.edit().putBoolean(KEY, value).apply() }
        }

    private companion object {
        const val KEY = "chosen"
    }
}
