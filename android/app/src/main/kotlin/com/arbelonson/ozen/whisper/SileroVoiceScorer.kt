package com.arbelonson.ozen.whisper

import android.content.Context
import com.arbelonson.ozen.core.SileroScores
import com.arbelonson.ozen.core.VoiceEvidence
import java.io.File

class SileroVoiceScorer private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun score(input: FloatArray): Float? {
        if (handle == 0L || input.size != VoiceEvidence.CONTEXT_SAMPLES + VoiceEvidence.CHUNK_SAMPLES) return null
        val frames = WhisperCpp.vadFrames(handle, input.copyOfRange(VoiceEvidence.CONTEXT_SAMPLES, input.size)) ?: return null
        return SileroScores.noisyOr(frames)
    }

    @Synchronized
    fun reset() {
        if (handle != 0L) WhisperCpp.vadReset(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) WhisperCpp.vadFree(handle)
        handle = 0L
    }

    companion object {
        const val MODEL_ASSET = "silero-v6.2.0-ggml.bin"

        fun fromAssets(context: Context): SileroVoiceScorer? {
            val model = File(context.noBackupFilesDir, MODEL_ASSET)
            val size = context.assets.openFd(MODEL_ASSET).use { it.length }
            if (model.length() != size) {
                val partial = File(model.path + ".partial")
                context.assets.open(MODEL_ASSET).use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (!partial.renameTo(model)) return null
            }
            if (!WhisperCpp.loadCpu(context.applicationInfo.nativeLibraryDir)) return null
            return WhisperCpp.vadLoad(model.path).takeIf { it != 0L }?.let(::SileroVoiceScorer)
        }
    }
}
