package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.StorageSpaceGate
import com.arbelonson.ozen.core.WhisperFallbackPasses
import com.arbelonson.ozen.core.WhisperLoadedModel
import com.arbelonson.ozen.core.WhisperModelLoader
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelFileLoader(
    private val file: File,
    private val libraryDir: String,
    private val threads: Int = PhoneSpeedMeter.engineThreads(),
    private val download: PhoneModelDownloader? = null,
    private val network: () -> NetworkConditions? = { null },
    private val freeBytes: () -> Long? = { null },
    private val makeRoom: (Long) -> Unit = {},
    /** The model list's name for the model, which the status line shows while it downloads and is set up. */
    private val variant: String? = null,
    private val marker: ModelLoadMarker? = null,
    private val keepAwake: (Duration?) -> Unit = {},
    private val open: (path: String, libraryDir: String) -> WhisperModel? = { path, libraryDir -> WhisperModel.load(path, libraryDir) },
) : WhisperModelLoader {
    override suspend fun pendingDownloadMegabytes(): Int? = if (file.isFile) null else download?.pendingMegabytes()

    override suspend fun hasModelOnPhone(): Boolean = file.isFile

    @Volatile private var downloading: AtomicBoolean? = null

    override suspend fun cancelDownload() {
        downloading?.set(true)
    }

    override suspend fun load(
        languageCode: String,
        cellularDownloadAllowed: Boolean,
        progress: (EnginePreparationProgress) -> Unit,
    ): WhisperLoadedModel {
        if (!file.isFile) {
            fetch(download ?: throw EngineUnavailability(EngineUnavailability.Kind.ModelNotOnDevice, "${file.name} is not on this phone"), cellularDownloadAllowed, progress)
        }
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = variant ?: file.name))
        var opened: WhisperModel? = null
        try {
            withContext(Dispatchers.IO) {
                marker?.begin(file.name)
                opened = open(file.path, libraryDir)
            }
        } catch (stopped: CancellationException) {
            opened?.close()
            marker?.clear()
            throw stopped
        } catch (failed: Exception) {
            marker?.clear()
            throw failed
        }
        val model = opened ?: run {
            marker?.clear()
            throw EngineUnavailability(EngineUnavailability.Kind.ModelLoadFailed, "${file.name} did not load")
        }
        return WhisperLoadedModel(
            passes = WhisperFallbackPasses(WhisperCppPasses(model, threads)),
            release = { thread(name = "ozen-model-release") { model.close() } },
            settled = { marker?.clear() },
        )
    }

    private suspend fun fetch(download: PhoneModelDownloader, cellularAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit) {
        val stopped = AtomicBoolean(false)
        downloading = stopped
        // The listening service keeps the app up, but with the screen off the
        // processor slept between packets and hundreds of megabytes crawled.
        // It is kept up for the download and the check of the file, renewed
        // every minute against a ten-minute limit, and let go at the end.
        keepAwake(AWAKE_FOR)
        try {
            withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                val renewing = launch {
                    while (true) {
                        delay(RENEW_EVERY)
                        keepAwake(AWAKE_FOR)
                    }
                }
                try {
                    // Room is asked for only when the download can start: asking
                    // clears other apps' caches.
                    if (ModelDownloadGate.decide(network(), cellularAllowed) == ModelDownloadGate.Decision.Proceed) {
                        download.pendingMegabytes()?.let { makeRoom(StorageSpaceGate.requiredMegabytes(it) * StorageSpaceGate.BYTES_PER_MEGABYTE) }
                    }
                    var shown = -1.0
                    download.download(checking = {
                        job.ensureActive()
                        if (stopped.get()) throw IOException("stopped: another model was chosen")
                        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingModel, detail = variant ?: file.name))
                    }) { fraction ->
                        job.ensureActive()
                        if (stopped.get()) throw IOException("stopped: another model was chosen")
                        if (ModelDownloadGate.decide(network(), cellularAllowed) == ModelDownloadGate.Decision.WaitForWiFi) {
                            throw IOException("Wi-Fi went, and downloads on cellular data are off")
                        }
                        if (fraction - shown >= 0.001 || (fraction == 1.0 && shown < 1.0)) {
                            shown = fraction
                            progress(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = fraction, detail = variant ?: file.name))
                        }
                    }
                } finally {
                    renewing.cancel()
                }
            }
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (error: Exception) {
            if (!StorageSpaceGate.isOutOfSpace(error)) throw EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "${file.name}: $error")
            val left = download.pendingMegabytes()
            val missing = left?.let { StorageSpaceGate.shortfallMegabytes(it, freeBytes()) }
            throw EngineUnavailability(EngineUnavailability.Kind.NotEnoughStorage, "${file.name}: disk full: $error", left, missing)
        } finally {
            downloading = null
            keepAwake(null)
        }
    }

    companion object {
        val AWAKE_FOR = 10.minutes
        val RENEW_EVERY = 1.minutes
    }
}
