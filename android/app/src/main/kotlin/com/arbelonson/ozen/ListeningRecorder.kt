package com.arbelonson.ozen

import android.content.Context
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface ListeningRecordStore {
    fun read(): String?

    fun write(value: String)

    fun clear()
}

class PreferencesListeningStore(context: Context) : ListeningRecordStore {
    private val preferences = context.getSharedPreferences("ozen-listening", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY, value).commit()
    }

    override fun clear() {
        preferences.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "record"
    }
}

class ListeningRecorder(
    private val store: ListeningRecordStore,
    private val version: Long,
    private val now: () -> Double = { System.currentTimeMillis() / 1_000.0 },
    private val background: Executor = Executors.newSingleThreadExecutor { Thread(it, "listening-record").apply { isDaemon = true } },
) {
    @Volatile private var record: ListeningCut.Record? = null

    fun phaseChanged(phase: PipelinePhase, recovering: Boolean) {
        when {
            phase == PipelinePhase.Listening -> if (record == null) begin()
            phase == PipelinePhase.Idle || phase == PipelinePhase.Paused || (phase is PipelinePhase.Failed && !recovering) -> end()
        }
    }

    fun phaseChanged(phase: PipelinePhase, stopped: StoppedCaptionsNotice.Cause?) = phaseChanged(phase, recovering = stopped == null)

    fun keepAlive() {
        val current = record ?: return
        save(current.copy(aliveAt = now()))
    }

    fun take(): ListeningCut.Notice? {
        val raw = runCatching { store.read() }.getOrNull() ?: return null
        runCatching { background.execute { runCatching { store.clear() } } }
        return ListeningCut.notice(ListeningCut.parse(raw), now(), version)
    }

    private fun begin() {
        val at = now()
        save(ListeningCut.Record(at, at, version))
    }

    private fun end() {
        if (record == null) return
        record = null
        runCatching { background.execute { runCatching { store.clear() } } }
    }

    private fun save(next: ListeningCut.Record) {
        record = next
        runCatching { background.execute { runCatching { store.write(ListeningCut.encode(next)) } } }
    }
}
