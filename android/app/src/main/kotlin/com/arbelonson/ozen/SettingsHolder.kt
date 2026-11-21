package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsHolder(private val store: SettingsStore) {
    private val state = MutableStateFlow(store.load())
    val current: StateFlow<AppSettings> = state.asStateFlow()
    val saveFailed = MutableStateFlow(false)
    private val oneChangeAtATime = Any()

    fun change(edit: (AppSettings) -> Unit) {
        synchronized(oneChangeAtATime) {
            val next = state.value.copy().also(edit)
            state.value = next
            saveFailed.value = try {
                store.save(next)
                false
            } catch (_: Exception) {
                true
            }
        }
    }
}
