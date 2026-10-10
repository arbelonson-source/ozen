package com.arbelonson.ozen.core

import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Splits one audio flow into several independent consumers (the
 * transcription engine, the speaker embedder, and later the sound-event
 * classifier all need every sample) without any of them blocking or
 * starving the others. Each output gets its own buffer, so a consumer
 * that's briefly slow (Whisper mid-inference) just falls a little behind
 * rather than dropping audio; consumers are expected to catch up (the
 * engines are written to accumulate quickly and infer separately).
 *
 * The buffers are large but not endless. A consumer that stops reading
 * for good while captions run (the sound classifier's request failing
 * mid-conversation, say) would otherwise pile up every sample for the
 * rest of the evening: about 230 MB an hour. Past the limit an output
 * keeps the newest audio and lets the oldest go.
 *
 * A glitched buffer (a Bluetooth microphone reconnecting, a converter
 * hiccup) can carry NaN or infinite samples, which spoil everything they
 * touch: a Whisper window turns to nonsense, a voice print never matches.
 * They reach the consumers as silence, and [onGlitch] hears of each such
 * chunk.
 *
 * The pump that reads [source] runs in [scope]. Each output is read once.
 */
class FlowAudioFanOut(
    scope: CoroutineScope,
    source: Flow<FloatArray>,
    count: Int,
    bufferLimit: Int = AudioFanOut.DEFAULT_BUFFER_LIMIT,
    onGlitch: () -> Unit = {},
) {
    val outputs: List<Flow<FloatArray>>
    private val pump: Job

    init {
        val channels = List(max(count, 1)) {
            Channel<FloatArray>(max(bufferLimit, 1), BufferOverflow.DROP_OLDEST)
        }
        outputs = channels.map { it.receiveAsFlow() }
        pump = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                source.collect { chunk ->
                    ensureActive()
                    val glitched = !chunk.all { it.isFinite() }
                    val forwarded = if (glitched) AudioFanOut.withoutGlitches(chunk) else chunk
                    if (glitched) onGlitch()
                    for (channel in channels) channel.trySend(forwarded)
                }
            } finally {
                for (channel in channels) channel.close()
            }
        }
    }

    /**
     * Stops forwarding and finishes every output. The source flow is
     * left to its owner (the audio capturer) to end.
     */
    fun cancel() {
        pump.cancel()
    }
}
