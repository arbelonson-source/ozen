package com.arbelonson.ozen.core

object BuiltInWhileWaiting {
    enum class Offer { None, CellularDownload, BuiltInOrCellularDownload }

    enum class HandOver { NotYet, AtNextPause, Now }

    data class Resume(val settings: AppSettings, val keepsBuiltIn: Boolean)

    fun offer(
        phase: PipelinePhase,
        chosen: AppSettings,
        network: NetworkConditions?,
        worksOnDevice: (String) -> Boolean,
    ): Offer {
        if (phase.failure?.engineUnavailability?.kind != EngineUnavailability.Kind.WaitingForWiFi) return Offer.None
        if (chosen.engine != TranscriptionEngineKind.WhisperKit) return Offer.CellularDownload
        if (network != null && ModelDownloadGate.canRetryDownload(network, chosen.allowCellularModelDownload)) return Offer.CellularDownload
        return if (worksOnDevice(chosen.languageCode)) Offer.BuiltInOrCellularDownload else Offer.CellularDownload
    }

    fun captionSettings(chosen: AppSettings): AppSettings = chosen.copy().apply {
        engine = TranscriptionEngineKind.AppleSpeech
        allowServerFallbackForAppleSpeech = false
    }

    fun handOver(modelReady: Boolean, phase: PipelinePhase, microphoneHeldElsewhere: Boolean): HandOver {
        if (!modelReady || microphoneHeldElsewhere) return HandOver.NotYet
        return when (phase) {
            PipelinePhase.Listening -> HandOver.AtNextPause
            is PipelinePhase.Failed -> HandOver.Now
            PipelinePhase.Idle, PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine,
            PipelinePhase.StartingAudio, PipelinePhase.Paused,
            -> HandOver.NotYet
        }
    }

    fun resumeSettings(chosen: AppSettings, modelReady: Boolean): Resume =
        if (modelReady) Resume(chosen, keepsBuiltIn = false) else Resume(captionSettings(chosen), keepsBuiltIn = true)
}
