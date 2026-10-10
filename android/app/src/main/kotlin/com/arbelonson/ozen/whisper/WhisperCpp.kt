package com.arbelonson.ozen.whisper

data class WhisperSegment(val text: String, val noSpeech: Float, val averageLogprob: Float)

class WhisperModel private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun transcribe(audio: FloatArray, language: String, prompt: String?, threads: Int, beam: Int, finished: Boolean): List<WhisperSegment>? {
        check(handle != 0L) { "closed" }
        val count = WhisperCpp.transcribe(handle, audio, language, prompt, threads, beam, finished)
        if (count < 0) return null
        return (0 until count).map { segment ->
            WhisperSegment(
                text = WhisperCpp.segmentText(handle, segment).toString(Charsets.UTF_8).trim(),
                noSpeech = WhisperCpp.segmentNoSpeech(handle, segment),
                averageLogprob = WhisperCpp.segmentLogprob(handle, segment),
            )
        }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) WhisperCpp.free(handle)
        handle = 0L
    }

    companion object {
        fun load(path: String): WhisperModel? = WhisperCpp.load(path).takeIf { it != 0L }?.let(::WhisperModel)
    }
}

internal object WhisperCpp {
    init {
        System.loadLibrary("ozen_whisper")
    }

    external fun load(path: String): Long
    external fun free(handle: Long)
    external fun transcribe(
        handle: Long, audio: FloatArray, language: String, prompt: String?, threads: Int, beam: Int, finished: Boolean,
    ): Int
    external fun segmentText(handle: Long, segment: Int): ByteArray
    external fun segmentNoSpeech(handle: Long, segment: Int): Float
    external fun segmentLogprob(handle: Long, segment: Int): Float
    external fun systemInfo(): String
}
