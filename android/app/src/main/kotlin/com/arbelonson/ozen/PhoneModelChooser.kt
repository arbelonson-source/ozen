package com.arbelonson.ozen

import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.ModelDownloadRetry
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelChoice
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.PhoneModelEstimate
import com.arbelonson.ozen.core.PhoneModelFit
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

    fun choose(file: String, byHand: Boolean = false)

    /** Like `choose`, for a model that suits the phone better: the model in use now is deleted once the new one is chosen and on the phone. */
    fun replaceWith(file: String)

    /** The size of the model in use on the phone, or null when its file is not there. */
    fun inUseBytes(): Long?

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
    private val makeRoom: (Long) -> Unit = {},
    private val scope: CoroutineScope,
    private val measure: suspend ((Float) -> Unit) -> PhoneSpeed?,
    private val applied: () -> Unit,
    override val captionsBusy: StateFlow<Boolean> = MutableStateFlow(false),
    private val totalMemory: () -> Long? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val remove: (File) -> Boolean = { it.delete() },
    private val device: String = "",
    private val downloadStarted: () -> Unit = {},
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

    private var replaced: String? = null

    // A download that broke off for a reason a later try can mend (the
    // connection dropped, not a file that arrived damaged) is tried again by
    // itself, so the person who left Settings does not have to come back and
    // press it. The count belongs to the file she chose: choosing starts it
    // over, a retry timer or a connection change carries it on.
    private var retryJob: Job? = null
    private var failures = 0
    private var brokeOff: String? = null

    private fun onDisk(): Set<String> = PhoneModelCatalog.all.filter { File(modelFolder, it.file).isFile }.map { it.file }.toSet()

    private fun leftovers(): Map<String, Long> = PhoneModelShelf.partials(PhoneModelStorage(modelFolder, "").list())

    override fun refreshInstalled() {
        installedState.value = onDisk()
        partialState.value = leftovers()
    }

    override fun choose(file: String, byHand: Boolean) {
        if (byHand) rememberChoiceAgainstWarning(file)
        picks += 1
        failures = 0
        synchronized(lock) { replaced = null }
        start(file)
    }

    override fun replaceWith(file: String) {
        picks += 1
        failures = 0
        synchronized(lock) { replaced = settings.current.value.phoneModel.file.takeIf { it != file } }
        start(file)
    }

    override fun inUseBytes(): Long? = File(modelFolder, settings.current.value.phoneModel.file).takeIf { it.isFile }?.length()

    private fun rememberChoiceAgainstWarning(file: String) {
        val current = settings.current.value
        val model = PhoneModelCatalog.offered(current.spokenLanguage).firstOrNull { it.file == file } ?: return
        val speed = current.gflopsOn(device) ?: return
        if (PhoneModelEstimate.fit(model, speed, totalMemory()) != PhoneModelFit.KeepsUp) settings.change { it.dismissModelFit(model.variant, device) }
    }

    private fun start(file: String) {
        val current = settings.current.value
        val model = PhoneModelCatalog.offered(current.spokenLanguage).firstOrNull { it.file == file } ?: return
        synchronized(lock) {
            refreshInstalled()
            stopDownload?.set(true)
            retryJob?.cancel()
            retryJob = null
            brokeOff = null
            switchState.value = PhoneModelSwitch.Idle
            when (PhoneModelChoice.plan(model, current.phoneModel, installedState.value)) {
                PhoneModelChoice.Plan.UseNow -> {
                    use(model)
                    removeReplaced(model)
                }
                PhoneModelChoice.Plan.DownloadFirst -> download(model)
            }
        }
    }

    override fun resumeWaiting() {
        synchronized(lock) {
            val file = switchState.value.waitingFile ?: switchState.value.retryableFile(brokeOff) ?: return
            if (ModelDownloadGate.decide(network(), settings.current.value.allowCellularModelDownload) == ModelDownloadGate.Decision.Proceed) start(file)
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
                            if (freed is PhoneModelDeletion.Result.Freed && (state is PhoneModelSwitch.NotEnoughRoom || state.waitingFile == file || state.retryingFile == file)) switchState.value = PhoneModelSwitch.Idle
                            if (freed is PhoneModelDeletion.Result.Freed && state.retryingFile == file) retryJob?.cancel()
                            if (freed is PhoneModelDeletion.Result.Freed && brokeOff == file) brokeOff = null
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

    private fun removeReplaced(model: PhoneModel) {
        val old = replaced ?: return
        replaced = null
        if (settings.current.value.phoneModel.file != model.file) return
        PhoneModelDeletion(modelFolder, settings.current.value.phoneModel.file, remove).delete(old)
        refreshInstalled()
    }

    private fun removeStrayParts(model: PhoneModel) {
        val keep = setOf(model.file, settings.current.value.phoneModel.file)
        for (entry in PhoneModelStorage(modelFolder, "").list()) {
            val name = entry.name.removeSuffix(".part")
            if (name != entry.name && name !in keep) PhoneModelDownloader.ifNotDownloading(File(modelFolder, name)) { remove(File(modelFolder, entry.name)) }
        }
        refreshInstalled()
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
        downloadStarted()
        scope.launch {
            var lostWiFi = false
            val outcome = withContext(io) {
                runCatching {
                    downloader.pendingMegabytes()?.let { makeRoom(StorageSpaceGate.requiredMegabytes(it) * StorageSpaceGate.BYTES_PER_MEGABYTE) }
                    downloader.download(checking = {
                        if (stopped.get()) throw IOException("stopped: another model was chosen")
                        switchState.value = PhoneModelSwitch.Downloading(model.file, 1.0, checking = true)
                    }) { fraction ->
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
                }
            }
            if (stopped.get()) return@launch
            stopDownload = null
            refreshInstalled()
            if (outcome.isSuccess) {
                switchState.value = PhoneModelSwitch.Idle
                synchronized(lock) {
                    use(model)
                    removeReplaced(model)
                    removeStrayParts(model)
                }
            } else if (lostWiFi) {
                switchState.value = PhoneModelSwitch.WaitingForWiFi(model.file)
                resumeWaiting()
            } else if (ModelDownloadGate.decide(network(), settings.current.value.allowCellularModelDownload) == ModelDownloadGate.Decision.Offline) {
                switchState.value = PhoneModelSwitch.Offline(model.file)
            } else {
                failed(model.file, outcome.exceptionOrNull(), stopped)
            }
        }
    }

    private fun failed(file: String, error: Throwable?, stopped: AtomicBoolean) {
        synchronized(lock) {
            if (stopped.get()) return
            if (error is PhoneModelDownloader.ChecksumMismatch) {
                switchState.value = PhoneModelSwitch.Failed(file)
                return
            }
            brokeOff = file
            failures += 1
            val seconds = ModelDownloadRetry.delaySeconds(failures)
            if (seconds == null) {
                switchState.value = PhoneModelSwitch.Failed(file)
                return
            }
            val waiting = PhoneModelSwitch.Retrying(file, seconds)
            switchState.value = waiting
            retryJob = scope.launch {
                delay(seconds * 1_000)
                synchronized(lock) {
                    if (switchState.value == waiting) {
                        retryJob = null
                        start(file)
                    }
                }
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
            settings.change { it.phoneSpeed = speed.copy(device = device) }
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

private val PhoneModelSwitch.retryingFile: String?
    get() = (this as? PhoneModelSwitch.Retrying)?.file

private fun PhoneModelSwitch.retryableFile(brokeOff: String?): String? = when (this) {
    is PhoneModelSwitch.Retrying -> file
    is PhoneModelSwitch.Failed -> file.takeIf { it == brokeOff }
    else -> null
}

private val PhoneModelSwitch.waitingFile: String?
    get() = when (this) {
        is PhoneModelSwitch.WaitingForWiFi -> file
        is PhoneModelSwitch.Offline -> file
        else -> null
    }
