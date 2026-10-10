package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.TranscriptionEngineKind

class EngineSettings(
    private val settings: SettingsHolder,
    private val cloudKeys: CloudKeyStore,
    private val restartIfRunning: () -> Unit,
) {
    val engines = listOf(TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud)

    val chosenEngine: TranscriptionEngineKind
        get() = settings.current.value.engine.let { if (it == TranscriptionEngineKind.AppleSpeech) TranscriptionEngineKind.WhisperKit else it }

    private val cloudIsTheEngine get() = settings.current.value.engine == TranscriptionEngineKind.Cloud

    private val provider get() = settings.current.value.cloudProvider

    fun chooseEngine(kind: TranscriptionEngineKind) {
        if (settings.current.value.engine == kind) return
        settings.change { it.engine = kind }
        restartIfRunning()
    }

    fun chooseCloudService(service: CloudProvider) {
        if (provider == service) return
        settings.change { it.cloudProvider = service }
        if (cloudIsTheEngine) restartIfRunning()
    }

    fun hasCloudKey(): Boolean = cloudKeys.hasKey(provider)

    fun saveCloudKey(draft: String): Boolean {
        val key = draft.trim()
        if (key.isEmpty() || !cloudKeys.save(key, provider)) return false
        if (cloudIsTheEngine) restartIfRunning()
        return true
    }

    fun deleteCloudKey() {
        cloudKeys.remove(provider)
        if (cloudIsTheEngine) restartIfRunning()
    }
}
