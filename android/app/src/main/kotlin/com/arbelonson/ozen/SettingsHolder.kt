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

    fun changeAndSave(edit: (AppSettings) -> Unit): Boolean = saving { edited(edit) }

    fun changeInTurn(edit: (AppSettings) -> Unit): () -> Boolean {
        synchronized(oneChangeAtATime) { edited(edit) }
        return { saving {} }
    }

    private fun edited(edit: (AppSettings) -> Unit) {
        state.value = state.value.copy().also(edit)
    }

    private fun saving(edit: () -> Unit): Boolean {
        var before: AppSettings? = null
        val saved = synchronized(oneChangeAtATime) {
            edit()
            val next = state.value
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
