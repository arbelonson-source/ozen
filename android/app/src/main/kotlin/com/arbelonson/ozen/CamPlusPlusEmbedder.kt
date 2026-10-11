package com.arbelonson.ozen

import android.content.Context
import com.arbelonson.ozen.core.KaldiFBank
import com.arbelonson.ozen.core.SpeakerEmbedding
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CamPlusPlusEmbedder(private val context: Context) : SpeakerEmbedding {
    private val fbank = KaldiFBank()
    private var interpreter: Interpreter? = null
    private var loadFailed = false

    override val embeddingLength: Int get() = EMBEDDING_LENGTH

    @Synchronized
    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
        val model = model() ?: return null
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

    private fun model(): Interpreter? {
        interpreter?.let { return it }
        if (loadFailed) return null
        return try {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buffer.rewind()
            Interpreter(buffer, Interpreter.Options().setNumThreads(1)).also { interpreter = it }
        } catch (error: Exception) {
            loadFailed = true
            null
        }
    }

    companion object {
        const val MODEL = "campp.tflite"
        private const val FRAME_COUNT = 148
        private const val EMBEDDING_LENGTH = 512
    }
}
