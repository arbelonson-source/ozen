package com.arbelonson.ozen.core

object CloudCover {
    fun phoneSettings(settings: AppSettings, failure: PipelineFailure): AppSettings? {
        if (settings.engine != TranscriptionEngineKind.Cloud && settings.engine != TranscriptionEngineKind.HomeServer) return null
        val kind = failure.engineUnavailability?.kind ?: return null
        return when (kind) {
            EngineUnavailability.Kind.CloudKeyNeeded,
            EngineUnavailability.Kind.CloudOutOfCredit,
            EngineUnavailability.Kind.NoInternet,
            EngineUnavailability.Kind.HomeServerUnreachable,
            EngineUnavailability.Kind.HomeServerRejected,
            -> settings.copy().also { it.engine = TranscriptionEngineKind.WhisperKit }
            else -> null
        }
    }
}
