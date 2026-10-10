package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.WhisperFallbackPasses
import com.arbelonson.ozen.core.WhisperLoadedModel
import com.arbelonson.ozen.core.WhisperModelLoader
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelFileLoader(
    private val file: File,
    private val libraryDir: String,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
) : WhisperModelLoader {
    override suspend fun load(
        languageCode: String,
        cellularDownloadAllowed: Boolean,
        progress: (EnginePreparationProgress) -> Unit,
    ): WhisperLoadedModel {
        if (!file.isFile) {
            throw EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "${file.name} is not on this phone")
        }
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = file.name))
        val model = withContext(Dispatchers.IO) { WhisperModel.load(file.path, libraryDir) }
            ?: throw EngineUnavailability(EngineUnavailability.Kind.ModelLoadFailed, "${file.name} did not load")
        return WhisperLoadedModel(
            passes = WhisperFallbackPasses(WhisperCppPasses(model, threads)),
            release = { thread(name = "ozen-model-release") { model.close() } },
        )
    }
}
