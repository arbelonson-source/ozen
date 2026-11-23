package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsStore
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsHolder(
    private val store: SettingsStore,
    private val writer: Executor = backgroundWriter(),
    private val save: (AppSettings) -> AppSettings = store::saveTakingUp,
) {
    private class Edit(val apply: (AppSettings) -> Unit, val asked: Boolean)

    private val state = MutableStateFlow(store.load())
    val current: StateFlow<AppSettings> = state.asStateFlow()
    val saveFailed = MutableStateFlow(false)
    val settingsUnreadable = MutableStateFlow(store.isUnreadable)
    private val takenUpFrom = MutableSharedFlow<AppSettings>(extraBufferCapacity = 8)
    val takenUp: SharedFlow<AppSettings> = takenUpFrom.asSharedFlow()
    private val oneChangeAtATime = Any()
    private val oneWriteAtATime = Any()
    private val unwritten = ArrayList<Edit>()
    private var writeAsked = false
    private var lastSaved = true

    fun change(edit: (AppSettings) -> Unit) {
        if (add(edit, asked = true)) writer.execute(::writeIfAsked)
    }

    fun waitForWrites(): Boolean = synchronized(oneWriteAtATime) {
        val waiting = synchronized(oneChangeAtATime) { unwritten.isNotEmpty() }
        if (waiting) written() else lastSaved
    }

    private fun writeIfAsked() {
        synchronized(oneWriteAtATime) { if (synchronized(oneChangeAtATime) { writeAsked }) written() }
    }

    fun changeAndSave(edit: (AppSettings) -> Unit): Boolean = changeInTurn(edit)()

    fun changeInTurn(edit: (AppSettings) -> Unit): () -> Boolean {
        add(edit, asked = false)
        return ::written
    }

    private fun add(edit: (AppSettings) -> Unit, asked: Boolean): Boolean = synchronized(oneChangeAtATime) {
        state.value = state.value.copy().also(edit)
        unwritten += Edit(edit, asked)
        val first = asked && !writeAsked
        if (asked) writeAsked = true
        first
    }

    private fun written(): Boolean {
        var before: AppSettings? = null
        var askAgain = false
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
                writeAsked = unwritten.any { it.asked }
                askAgain = writeAsked
                if (held != null && held != next) {
                    state.value = held.copy().also { settings -> since.forEach { it.apply(settings) } }
                    before = next
                }
            }
            settingsUnreadable.value = store.isUnreadable
            saveFailed.value = held == null
            lastSaved = held != null
            lastSaved
        }
        if (askAgain) writer.execute(::writeIfAsked)
        before?.let(takenUpFrom::tryEmit)
        return saved
    }
}

private fun backgroundWriter(): Executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
    Thread(task, "ozen-settings-writer").apply { isDaemon = true }
}.apply { allowCoreThreadTimeOut(true) }
