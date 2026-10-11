package com.arbelonson.ozen

import android.content.Context
import com.arbelonson.ozen.core.RestartBudget
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.SoundEventDetecting
import com.arbelonson.ozen.core.SoundObservation
import com.arbelonson.ozen.core.SoundWindows
import com.arbelonson.ozen.core.YamnetSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YamnetSoundDetector(private val context: Context) : SoundEventDetecting {
    override fun observations(audio: Flow<FloatArray>): Flow<SoundObservation> = flow {
        val labels = context.assets.open(LABELS).bufferedReader().use { it.readLines() }
        val indices = YamnetSounds.indices(labels)
        val windows = SoundWindows()
        val scores = Array(1) { FloatArray(labels.size) }
        val budget = RestartBudget(MAXIMUM_RESTARTS, RESTART_WINDOW_SECONDS, RESTART_SPACING_SECONDS)
        var interpreter: Interpreter? = null
        try {
            audio.collect { chunk ->
                for (window in windows.add(chunk)) {
                    val now = System.currentTimeMillis() / 1_000.0
                    val model = interpreter ?: (if (budget.spend(now)) load() else null)?.also { interpreter = it } ?: continue
                    try {
                        model.run(window, scores)
                    } catch (error: RuntimeException) {
                        model.close()
                        interpreter = null
                        continue
                    }
                    val candidates = YamnetSounds.candidates(scores[0], indices)
                    for (observation in SoundEventCatalog.matchingObservations(candidates, FORWARDING_CONFIDENCE, now)) emit(observation)
                }
            }
        } finally {
            interpreter?.close()
        }
    }.flowOn(Dispatchers.Default)

    private fun load(): Interpreter? = try {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        model.rewind()
        Interpreter(model, Interpreter.Options().setNumThreads(1))
    } catch (error: Exception) {
        null
    }

    companion object {
        const val MODEL = "yamnet.tflite"
        const val LABELS = "yamnet_labels.txt"
        private const val FORWARDING_CONFIDENCE = 0.3
        private const val MAXIMUM_RESTARTS = 5
        private const val RESTART_WINDOW_SECONDS = 600.0
        private const val RESTART_SPACING_SECONDS = 15.0
    }
}
