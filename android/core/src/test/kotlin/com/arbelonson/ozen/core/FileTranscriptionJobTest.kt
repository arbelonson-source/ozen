package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

class FileTranscriptionJobTest {
    private val model = FileModelChoice.toDownload(UILanguage.Hebrew)
    private val ready = FileTranscription.Ready("note.wav", UILanguage.Hebrew, model, listOf(model), model, 12.0, false)
    private val working = FileTranscription.Working("note.wav", UILanguage.Hebrew, model, FileTranscription.Working.Stage.Loading, null, null, emptyList())
    private val line = TranscribedLine(0.0, 2.0, "שלום")
    private val done = FileTranscription.Done("note.wav", UILanguage.Hebrew, model, listOf(line), HistorySave.Off)
    private val shown = MutableStateFlow<FileTranscription>(ready)
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val job = FileTranscriptionJob(shown, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)

    @Test
    fun theWorkingPageIsUpAtOnceAndStaysUntilTheWorkEnds() {
        job.start(working, { ready }) { awaitCancellation() }
        assertEquals(working, shown.value)
        assertTrue(job.running)
        scheduler.runCurrent()
        assertEquals(working, shown.value)
        assertTrue(job.running)
    }

    @Test
    fun stoppingBeforeTheDispatcherHasBegunTheWorkStillBringsTheReadyPageBack() {
        var began = false
        job.start(working, { ready }) { began = true }
        job.cancel()
        scheduler.runCurrent()
        assertFalse(began)
        assertEquals(ready, shown.value)
        assertFalse(job.running)
    }

    @Test
    fun stoppingWhileTheWorkRunsBringsTheReadyPageBack() {
        job.start(working, { ready }) { awaitCancellation() }
        scheduler.runCurrent()
        job.cancel()
        scheduler.runCurrent()
        assertEquals(ready, shown.value)
        assertFalse(job.running)
    }

    @Test
    fun aStopThatComesOnceTheTranscriptIsOnScreenLeavesTheTranscriptThere() {
        job.start(working, { ready }) {
            shown.value = done
            job.cancel()
        }
        scheduler.runCurrent()
        assertEquals(done, shown.value)
        assertFalse(job.running)
    }

    @Test
    fun workThatEndsOnItsOwnLeavesWhatItPutOnScreen() {
        val failed = FileTranscription.Failed("note.wav", FileTranscriptionProblem.NoSpeech)
        job.start(working, { ready }) { shown.value = failed }
        scheduler.runCurrent()
        assertEquals(failed, shown.value)
        assertFalse(job.running)
    }

    @Test
    fun aStopWithNowhereToGoBackToLeavesTheScreenAlone() {
        job.start(working, { null }) { awaitCancellation() }
        scheduler.runCurrent()
        job.cancel()
        scheduler.runCurrent()
        assertEquals(working, shown.value)
    }
}
