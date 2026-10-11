package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * An 80-bin, Kaldi-compatible log mel filterbank: the front end the CAM++
 * voice-print model was trained on, the same as the iPhone's KaldiFBank.
 * It has to match the reference feature extractor (`kaldi_native_fbank`,
 * which WeSpeaker's own pipeline uses) closely enough that a print made
 * here lines up with one made there; `KaldiFBankTest` checks it against
 * that library's frames on real speech.
 *
 * 25 ms frames every 10 ms, DC removal, then 0.97 pre-emphasis, a Povey
 * window (Hamming tapered to zero at both edges), an 80-band triangular
 * filterbank in Kaldi's mel scale (`1127 * ln(1 + hz / 700)`), a natural
 * log with Kaldi's floor, then each band's mean over time subtracted, which
 * also cancels any constant scale of the sound or the FFT.
 */
class KaldiFBank {
    private val window = DoubleArray(FRAME_SIZE) { i -> (0.5 - 0.5 * cos(2 * PI * i / (FRAME_SIZE - 1))).pow(POVEY_EXPONENT) }
    private var filterbank: Pair<Double, Array<DoubleArray>>? = null

    /** One row per frame, [MEL_BIN_COUNT] log energies each, mean-subtracted; null when too short for one frame. */
    fun frames(samples: FloatArray, sampleRate: Double): Array<FloatArray>? {
        if (samples.size < FRAME_SIZE) return null
        val filters = filterbank?.takeIf { it.first == sampleRate }?.second ?: melFilterbank(sampleRate).also { filterbank = sampleRate to it }
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val frames = ArrayList<FloatArray>()
        var offset = 0
        while (offset + FRAME_SIZE <= samples.size) {
            frames += logMelEnergies(samples, offset, filters, real, imag)
            offset += HOP_SIZE
        }
        return meanNormalized(frames)
    }

    private fun logMelEnergies(samples: FloatArray, offset: Int, filters: Array<DoubleArray>, real: DoubleArray, imag: DoubleArray): FloatArray {
        var mean = 0.0
        for (i in 0 until FRAME_SIZE) mean += samples[offset + i]
        mean /= FRAME_SIZE
        for (i in 0 until FRAME_SIZE) real[i] = samples[offset + i] - mean
        // High index to low, so each sample still reads its original predecessor.
        for (i in FRAME_SIZE - 1 downTo 1) real[i] -= PREEMPHASIS * real[i - 1]
        real[0] -= PREEMPHASIS * real[0]
        for (i in 0 until FRAME_SIZE) real[i] *= window[i]
        real.fill(0.0, FRAME_SIZE, FFT_SIZE)
        imag.fill(0.0)
        fft(real, imag)
        return FloatArray(MEL_BIN_COUNT) { bin ->
            var sum = 0.0
            val filter = filters[bin]
            for (k in 0 until FFT_SIZE / 2) sum += (real[k] * real[k] + imag[k] * imag[k]) * filter[k]
            ln(maxOf(sum, FLOAT_EPSILON)).toFloat()
        }
    }

    private fun meanNormalized(frames: List<FloatArray>): Array<FloatArray> {
        val means = DoubleArray(MEL_BIN_COUNT)
        for (frame in frames) for (i in 0 until MEL_BIN_COUNT) means[i] += frame[i].toDouble()
        for (i in 0 until MEL_BIN_COUNT) means[i] /= frames.size
        return Array(frames.size) { at -> FloatArray(MEL_BIN_COUNT) { i -> (frames[at][i] - means[i]).toFloat() } }
    }

    /**
     * Kaldi's triangular filters: edges spaced evenly on its mel scale, each
     * filter's weight at an FFT bin taken from that bin's centre frequency.
     */
    private fun melFilterbank(sampleRate: Double): Array<DoubleArray> {
        fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
        val binWidth = sampleRate / FFT_SIZE
        val low = mel(LOW_FREQUENCY_HZ)
        val delta = (mel(sampleRate / 2) - low) / (MEL_BIN_COUNT + 1)
        return Array(MEL_BIN_COUNT) { bin ->
            val left = low + bin * delta
            val centre = low + (bin + 1) * delta
            val right = low + (bin + 2) * delta
            DoubleArray(FFT_SIZE / 2) { i ->
                val at = mel(binWidth * i)
                when {
                    at <= left || at >= right -> 0.0
                    at <= centre -> (at - left) / (centre - left)
                    else -> (right - at) / (right - centre)
                }
            }
        }
    }

    /** An in-place radix-2 FFT of [FFT_SIZE] points. */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val half = length / 2
            for (k in 0 until half) {
                val angle = -2 * PI * k / length
                val wReal = cos(angle)
                val wImag = sin(angle)
                for (start in 0 until n step length) {
                    val a = start + k
                    val b = a + half
                    val tReal = real[b] * wReal - imag[b] * wImag
                    val tImag = real[b] * wImag + imag[b] * wReal
                    real[b] = real[a] - tReal
                    imag[b] = imag[a] - tImag
                    real[a] += tReal
                    imag[a] += tImag
                }
            }
            length = length shl 1
        }
    }

    companion object {
        const val MEL_BIN_COUNT = 80
        private const val FRAME_SIZE = 400
        private const val HOP_SIZE = 160
        private const val FFT_SIZE = 512
        private const val PREEMPHASIS = 0.97
        private const val POVEY_EXPONENT = 0.85
        private const val LOW_FREQUENCY_HZ = 20.0
        private const val FLOAT_EPSILON = 1.1920928955078125e-7
    }
}
