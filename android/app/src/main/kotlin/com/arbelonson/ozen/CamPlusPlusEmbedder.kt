package com.arbelonson.ozen

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import com.arbelonson.ozen.core.KaldiFBank
import com.arbelonson.ozen.core.RetryingModel
import com.arbelonson.ozen.core.SpeakerEmbedding
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

class CamPlusPlusEmbedder(
    private val context: Context,
    note: (String) -> Unit = {},
    nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val readModel: (AssetManager, String) -> ByteBuffer = ModelAssets::read,
) : SpeakerEmbedding {
    private val fbank = KaldiFBank()
    private val model = RetryingModel("speaker voice model", ::loadInterpreter, nowMillis, note)

    override val embeddingLength: Int get() = EMBEDDING_LENGTH

    @Synchronized
    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
        val model = model.get() ?: return null
        val frames = fbank.frames(samples, sampleRate) ?: return null
        val input = arrayOf(Array(FRAME_COUNT) { at -> frames.getOrNull(at) ?: FloatArray(KaldiFBank.MEL_BIN_COUNT) })
        val output = arrayOf(FloatArray(EMBEDDING_LENGTH))
        try {
            model.run(input, output)
        } catch (error: RuntimeException) {
            return null
        }
        return output[0].takeIf { print -> print.all { it.isFinite() } }
    }

    private fun loadInterpreter(): Interpreter = Interpreter(readModel(context.assets, MODEL), Interpreter.Options().setNumThreads(1))

    companion object {
        const val MODEL = "campp.tflite"
        private const val FRAME_COUNT = 148
        private const val EMBEDDING_LENGTH = 512
    }
}
