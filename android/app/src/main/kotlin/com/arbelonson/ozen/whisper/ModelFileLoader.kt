package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.WhisperFallbackPasses
import com.arbelonson.ozen.core.WhisperLoadedModel
import com.arbelonson.ozen.core.WhisperModelLoader
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

class ModelFileLoader(
    private val file: File,
    private val libraryDir: String,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    private val download: PhoneModelDownloader? = null,
    private val open: (path: String, libraryDir: String) -> WhisperModel? = { path, libraryDir -> WhisperModel.load(path, libraryDir) },
) : WhisperModelLoader {
    override suspend fun pendingDownloadMegabytes(): Int? = if (file.isFile) null else download?.pendingMegabytes()

    override suspend fun load(
        languageCode: String,
        cellularDownloadAllowed: Boolean,
        progress: (EnginePreparationProgress) -> Unit,
    ): WhisperLoadedModel {
        if (!file.isFile) {
            fetch(download ?: throw EngineUnavailability(EngineUnavailability.Kind.ModelNotOnDevice, "${file.name} is not on this phone"), progress)
        }
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = file.name))
        var opened: WhisperModel? = null
        try {
            withContext(Dispatchers.IO) { opened = open(file.path, libraryDir) }
        } catch (stopped: CancellationException) {
            opened?.close()
            throw stopped
        }
        val model = opened ?: throw EngineUnavailability(EngineUnavailability.Kind.ModelLoadFailed, "${file.name} did not load")
        return WhisperLoadedModel(
            passes = WhisperFallbackPasses(WhisperCppPasses(model, threads)),
            release = { thread(name = "ozen-model-release") { model.close() } },
        )
    }

    private suspend fun fetch(download: PhoneModelDownloader, progress: (EnginePreparationProgress) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                var shown = -1.0
                download.download { fraction ->
                    job.ensureActive()
                    if (fraction - shown >= 0.001 || (fraction == 1.0 && shown < 1.0)) {
                        shown = fraction
                        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = fraction, detail = file.name))
                    }
                }
            }
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (error: Exception) {
            throw EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "${file.name}: $error")
        }
    }
}
