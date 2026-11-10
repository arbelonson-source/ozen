package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionsStopAnnouncer
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private typealias Said = Pair<CaptionsStopAnnouncer.Event, PipelinePhase>

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionPhaseChangesTest {
    private class PipelineCaptions : SpokenReplies.Captions {
        var phase: PipelinePhase = PipelinePhase.Idle
        var onPhaseChange: (PipelinePhase) -> Unit = {}
        override val isListening get() = phase.isListening
        override val isPaused get() = phase == PipelinePhase.Paused
        override val duringCall = false

        override fun pause() = move(PipelinePhase.Paused)

        override fun resume() = listOf(PipelinePhase.Idle, PipelinePhase.StartingAudio, PipelinePhase.Listening).forEach(::move)

        fun move(to: PipelinePhase) {
            phase = to
            onPhaseChange(to)
        }
    }

    private val preparing = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.LoadingModel))
    private val stalled = PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.AudioSessionFailed, detail = "stalled"))
    private val voice = FakeVoice()
    private val captions = PipelineCaptions()
    private val said = mutableListOf<Said>()
    private var seconds = 0.0
    private var progressChanged: (PipelinePhase) -> Unit = {}

    private fun TestScope.phaseChanges(): SpokenReplies {
        val replies = SpokenReplies(voice, this, captions)
        val changes = CaptionPhaseChanges(
            replies,
            CaptionsStopAnnouncer(),
            appIsActive = { true },
            seconds = { seconds },
            changed = {},
            publish = {},
            say = { event, phase -> said += event to phase },
        )
        captions.onPhaseChange = changes::phaseChanged
        progressChanged = changes::preparationProgressed
        return replies
    }

    private fun TestScope.phraseEnds() {
        voice.finish()
        advanceTimeBy(400)
        runCurrent()
    }

    @Test
    fun `captions ready after a long start while a quick phrase is still said are announced once`() = runTest {
        val replies = phaseChanges()
        captions.move(preparing)
        replies.speak("Two pills after dinner", rate = 0.45f)
        seconds = 12.0
        captions.move(PipelinePhase.Listening)
        assertEquals(PipelinePhase.Paused, captions.phase)
        seconds = 15.0
        phraseEnds()
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertEquals(listOf<Said>(CaptionsStopAnnouncer.Event.Ready to PipelinePhase.Listening), said)
    }

    @Test
    fun `captions back after stopping while a quick phrase is still said are announced once`() = runTest {
        val replies = phaseChanges()
        captions.move(PipelinePhase.Listening)
        captions.move(stalled)
        replies.speak("Hello", rate = 0.45f)
        seconds = 5.0
        captions.move(PipelinePhase.StartingAudio)
        captions.move(PipelinePhase.Listening)
        assertEquals(PipelinePhase.Paused, captions.phase)
        seconds = 8.0
        phraseEnds()
        assertEquals(PipelinePhase.Listening, captions.phase)
        val expected = listOf<Said>(CaptionsStopAnnouncer.Event.Stopped to stalled, CaptionsStopAnnouncer.Event.Back to PipelinePhase.Listening)
        assertEquals(expected, said)
    }

    @Test
    fun `the download that waited for Wi-Fi is said when its progress reaches the download step`() = runTest {
        phaseChanges()
        val waiting = PipelinePhase.Failed(
            PipelineFailure(
                kind = PipelineFailure.Kind.EngineUnavailable,
                detail = "",
                engineUnavailability = EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "", 626),
            ),
        )
        captions.move(waiting)
        captions.move(PipelinePhase.RequestingMicrophonePermission)
        captions.move(PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.CheckingSupport)))
        val downloading = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.0))
        progressChanged(downloading)
        progressChanged(PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.1)))
        val expected = listOf<Said>(CaptionsStopAnnouncer.Event.Stopped to waiting, CaptionsStopAnnouncer.Event.DownloadStarted to downloading)
        assertEquals(expected, said)
    }
}
