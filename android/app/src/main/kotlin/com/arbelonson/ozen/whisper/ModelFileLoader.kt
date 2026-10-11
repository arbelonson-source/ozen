package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.WhisperFallbackPasses
import com.arbelonson.ozen.core.WhisperLoadedModel
import com.arbelonson.ozen.core.WhisperModelLoader
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelFileLoader(
    private val file: File,
    private val libraryDir: String,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    private val open: (path: String, libraryDir: String) -> WhisperModel? = { path, libraryDir -> WhisperModel.load(path, libraryDir) },
) : WhisperModelLoader {
    override suspend fun load(
        languageCode: String,
        cellularDownloadAllowed: Boolean,
        progress: (EnginePreparationProgress) -> Unit,
    ): WhisperLoadedModel {
        if (!file.isFile) {
            throw EngineUnavailability(EngineUnavailability.Kind.ModelNotOnDevice, "${file.name} is not on this phone")
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
}
