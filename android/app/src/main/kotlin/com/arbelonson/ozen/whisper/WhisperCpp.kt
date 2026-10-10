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
    fun <T> withHandle(block: (Long) -> T): T {
        check(handle != 0L) { "closed" }
        return block(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) WhisperCpp.free(handle)
        handle = 0L
    }

    companion object {
        fun load(path: String, libraryDir: String): WhisperModel? {
            if (!WhisperCpp.loadCpu(libraryDir)) return null
            return WhisperCpp.load(path).takeIf { it != 0L }?.let(::WhisperModel)
        }
    }
}

internal object WhisperCpp {
    init {
        System.loadLibrary("ozen_whisper")
    }

    private var cpuLoaded = false

    @Synchronized
    fun loadCpu(libraryDir: String): Boolean {
        if (!cpuLoaded) cpuLoaded = loadBackends(libraryDir)
        return cpuLoaded
    }

    private external fun loadBackends(folder: String): Boolean

    external fun load(path: String): Long
    external fun free(handle: Long)
    external fun transcribe(
        handle: Long, audio: FloatArray, language: String, prompt: String?, threads: Int, beam: Int, finished: Boolean,
    ): Int
    external fun segmentText(handle: Long, segment: Int): ByteArray
    external fun segmentNoSpeech(handle: Long, segment: Int): Float
    external fun segmentLogprob(handle: Long, segment: Int): Float
    external fun pass(
        handle: Long, audio: FloatArray, language: String, prompt: IntArray?, maxTokens: Int, temperature: Float,
        threads: Int, suppressBlank: Boolean, noTimestamps: Boolean, audioContext: Int,
    ): Int
    external fun segmentTokenIds(handle: Long, segment: Int): IntArray
    external fun segmentTokenProbabilities(handle: Long, segment: Int): FloatArray
    external fun tokenPiece(handle: Long, token: Int): ByteArray
    external fun tokenize(handle: Long, text: String): IntArray?
    external fun tokenEot(handle: Long): Int
    external fun systemInfo(): String
    external fun vadLoad(path: String): Long
    external fun vadFrames(handle: Long, samples: FloatArray): FloatArray?
    external fun vadReset(handle: Long)
    external fun vadFree(handle: Long)
}
