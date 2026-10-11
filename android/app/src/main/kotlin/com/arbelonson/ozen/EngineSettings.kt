package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EngineSettings(
    private val settings: SettingsHolder,
    private val cloudKeys: CloudKeyStore,
    private val homeServerCode: HomeServerCodeStore,
    private val checkHomeComputer: suspend () -> HomeServerCheck,
    private val scope: CoroutineScope,
    private val restartIfRunning: () -> Unit,
) {
    private var beamRestart: Job? = null

    val engines = listOf(TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud)

    val chosenEngine: TranscriptionEngineKind
        get() = settings.current.value.engine.let { if (it == TranscriptionEngineKind.AppleSpeech) TranscriptionEngineKind.WhisperKit else it }

    private val cloudIsTheEngine get() = settings.current.value.engine == TranscriptionEngineKind.Cloud

    private val homeComputerIsTheEngine get() = settings.current.value.engine == TranscriptionEngineKind.HomeServer

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

    fun saveHomeComputerAddress(draft: String) {
        val address = draft.trim()
        if (settings.current.value.homeServerAddress == address) return
        settings.change { it.homeServerAddress = address }
        if (homeComputerIsTheEngine) restartIfRunning()
    }

    fun hasPairingCode(): Boolean = homeServerCode.read() != null

    fun savePairingCode(draft: String): Boolean {
        val code = draft.trim()
        if (code.isEmpty() || !homeServerCode.save(code)) return false
        if (homeComputerIsTheEngine) restartIfRunning()
        return true
    }

    fun deletePairingCode() {
        homeServerCode.remove()
        if (homeComputerIsTheEngine) restartIfRunning()
    }

    fun chooseBeam(beam: Int) {
        val chosen = beam.coerceIn(AppSettings.homeServerBeamRange)
        if (settings.current.value.homeServerBeam == chosen) return
        settings.change { it.homeServerBeam = chosen }
        beamRestart?.cancel()
        if (!homeComputerIsTheEngine) return
        beamRestart = scope.launch {
            delay(BEAM_SETTLE_MILLIS)
            if (homeComputerIsTheEngine) restartIfRunning()
        }
    }

    fun canTestConnection(addressDraft: String, codeDraft: String, codeSaved: Boolean): Boolean {
        val saved = settings.current.value.homeServerAddress
        val address = HomeServer.url(saved) != null || HomeServer.unsavedAddress(addressDraft, saved) != null
        return address && (codeSaved || codeDraft.isNotBlank())
    }

    suspend fun testConnection(addressDraft: String): HomeServerCheck {
        HomeServer.unsavedAddress(addressDraft, settings.current.value.homeServerAddress)?.let { saveHomeComputerAddress(it) }
        return checkHomeComputer()
    }

    fun saveUnsavedEntries(cloudKeyDraft: String, addressDraft: String, pairingCodeDraft: String) {
        HomeServer.unsavedAddress(addressDraft, settings.current.value.homeServerAddress)?.let { saveHomeComputerAddress(it) }
        savePairingCode(pairingCodeDraft)
        saveCloudKey(cloudKeyDraft)
    }

    companion object {
        const val BEAM_SETTLE_MILLIS = 1_000L

        fun beamDescription(beam: Int): String {
            val top = AppSettings.homeServerBeamRange.last
            return when {
                beam <= 1 -> tr("1 מתוך %1, הכי מהיר", "1 of %1, fastest", listOf("$top"))
                beam == AppSettings.default.homeServerBeam -> tr("%1 מתוך %2, הרגיל", "%1 of %2, usual", listOf("$beam", "$top"))
                beam >= top -> tr("%1 מתוך %1, הכי איטי", "%1 of %1, slowest", listOf("$top"))
                else -> tr("%1 מתוך %2", "%1 of %2", listOf("$beam", "$top"))
            }
        }
    }
}
