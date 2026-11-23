package com.arbelonson.ozen

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import com.arbelonson.ozen.core.RestartBudget
import com.arbelonson.ozen.core.SoundClassifierSupervisor
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

class YamnetSoundDetector(
    private val context: Context,
    private val readModel: (AssetManager, String) -> ByteBuffer = ModelAssets::read,
) : SoundEventDetecting {
    override fun observations(audio: Flow<FloatArray>): Flow<SoundObservation> = observations(audio) {}

    override fun observations(audio: Flow<FloatArray>, availability: (Boolean) -> Unit): Flow<SoundObservation> = flow {
        val windows = SoundWindows()
        val supervisor = SoundClassifierSupervisor(RestartBudget(MAXIMUM_RESTARTS, RESTART_WINDOW_SECONDS, RESTART_SPACING_SECONDS))
        var classifier: Classifier? = null
        try {
            audio.collect { chunk ->
                if (chunk.isEmpty()) supervisor.audioGap()
                for (window in windows.add(chunk)) {
                    val now = System.currentTimeMillis() / 1_000.0
                    when (supervisor.step(classifier != null, SystemClock.elapsedRealtime() / 1_000.0)) {
                        SoundClassifierSupervisor.Step.Skip -> {
                            supervisor.takeChange()?.let(availability)
                            continue
                        }
                        SoundClassifierSupervisor.Step.Restart -> {
                            classifier = load()
                            if (classifier == null) supervisor.restartGaveNoClassifier()
                        }
                        SoundClassifierSupervisor.Step.Classify -> Unit
                    }
                    supervisor.takeChange()?.let(availability)
                    val current = classifier ?: continue
                    try {
                        current.interpreter.run(window, current.scores)
                    } catch (error: Throwable) {
                        if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
                        current.interpreter.close()
                        classifier = null
                        continue
                    }
                    val candidates = YamnetSounds.candidates(current.scores[0], current.indices)
                    for (observation in SoundEventCatalog.matchingObservations(candidates, FORWARDING_CONFIDENCE, now)) emit(observation)
                }
            }
        } finally {
            classifier?.interpreter?.close()
        }
    }.flowOn(Dispatchers.Default)

    private class Classifier(val interpreter: Interpreter, val indices: Map<String, List<Int>>, val scores: Array<FloatArray>)

    private fun load(): Classifier? {
        var interpreter: Interpreter? = null
        return try {
            val labels = context.assets.open(LABELS).bufferedReader().use { it.readLines() }
            interpreter = Interpreter(readModel(context.assets, MODEL), Interpreter.Options().setNumThreads(1))
            Classifier(interpreter, YamnetSounds.indices(labels), Array(1) { FloatArray(labels.size) })
        } catch (error: Throwable) {
            if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
            interpreter?.close()
            null
        }
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
