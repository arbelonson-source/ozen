package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.CloudHTTP
import com.arbelonson.ozen.core.CloudKeyCheck
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.UrlConnectionCloudHTTP
import com.arbelonson.ozen.core.tr
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EngineSettings(
    private val settings: SettingsHolder,
    private val cloudKeys: CloudKeyStore,
    private val homeServerCode: HomeServerCodeStore,
    private val checkHomeComputer: suspend () -> HomeServerCheck,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val http: CloudHTTP = UrlConnectionCloudHTTP(),
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
        restart()
    }

    /** Every engine is told the language, so captions running on any of them start again in the new one. */
    fun chooseSpokenLanguage(language: UILanguage) {
        if (settings.current.value.spokenLanguage == language) return
        settings.change {
            // As on the iPhone: on the old language's recommended model, the
            // new one's comes along (its "may be misheard" cutoffs, and the
            // name saved with each conversation); one chosen by hand stays.
            val followsPhoneModel = it.whisperModelVariant == it.phoneModel.variant
            it.spokenLanguage = language
            if (followsPhoneModel) it.whisperModelVariant = it.phoneModel.variant
        }
        restart()
    }

    fun chooseCloudService(service: CloudProvider) {
        if (provider == service) return
        settings.change { it.cloudProvider = service }
        forgetKeyCheck()
        if (cloudIsTheEngine) restart()
    }

    fun chooseCloudModel(model: String) {
        if (settings.current.value.cloudModel == model) return
        settings.change { it.cloudModel = model }
        if (cloudIsTheEngine) restart()
    }

    /** What the Keystore held when last looked at, so drawing Settings never opens it on the main thread. */
    val cloudKeysHeld = MutableStateFlow<Set<CloudProvider>>(emptySet())

    val pairingCodeHeld = MutableStateFlow(false)

    private val credentialWork = Mutex()

    private val keyCheckState = MutableStateFlow<CloudKeyCheck?>(null)

    /** What trying the saved key at its service showed, for the key row under the field; null until a reply. */
    val cloudKeyCheck: StateFlow<CloudKeyCheck?> = keyCheckState.asStateFlow()

    private val keyCheckGeneration = AtomicInteger()

    private fun forgetKeyCheck(): Int {
        val generation = keyCheckGeneration.incrementAndGet()
        keyCheckState.value = null
        return generation
    }

    suspend fun loadKeyState() = credentialWork.withLock {
        val held = withContext(io) { CloudProvider.entries.filter { cloudKeys.hasKey(it) }.toSet() to (homeServerCode.read() != null) }
        cloudKeysHeld.value = held.first
        pairingCodeHeld.value = held.second
    }

    fun hasCloudKey(): Boolean = provider in cloudKeysHeld.value

    fun cloudSoundAcknowledged(): Boolean = provider in settings.current.value.cloudSoundAcknowledged

    fun acknowledgeCloud(service: CloudProvider) {
        if (service in settings.current.value.cloudSoundAcknowledged) return
        settings.change { it.cloudSoundAcknowledged = it.cloudSoundAcknowledged + service }
    }

    suspend fun saveCloudKey(draft: String): Boolean {
        return withContext(NonCancellable) {
            val key = draft.trim()
            if (key.isEmpty() || !cloudSoundAcknowledged()) return@withContext false
            val service = provider
            var generation = 0
            credentialWork.withLock {
                if (!withContext(io) { cloudKeys.save(key, service) }) return@withContext false
                cloudKeysHeld.value = cloudKeysHeld.value + service
                generation = forgetKeyCheck()
            }
            checkSavedKey(service, key, generation)
            if (cloudIsTheEngine) restart()
            true
        }
    }

    /**
     * After the app starts again a saved key has no check yet: it is checked
     * once when the cloud section shows, so a key the service refuses says
     * so instead of reading as a green "Key saved on the phone".
     */
    suspend fun checkSavedKeyIfUnchecked() {
        if (keyCheckState.value != null) return
        val service = provider
        val generation = keyCheckGeneration.get()
        val key = withContext(io) { cloudKeys.read(service) } ?: return
        checkSavedKey(service, key, generation)
    }

    private fun checkSavedKey(service: CloudProvider, key: String, generation: Int) {
        scope.launch {
            val result = CloudKeyCheck.result(service, key, http)
            val stillSaved = withContext(io) { cloudKeys.read(service) == key }
            if (generation == keyCheckGeneration.get() && provider == service && stillSaved) keyCheckState.value = result
        }
    }

    suspend fun deleteCloudKey() {
        withContext(NonCancellable) {
            val service = provider
            credentialWork.withLock {
                withContext(io) { cloudKeys.remove(service) }
                cloudKeysHeld.value = cloudKeysHeld.value - service
                forgetKeyCheck()
            }
            if (cloudIsTheEngine) restart()
        }
    }

    fun saveHomeComputerAddress(draft: String) {
        val address = draft.trim()
        if (settings.current.value.homeServerAddress == address) return
        settings.change {
            it.homeServerAddress = address
            it.homeServerCertificatePin = null
        }
        if (homeComputerIsTheEngine) restart()
    }

    fun hasPairingCode(): Boolean = pairingCodeHeld.value

    suspend fun savePairingCode(draft: String): Boolean {
        return withContext(NonCancellable) {
            val code = draft.trim()
            if (code.isEmpty()) return@withContext false
            credentialWork.withLock {
                if (!withContext(io) { homeServerCode.save(code) }) return@withContext false
                pairingCodeHeld.value = true
            }
            if (homeComputerIsTheEngine) restart()
            true
        }
    }

    suspend fun deletePairingCode() {
        withContext(NonCancellable) {
            credentialWork.withLock {
                withContext(io) { homeServerCode.remove() }
                pairingCodeHeld.value = false
            }
            if (homeComputerIsTheEngine) restart()
        }
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

    /** Counts pairings from a computer's link, so an open Settings shows the code they saved. */
    val computerPairings = MutableStateFlow(0)

    /** A new computer paired from its code: captions start again on it. */
    fun pairedWithAComputer() {
        pairingCodeHeld.value = true
        computerPairings.value += 1
        restart()
    }

    /**
     * As on the iPhone, a restart carries the current beam too: one still
     * settling from the slider would only reconnect a second time.
     */
    private fun restart() {
        beamRestart?.cancel()
        beamRestart = null
        restartIfRunning()
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

    /** False when a typed key could not be stored, so Settings can stay open and say so instead of closing on it. */
    suspend fun saveUnsavedEntries(cloudKeyDraft: String, addressDraft: String, pairingCodeDraft: String): Boolean {
        HomeServer.unsavedAddress(addressDraft, settings.current.value.homeServerAddress)?.let { saveHomeComputerAddress(it) }
        savePairingCode(pairingCodeDraft)
        return cloudKeyDraft.isBlank() || saveCloudKey(cloudKeyDraft)
    }

    companion object {
        const val BEAM_SETTLE_MILLIS = 1_000L

        fun cloudModelName(model: String): String =
            if (model == CloudSpeech.FAST_MODEL) tr("מהיר (Gemini Flash Lite)", "Fast (Gemini Flash Lite)") else tr("מדויק יותר, קצת יותר איטי (Gemini Flash)", "More accurate, a bit slower (Gemini Flash)")

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
