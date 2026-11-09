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
    @Volatile private var leftover = Leftover.None

    fun phaseChanged(phase: PipelinePhase, recovering: Boolean, soundsOnly: Boolean = false) {
        when {
            phase == PipelinePhase.Listening -> if (record == null) begin()
            (phase == PipelinePhase.Idle || phase == PipelinePhase.Paused) && soundsOnly -> if (record == null) begin()
            phase == PipelinePhase.Idle || phase == PipelinePhase.Paused || (phase is PipelinePhase.Failed && !recovering) -> end()
            phase == PipelinePhase.RequestingMicrophonePermission || phase is PipelinePhase.PreparingEngine || phase == PipelinePhase.StartingAudio ->
                if (leftover == Leftover.Kept) leftover = Leftover.Starting
        }
    }

    fun phaseChanged(phase: PipelinePhase, stopped: StoppedCaptionsNotice.Cause?, soundsOnly: Boolean = false) = phaseChanged(phase, recovering = stopped == null, soundsOnly = soundsOnly)

    fun keepAlive() {
        val current = record ?: return
        save(current.copy(aliveAt = now()))
    }

    fun take(): ListeningCut.Notice? {
        val raw = runCatching { store.read() }.getOrNull() ?: return null
        val record = ListeningCut.parse(raw)
        val at = now()
        val notice = ListeningCut.notice(record, at, version)
        if (notice == null && record != null && record.version == version && at - record.aliveAt in 0.0..ListeningCut.LEAST_GAP_SECONDS) {
            leftover = Leftover.Kept
            return null
        }
        runCatching { background.execute { runCatching { store.clear() } } }
        return notice
    }

    private fun begin() {
        val at = now()
        save(ListeningCut.Record(at, at, version))
    }

    private fun end() {
        if (record == null && leftover != Leftover.Starting) return
        record = null
        leftover = Leftover.None
        runCatching { background.execute { runCatching { store.clear() } } }
    }

    private fun save(next: ListeningCut.Record) {
        record = next
        runCatching { background.execute { runCatching { store.write(ListeningCut.encode(next)) } } }
    }

    private enum class Leftover { None, Kept, Starting }
}
