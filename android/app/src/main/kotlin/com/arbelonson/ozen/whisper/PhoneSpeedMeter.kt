package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.PhoneSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PhoneSpeedMeter(
    private val libraryDir: String,
    private val threads: Int = engineThreads(),
    private val rounds: Int = ROUNDS,
    private val roundMillis: Int = ROUND_MILLIS,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val ready: () -> Boolean = { WhisperCpp.loadCpu(libraryDir) },
    private val measure: (threads: Int, millis: Int) -> Double = { count, millis -> WhisperCpp.benchMatMul(count, millis) },
) {
    suspend fun run(progress: (Float) -> Unit): PhoneSpeed? = withContext(Dispatchers.Default) {
        if (!ready()) return@withContext null
        val speeds = ArrayList<Double>()
        progress(0f)
        repeat(rounds) { round ->
            ensureActive()
            speeds += measure(threads, roundMillis)
            progress((round + 1).toFloat() / rounds)
        }
        val usable = speeds.filter { it > 0 }.sorted()
        if (usable.isEmpty()) null else PhoneSpeed(usable[usable.size / 2], threads, now())
    }

    companion object {
        const val ROUNDS = 5
        const val ROUND_MILLIS = 2_000

        fun engineThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
