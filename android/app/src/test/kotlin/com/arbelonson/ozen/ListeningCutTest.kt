package com.arbelonson.ozen

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import com.arbelonson.ozen.core.UILanguage
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListeningCutTest {
    private class MemoryStore(var value: String? = null) : ListeningRecordStore {
        var failWrites = false
        var failReads = false

        override fun read(): String? {
            if (failReads) error("read failed")
            return value
        }

        override fun write(value: String) {
            if (failWrites) error("write failed")
            this.value = value
        }

        override fun clear() {
            if (failWrites) error("clear failed")
            value = null
        }
    }

    private val day = 1_700_000_000.0 - 1_700_000_000.0 % 86_400
    private fun at(hours: Int, minutes: Int) = day + hours * 3_600 + minutes * 60

    private val store = MemoryStore()
    private var clock = at(0, 30)
    private val direct = Executor { it.run() }
    private fun recorder() = ListeningRecorder(store, VERSION, { clock }, direct)
    private val failure = PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "the microphone is busy")

    @Test
    fun `in Portuguese and Spanish listening cut at 01_10 is said with the singular article`() {
        val oneOClock = ListeningCut.Notice(stoppedAt = at(1, 10), offSeconds = 600.0)
        val tenOClock = ListeningCut.Notice(stoppedAt = at(10, 10), offSeconds = 600.0)
        Localization.withLanguage(UILanguage.Portuguese) {
            assertTrue("por volta da " in oneOClock.text(ZoneOffset.UTC), oneOClock.text(ZoneOffset.UTC))
            assertTrue("por volta das " in tenOClock.text(ZoneOffset.UTC), tenOClock.text(ZoneOffset.UTC))
        }
        Localization.withLanguage(UILanguage.Spanish) {
            assertTrue("hacia la " in oneOClock.text(ZoneOffset.UTC), oneOClock.text(ZoneOffset.UTC))
            assertTrue("hacia las " in tenOClock.text(ZoneOffset.UTC), tenOClock.text(ZoneOffset.UTC))
        }
    }

    @Test
    fun `listening that was cut at 01_10 and seen at 04_20 gives a notice about 01_10 and the length`() {
        val notice = ListeningCut.notice(ListeningCut.Record(startedAt = at(0, 30), aliveAt = at(1, 10), version = VERSION), now = at(4, 20), version = VERSION)
        assertNotNull(notice)
        assertEquals(at(1, 10), notice.stoppedAt, 0.001)
        assertEquals(3 * 3_600.0 + 10 * 60, notice.offSeconds, 0.001)
        val text = Localization.withLanguage(UILanguage.English) { notice.text(ZoneOffset.UTC) }
        assertTrue("1:10" in text && "3 hours 10 minutes" in text, text)
        assertTrue(text.startsWith("Listening stopped at about ") && text.endsWith("No alerts could come through during that time."), text)
    }

    @Test
    fun `the notification title says listening was off, not that captions are stopped now`() {
        val notice = ListeningCut.Notice(stoppedAt = at(1, 10), offSeconds = 3 * 3_600.0)
        assertEquals("Listening was off for a while", Localization.withLanguage(UILanguage.English) { notice.title() })
    }

    @Test
    fun `a gap of two minutes or less is a quick restart and says nothing`() {
        val record = ListeningCut.Record(startedAt = at(0, 30), aliveAt = at(1, 10), version = VERSION)
        assertNull(ListeningCut.notice(record, now = at(1, 10) + 119, version = VERSION))
        assertNull(ListeningCut.notice(record, now = at(1, 10) + 120, version = VERSION))
        assertNotNull(ListeningCut.notice(record, now = at(1, 10) + 121, version = VERSION))
    }

    @Test
    fun `a record from before an app update says nothing`() {
        val record = ListeningCut.Record(startedAt = at(0, 30), aliveAt = at(1, 10), version = VERSION)
        assertNull(ListeningCut.notice(record, now = at(4, 20), version = VERSION + 1))
    }

    @Test
    fun `no record says nothing`() {
        assertNull(ListeningCut.notice(null, now = at(4, 20), version = VERSION))
    }

    @Test
    fun `a corrupt record or a time in the future says nothing and does not crash`() {
        for (raw in listOf("", "garbage", "listening 1", "listening 1 2", "listening a b 7", "listening NaN NaN 7", "listening -5 -5 7", "listening 9 3 7")) {
            assertNull(ListeningCut.notice(ListeningCut.parse(raw), now = at(4, 20), version = VERSION), raw)
        }
        assertNull(ListeningCut.parse(null))
        assertNull(ListeningCut.notice(ListeningCut.Record(startedAt = at(5, 0), aliveAt = at(6, 0), version = VERSION), now = at(4, 20), version = VERSION))
    }

    @Test
    fun `a record survives being written and read back`() {
        val record = ListeningCut.Record(startedAt = at(0, 30), aliveAt = at(1, 10), version = VERSION)
        assertEquals(record, ListeningCut.parse(ListeningCut.encode(record)))
    }

    @Test
    fun `listening starting writes a record of when it started`() {
        recorder().phaseChanged(PipelinePhase.Listening, recovering = false)
        assertEquals(ListeningCut.Record(at(0, 30), at(0, 30), VERSION), ListeningCut.parse(store.value))
    }

    @Test
    fun `the keep-alive tick moves the alive time and keeps the start`() {
        val recorder = recorder()
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        clock = at(1, 10)
        recorder.keepAlive()
        assertEquals(ListeningCut.Record(at(0, 30), at(1, 10), VERSION), ListeningCut.parse(store.value))
    }

    @Test
    fun `a tick while nothing listens writes nothing`() {
        recorder().keepAlive()
        assertNull(store.value)
    }

    @Test
    fun `a tick after the listening ended does not bring the record back`() {
        val recorder = recorder()
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
        recorder.keepAlive()
        assertNull(store.value)
    }

    @Test
    fun `stop, pause and a failure that is reported each clear the record`() {
        for (end in listOf(PipelinePhase.Idle, PipelinePhase.Paused, PipelinePhase.Failed(failure))) {
            val recorder = recorder()
            recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
            assertNotNull(store.value)
            recorder.phaseChanged(end, recovering = false)
            assertNull(store.value, "$end")
        }
    }

    @Test
    fun `steps of a start and a failure that retries by itself keep the record`() {
        val preparing = PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.5))
        for ((phase, recovering) in listOf(preparing to false, PipelinePhase.StartingAudio to false, PipelinePhase.Failed(failure) to true)) {
            val recorder = recorder()
            recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
            recorder.phaseChanged(phase, recovering)
            assertNotNull(store.value, "$phase")
        }
    }

    @Test
    fun `a failure nobody is told about keeps the record, one the stopped-captions notice reports clears it`() {
        val wifi = PipelinePhase.Failed(
            PipelineFailure(
                PipelineFailure.Kind.EngineUnavailable,
                "the model waits for Wi-Fi",
                EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "cellular", downloadMegabytes = 574),
            ),
        )
        val reported = PipelinePhase.Failed(failure)
        for ((phase, kept) in listOf(wifi to true, reported to false)) {
            val recorder = recorder()
            recorder.phaseChanged(PipelinePhase.Listening, stopped = null)
            val cause = StoppedCaptionsNotice.cause(phase, retryScheduled = false, systemInterrupted = false, callEndedDuringInterruption = false)
            recorder.phaseChanged(phase, stopped = cause)
            assertEquals(kept, store.value != null, "$phase")
        }
    }

    @Test
    fun `listening again after a stop starts a fresh record`() {
        val recorder = recorder()
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
        clock = at(2, 0)
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        assertEquals(ListeningCut.Record(at(2, 0), at(2, 0), VERSION), ListeningCut.parse(store.value))
    }

    @Test
    fun `taking reads the leftover record, gives its notice and clears it`() {
        store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION))
        clock = at(4, 20)
        assertEquals(at(1, 10), recorder().take()?.stoppedAt)
        assertNull(store.value)
    }

    @Test
    fun `a quick restart keeps the record, so a later start still tells the cut from the real last-alive time`() {
        store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION))
        clock = at(1, 11)
        assertNull(recorder().take())
        assertNotNull(store.value)
        clock = at(4, 20)
        assertEquals(at(1, 10), recorder().take()?.stoppedAt)
        assertNull(store.value)
    }

    @Test
    fun `listening again after a quick restart writes a fresh record`() {
        store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION))
        clock = at(1, 11)
        val recorder = recorder()
        assertNull(recorder.take())
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        assertEquals(ListeningCut.Record(at(1, 11), at(1, 11), VERSION), ListeningCut.parse(store.value))
    }

    @Test
    fun `a stop right after a quick restart clears the old record, so no false notice comes later`() {
        store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION))
        clock = at(1, 11)
        val recorder = recorder()
        assertNull(recorder.take())
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
        assertNotNull(store.value, "the idle report every start sends keeps the record")
        recorder.phaseChanged(PipelinePhase.StartingAudio, recovering = false)
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
        clock = at(9, 0)
        assertNull(recorder().take())
    }

    @Test
    fun `after a quick restart each start step then a stop, pause or reported failure clears the old record, a retry keeps it`() {
        val preparing = PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.5))
        for (start in listOf(PipelinePhase.RequestingMicrophonePermission, preparing, PipelinePhase.StartingAudio)) {
            for (end in listOf(PipelinePhase.Idle, PipelinePhase.Paused, PipelinePhase.Failed(failure))) {
                store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION))
                clock = at(1, 11)
                val recorder = recorder()
                assertNull(recorder.take())
                recorder.phaseChanged(start, recovering = false)
                recorder.phaseChanged(PipelinePhase.Failed(failure), recovering = true)
                assertNotNull(store.value, "$start, then a failure that retries by itself")
                recorder.phaseChanged(end, recovering = false)
                assertNull(store.value, "$start, then $end")
            }
        }
    }

    @Test
    fun `a record from another version is cleared even right after it was written`() {
        store.value = ListeningCut.encode(ListeningCut.Record(at(0, 30), at(1, 10), VERSION - 1))
        clock = at(1, 11)
        assertNull(recorder().take())
        assertNull(store.value)
    }

    @Test
    fun `taking a corrupt record gives nothing and clears it`() {
        store.value = "garbage"
        assertNull(recorder().take())
        assertNull(store.value)
    }

    @Test
    fun `a store that fails never reaches the captions`() {
        store.failReads = true
        store.failWrites = true
        val recorder = recorder()
        assertNull(recorder.take())
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        recorder.keepAlive()
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
    }

    private companion object {
        const val VERSION = 7L
    }

    @Test
    fun `sounds only counts as listening - the record begins, is kept alive, and ends when it is stopped`() {
        val recorder = recorder()
        recorder.phaseChanged(PipelinePhase.Paused, recovering = false, soundsOnly = true)
        assertEquals(ListeningCut.Record(at(0, 30), at(0, 30), VERSION), ListeningCut.parse(store.value))
        clock = at(2, 0)
        recorder.keepAlive()
        assertEquals(ListeningCut.Record(at(0, 30), at(2, 0), VERSION), ListeningCut.parse(store.value))
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false, soundsOnly = true)
        assertEquals(ListeningCut.Record(at(0, 30), at(2, 0), VERSION), ListeningCut.parse(store.value))
        recorder.phaseChanged(PipelinePhase.Idle, recovering = false)
        assertNull(store.value)
    }

    @Test
    fun `paused or idle without sounds-only ends the record as before`() {
        val recorder = recorder()
        recorder.phaseChanged(PipelinePhase.Listening, recovering = false)
        recorder.phaseChanged(PipelinePhase.Paused, recovering = false)
        assertNull(store.value)
    }
}
