package com.arbelonson.ozen.core

import com.arbelonson.ozen.core.BuiltInWhileWaiting.HandOver
import com.arbelonson.ozen.core.BuiltInWhileWaiting.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class BuiltInWhileWaitingTest {
    private val waiting = PipelinePhase.Failed(
        PipelineFailure(
            kind = PipelineFailure.Kind.EngineUnavailable,
            detail = "626 MB to download, waiting for Wi-Fi",
            engineUnavailability = EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "cellular or Low Data Mode", downloadMegabytes = 626),
        ),
    )

    private fun chosen(languageCode: String = "he", engine: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit, allowCellular: Boolean = false) =
        AppSettings.default.apply {
            this.engine = engine
            this.languageCode = languageCode
            allowCellularModelDownload = allowCellular
        }

    @Test
    fun `offered only where the built-in recognition works on the phone alone for the spoken language`() {
        val cases = listOf(
            Triple("he", setOf("he", "en"), Offer.BuiltInOrCellularDownload),
            Triple("he", setOf("en"), Offer.CellularDownload),
            Triple("en", setOf("en"), Offer.BuiltInOrCellularDownload),
            Triple("en", emptySet(), Offer.CellularDownload),
            Triple("ru", setOf("he", "en"), Offer.CellularDownload),
            Triple("ru", setOf("ru"), Offer.BuiltInOrCellularDownload),
        )
        for ((language, onDevice, expected) in cases) {
            val offer = BuiltInWhileWaiting.offer(waiting, chosen(language), NetworkConditions.cellular) { it in onDevice }
            assertEquals(expected, offer, "$language with ${onDevice.sorted()}")
        }
    }

    @Test
    fun `the language asked about is the one spoken, whatever the app shows`() {
        val asked = mutableListOf<String>()
        BuiltInWhileWaiting.offer(waiting, chosen("ar"), NetworkConditions.cellular) { asked += it; true }
        BuiltInWhileWaiting.offer(waiting, chosen("fr"), NetworkConditions.cellular) { asked += it; true }
        assertEquals(listOf("ar", "fr"), asked)
    }

    @Test
    fun `offered while the connection still can't take the download, and on Wi-Fi the download is about to start by itself`() {
        val cases = listOf(
            Triple(NetworkConditions.cellular, false, Offer.BuiltInOrCellularDownload),
            Triple(NetworkConditions(isConnected = true, isConstrained = true), false, Offer.BuiltInOrCellularDownload),
            Triple(NetworkConditions.offline, false, Offer.BuiltInOrCellularDownload),
            Triple(null, false, Offer.BuiltInOrCellularDownload),
            Triple(NetworkConditions.wifi, false, Offer.CellularDownload),
            Triple(NetworkConditions.cellular, true, Offer.CellularDownload),
        )
        for ((network, allowCellular, expected) in cases) {
            val offer = BuiltInWhileWaiting.offer(waiting, chosen(allowCellular = allowCellular), network) { true }
            assertEquals(expected, offer, "$network cellular allowed: $allowCellular")
        }
    }

    @Test
    fun `nothing is offered unless captions are stopped waiting for Wi-Fi`() {
        val others = listOf(
            PipelinePhase.Idle,
            PipelinePhase.Listening,
            PipelinePhase.Paused,
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.4)),
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, isFirstTime = true)),
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "offline", EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "offline", downloadMegabytes = 626))),
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "full", EngineUnavailability(EngineUnavailability.Kind.NotEnoughStorage, "full", downloadMegabytes = 626))),
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, "denied")),
        )
        for (phase in others) assertEquals(Offer.None, BuiltInWhileWaiting.offer(phase, chosen(), NetworkConditions.cellular) { true }, "$phase")
    }

    @Test
    fun `only the phone's own model is waited for - with another engine chosen only the cellular download is offered`() {
        for (engine in listOf(TranscriptionEngineKind.AppleSpeech, TranscriptionEngineKind.Cloud, TranscriptionEngineKind.HomeServer)) {
            assertEquals(Offer.CellularDownload, BuiltInWhileWaiting.offer(waiting, chosen(engine = engine), NetworkConditions.cellular) { true }, "$engine")
        }
    }

    @Test
    fun `the built-in captions on the phone alone even when server recognition was allowed, and keeps the rest of the settings`() {
        val settings = chosen("en").apply {
            allowServerFallbackForAppleSpeech = true
            vocabulary = listOf("Ozen")
        }
        val builtIn = BuiltInWhileWaiting.captionSettings(settings)
        assertEquals(TranscriptionEngineKind.AppleSpeech, builtIn.engine)
        assertFalse(builtIn.allowServerFallbackForAppleSpeech)
        assertEquals("en", builtIn.languageCode)
        assertEquals(listOf("Ozen"), builtIn.vocabulary)
        assertEquals(settings.whisperModelVariant, builtIn.whisperModelVariant)
        assertNotSame(settings, builtIn)
        assertEquals(TranscriptionEngineKind.WhisperKit, settings.engine)
        assertTrue(settings.allowServerFallbackForAppleSpeech)
    }

    @Test
    fun `the hand-over to the downloaded model - at the next pause while listening, at once if the built-in stopped, never while paused or starting`() {
        data class Case(val ready: Boolean, val phase: PipelinePhase, val held: Boolean, val expected: HandOver)
        val cases = listOf(
            Case(true, PipelinePhase.Listening, false, HandOver.AtNextPause),
            Case(true, waiting, false, HandOver.Now),
            Case(true, PipelinePhase.Paused, false, HandOver.NotYet),
            Case(true, PipelinePhase.Idle, false, HandOver.NotYet),
            Case(true, PipelinePhase.StartingAudio, false, HandOver.NotYet),
            Case(true, PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)), false, HandOver.NotYet),
            Case(true, PipelinePhase.Listening, true, HandOver.NotYet),
            Case(true, waiting, true, HandOver.NotYet),
            Case(false, PipelinePhase.Listening, false, HandOver.NotYet),
            Case(false, waiting, false, HandOver.NotYet),
        )
        for (case in cases) {
            assertEquals(case.expected, BuiltInWhileWaiting.handOver(case.ready, case.phase, case.held), "ready ${case.ready}, ${case.phase}, held ${case.held}")
        }
    }

    @Test
    fun `a resume keeps the built-in until the model is ready, then starts the model itself`() {
        val settings = chosen("en")
        val waitingStill = BuiltInWhileWaiting.resumeSettings(settings, modelReady = false)
        assertTrue(waitingStill.keepsBuiltIn)
        assertEquals(BuiltInWhileWaiting.captionSettings(settings), waitingStill.settings)
        val ready = BuiltInWhileWaiting.resumeSettings(settings, modelReady = true)
        assertFalse(ready.keepsBuiltIn)
        assertEquals(settings, ready.settings)
    }
}
