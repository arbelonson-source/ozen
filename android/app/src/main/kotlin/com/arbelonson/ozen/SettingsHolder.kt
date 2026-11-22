package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsFileUnreadableException
import com.arbelonson.ozen.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsHolder(private val store: SettingsStore) {
    private val state = MutableStateFlow(store.load())
    val current: StateFlow<AppSettings> = state.asStateFlow()
    val saveFailed = MutableStateFlow(false)
    val saveFailedUnreadable = MutableStateFlow(false)
    private val oneChangeAtATime = Any()

    fun change(edit: (AppSettings) -> Unit) {
        changeAndSave(edit)
    }

    fun changeAndSave(edit: (AppSettings) -> Unit): Boolean = synchronized(oneChangeAtATime) {
        val next = state.value.copy().also(edit)
        state.value = next
        var unreadable = false
        val saved = try {
            store.save(next)
            true
        } catch (error: Exception) {
            unreadable = error is SettingsFileUnreadableException
            false
        }
        saveFailedUnreadable.value = unreadable
        saveFailed.value = !saved
        saved
    }
}
