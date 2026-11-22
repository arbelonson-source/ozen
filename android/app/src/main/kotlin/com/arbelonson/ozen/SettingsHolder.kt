package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsHolder(private val store: SettingsStore) {
    private val state = MutableStateFlow(store.load())
    val current: StateFlow<AppSettings> = state.asStateFlow()
    val saveFailed = MutableStateFlow(false)
    val settingsUnreadable = MutableStateFlow(store.isUnreadable)
    private val takenUpFrom = MutableSharedFlow<AppSettings>(extraBufferCapacity = 8)
    val takenUp: SharedFlow<AppSettings> = takenUpFrom.asSharedFlow()
    private val oneChangeAtATime = Any()

    fun change(edit: (AppSettings) -> Unit) {
        changeAndSave(edit)
    }

    fun changeAndSave(edit: (AppSettings) -> Unit): Boolean {
        var before: AppSettings? = null
        val saved = synchronized(oneChangeAtATime) {
            val next = state.value.copy().also(edit)
            state.value = next
            val saved = try {
                val held = store.saveTakingUp(next)
                if (held != next) {
                    state.value = held
                    before = next
                }
                true
            } catch (_: Exception) {
                false
            }
            settingsUnreadable.value = store.isUnreadable
            saveFailed.value = !saved
            saved
        }
        before?.let(takenUpFrom::tryEmit)
        return saved
    }
}
