package com.arbelonson.ozen

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

class MicrophoneCapture(private val onChunk: (FloatArray) -> Unit) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, CHUNK * 2 * 4),
            )
        } catch (_: SecurityException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        running = true
        worker = thread(name = "ozen-microphone") {
            val buffer = ShortArray(CHUNK)
            try {
                while (running) {
                    val read = record.read(buffer, 0, CHUNK)
                    if (read < 0) break
                    if (read > 0) onChunk(FloatArray(read) { buffer[it] / 32768f })
                }
            } finally {
                record.stop()
                record.release()
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 1_600
    }
}
