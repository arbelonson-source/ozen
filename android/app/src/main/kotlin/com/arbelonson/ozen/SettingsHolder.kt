package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsHolder(
    private val store: SettingsStore,
    private val save: (AppSettings) -> AppSettings = store::saveTakingUp,
) {
    private val state = MutableStateFlow(store.load())
    val current: StateFlow<AppSettings> = state.asStateFlow()
    val saveFailed = MutableStateFlow(false)
    val settingsUnreadable = MutableStateFlow(store.isUnreadable)
    private val takenUpFrom = MutableSharedFlow<AppSettings>(extraBufferCapacity = 8)
    val takenUp: SharedFlow<AppSettings> = takenUpFrom.asSharedFlow()
    private val oneChangeAtATime = Any()
    private val oneWriteAtATime = Any()
    private val unwritten = ArrayList<(AppSettings) -> Unit>()
    private val writeAsked = AtomicBoolean(false)
    private var lastSaved = true
    private val writer = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "ozen-settings-writer").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    fun change(edit: (AppSettings) -> Unit) {
        changeInTurn(edit)
        if (writeAsked.compareAndSet(false, true)) writer.execute { waitForWrites() }
    }

    fun waitForWrites(): Boolean = synchronized(oneWriteAtATime) {
        if (writeAsked.getAndSet(false)) written() else lastSaved
    }

    fun changeAndSave(edit: (AppSettings) -> Unit): Boolean = changeInTurn(edit)()

    fun changeInTurn(edit: (AppSettings) -> Unit): () -> Boolean {
        synchronized(oneChangeAtATime) {
            state.value = state.value.copy().also(edit)
            unwritten += edit
        }
        return ::written
    }

    private fun written(): Boolean {
        var before: AppSettings? = null
        val saved = synchronized(oneWriteAtATime) {
            val (next, taken) = synchronized(oneChangeAtATime) { state.value to unwritten.size }
            val held = try {
                save(next)
            } catch (_: Exception) {
                null
            }
            synchronized(oneChangeAtATime) {
                val since = unwritten.drop(taken)
                unwritten.subList(0, taken).clear()
                if (held != null && held != next) {
                    state.value = held.copy().also { settings -> since.forEach { it(settings) } }
                    before = next
                }
            }
            settingsUnreadable.value = store.isUnreadable
            saveFailed.value = held == null
            lastSaved = held != null
            lastSaved
        }
        before?.let(takenUpFrom::tryEmit)
        return saved
    }
}
