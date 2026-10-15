package com.arbelonson.ozen.core

import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class FlowAudioFanOut(
    scope: CoroutineScope,
    source: Flow<FloatArray>,
    count: Int,
    bufferLimit: Int = AudioFanOut.DEFAULT_BUFFER_LIMIT,
    onGlitch: () -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
) {
    val outputs: List<Flow<FloatArray>>
    private val pump: Job

    init {
        val channels = List(max(count, 1)) {
            Channel<FloatArray>(max(bufferLimit, 1), BufferOverflow.DROP_OLDEST)
        }
        outputs = channels.map { it.receiveAsFlow() }
        pump = scope.launch(start = CoroutineStart.ATOMIC) {
            var failure: Exception? = null
            try {
                source.collect { chunk ->
                    ensureActive()
                    val glitched = !chunk.all { it.isFinite() }
                    val forwarded = if (glitched) AudioFanOut.withoutGlitches(chunk) else chunk
                    if (glitched) onGlitch()
                    for (channel in channels) channel.trySend(forwarded)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            } finally {
                for (channel in channels) channel.close()
            }
            failure?.let(onFailure)
        }
    }

    fun cancel() {
        pump.cancel()
    }
}
