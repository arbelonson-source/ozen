package com.arbelonson.ozen

import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelChoice
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.PhoneModelEstimate
import com.arbelonson.ozen.core.PhoneModelShelf
import com.arbelonson.ozen.core.PhoneModelStorage
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PhoneSpeed
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.StorageSpaceGate
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface PhoneModels {
    val switching: StateFlow<PhoneModelSwitch>
    val check: StateFlow<PhoneCheckState>
    val installed: StateFlow<Set<String>>
    val partial: StateFlow<Map<String, Long>>
    val captionsBusy: StateFlow<Boolean>
    val totalMemoryBytes: Long?

    fun refreshInstalled()

    fun choose(file: String)

    /** The connection or the cellular switch changed: a model waiting for Wi-Fi or a connection starts now if it can. */
    fun resumeWaiting()

    suspend fun delete(file: String): PhoneModelDeletion.Result

    fun runCheck(thenChooseBest: Boolean)
}

class PhoneModelChooser(
    private val settings: SettingsHolder,
    private val modelFolder: File,
    private val fetcher: () -> ReleaseFileFetching,
    private val network: () -> NetworkConditions?,
    private val freeSpace: () -> Long?,
    private val scope: CoroutineScope,
    private val measure: suspend ((Float) -> Unit) -> PhoneSpeed?,
    private val applied: () -> Unit,
    override val captionsBusy: StateFlow<Boolean> = MutableStateFlow(false),
    private val totalMemory: () -> Long? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val remove: (File) -> Boolean = { it.delete() },
) : PhoneModels {
    private val lock = Any()

    private val switchState = MutableStateFlow<PhoneModelSwitch>(PhoneModelSwitch.Idle)
    override val switching: StateFlow<PhoneModelSwitch> = switchState

    private val checkState = MutableStateFlow<PhoneCheckState>(PhoneCheckState.Idle)
    override val check: StateFlow<PhoneCheckState> = checkState

    private val installedState = MutableStateFlow(onDisk())
    override val installed: StateFlow<Set<String>> = installedState

    private val partialState = MutableStateFlow(leftovers())
    override val partial: StateFlow<Map<String, Long>> = partialState

    override val totalMemoryBytes: Long? get() = totalMemory()

    private var picks = 0

    private var stopDownload: AtomicBoolean? = null

    private fun onDisk(): Set<String> = PhoneModelCatalog.all.filter { File(modelFolder, it.file).isFile }.map { it.file }.toSet()

    private fun leftovers(): Map<String, Long> = PhoneModelShelf.partials(PhoneModelStorage(modelFolder, "").list())

    override fun refreshInstalled() {
        installedState.value = onDisk()
        partialState.value = leftovers()
    }

    override fun choose(file: String) {
        picks += 1
        val current = settings.current.value
        val model = PhoneModelCatalog.offered(current.spokenLanguage).firstOrNull { it.file == file } ?: return
        synchronized(lock) {
            refreshInstalled()
            stopDownload?.set(true)
            switchState.value = PhoneModelSwitch.Idle
            when (PhoneModelChoice.plan(model, current.phoneModel, installedState.value)) {
                PhoneModelChoice.Plan.UseNow -> use(model)
                PhoneModelChoice.Plan.DownloadFirst -> download(model)
            }
        }
    }

    override fun resumeWaiting() {
        synchronized(lock) {
            val file = switchState.value.waitingFile ?: return
            if (ModelDownloadGate.decide(network(), settings.current.value.allowCellularModelDownload) == ModelDownloadGate.Decision.Proceed) choose(file)
        }
    }

    override suspend fun delete(file: String): PhoneModelDeletion.Result {
        return withContext(NonCancellable) {
            val result = withContext(io) {
                synchronized(lock) {
                    if ((switchState.value as? PhoneModelSwitch.Downloading)?.file == file) {
                        PhoneModelDeletion.Result.Refused
                    } else {
                        PhoneModelDeletion(modelFolder, settings.current.value.phoneModel.file, remove).delete(file).also { freed ->
                            val state = switchState.value
                            if (freed is PhoneModelDeletion.Result.Freed && (state is PhoneModelSwitch.NotEnoughRoom || state.waitingFile == file)) switchState.value = PhoneModelSwitch.Idle
                        }
                    }
                }
            }
            refreshInstalled()
            result
        }
    }

    private fun use(model: PhoneModel) {
        val before = settings.current.value.phoneModel
        synchronized(lock) {
            settings.change {
                it.phoneModelFile = PhoneModelChoice.storedFile(it.spokenLanguage, model)
                it.whisperModelVariant = model.variant
            }
        }
        if (before.file != model.file) applied()
    }

    private fun download(model: PhoneModel) {
        val downloader = PhoneModelDownloader(File(modelFolder, model.file), model.source, fetcher())
        val room = StorageSpaceGate.shortfallMegabytes(downloader.pendingMegabytes() ?: 0, freeSpace())
        if (room != null) {
            switchState.value = PhoneModelSwitch.NotEnoughRoom(model.file, room)
            return
        }
        val allowCellular = settings.current.value.allowCellularModelDownload
        when (ModelDownloadGate.decide(network(), allowCellular)) {
            ModelDownloadGate.Decision.Offline -> return run { switchState.value = PhoneModelSwitch.Offline(model.file) }
            ModelDownloadGate.Decision.WaitForWiFi -> return run { switchState.value = PhoneModelSwitch.WaitingForWiFi(model.file) }
            ModelDownloadGate.Decision.Proceed -> Unit
        }
        val stopped = AtomicBoolean(false)
        stopDownload = stopped
        switchState.value = PhoneModelSwitch.Downloading(model.file, 0.0)
        scope.launch {
            var lostWiFi = false
            val done = withContext(io) {
                runCatching {
                    downloader.download { fraction ->
                        if (stopped.get()) throw IOException("stopped: another model was chosen")
                        if (ModelDownloadGate.decide(network(), settings.current.value.allowCellularModelDownload) == ModelDownloadGate.Decision.WaitForWiFi) {
                            lostWiFi = true
                            throw IOException("Wi-Fi went, and downloads on cellular data are off")
                        }
                        val shown = (fraction * 100).toInt() / 100.0
                        if (!stopped.get() && (switchState.value as? PhoneModelSwitch.Downloading)?.fraction != shown) {
                            switchState.value = PhoneModelSwitch.Downloading(model.file, shown)
                        }
                    }
                }.isSuccess
            }
            if (stopped.get()) return@launch
            stopDownload = null
            refreshInstalled()
            if (done) {
                switchState.value = PhoneModelSwitch.Idle
                use(model)
            } else if (lostWiFi) {
                switchState.value = PhoneModelSwitch.WaitingForWiFi(model.file)
                resumeWaiting()
            } else {
                switchState.value = PhoneModelSwitch.Failed(model.file)
            }
        }
    }

    override fun runCheck(thenChooseBest: Boolean) {
        if (checkState.value is PhoneCheckState.Running || captionsBusy.value) return
        checkState.value = PhoneCheckState.Running(0f)
        val before = settings.current.value
        val picksBefore = picks
        val job = scope.launch {
            val speed = measure { checkState.value = PhoneCheckState.Running(it) }
            if (speed == null) {
                checkState.value = PhoneCheckState.Failed
                return@launch
            }
            if (captionsBusy.value) {
                checkState.value = PhoneCheckState.Idle
                return@launch
            }
            settings.change { it.phoneSpeed = speed }
            checkState.value = PhoneCheckState.Idle
            val now = settings.current.value
            val untouched = picks == picksBefore && now.phoneModelFile == before.phoneModelFile && now.spokenLanguage == before.spokenLanguage
            if (thenChooseBest && untouched) choose(PhoneModelEstimate.recommend(now.spokenLanguage, speed.gflops, totalMemory()).file)
        }
        val watcher = scope.launch { captionsBusy.first { it }; job.cancel() }
        job.invokeOnCompletion {
            watcher.cancel()
            if (it != null && checkState.value is PhoneCheckState.Running) checkState.value = PhoneCheckState.Idle
        }
    }
}

private val PhoneModelSwitch.waitingFile: String?
    get() = when (this) {
        is PhoneModelSwitch.WaitingForWiFi -> file
        is PhoneModelSwitch.Offline -> file
        else -> null
    }
