package com.arbelonson.ozen.whisper

import android.content.Context
import com.arbelonson.ozen.core.ModelLoadCrash

interface MarkerStore {
    fun read(): String?

    fun write(value: String)

    fun clear()
}

class PreferencesMarkerStore(context: Context) : MarkerStore {
    private val preferences = context.getSharedPreferences("ozen-model-load", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY, value).commit()
    }

    override fun clear() {
        preferences.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "loading"
    }
}

/**
 * Written, synchronously, right before whisper.cpp opens a model file and
 * cleared once the app lived through the load and the first pass. Android's
 * low-memory killer cannot be caught, so a mark found at the next start means
 * the app was ended while it set that model up.
 */
class ModelLoadMarker(private val store: MarkerStore) {
    fun begin(file: String) = store.write(ModelLoadCrash.marker(file))

    fun clear() = store.clear()

    fun take(): String? = store.read().also { if (it != null) store.clear() }
}
