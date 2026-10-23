package com.arbelonson.ozen

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arbelonson.ozen.core.SampleRateConverter
import java.nio.ByteOrder

enum class DecodeOutcome { Finished, Stopped, NoAudio, Unreadable, Stalled }

object AudioFileDecoder {
    private const val WAIT_MICROSECONDS = 10_000L
    private const val MOST_IDLE_TURNS = 1_000
    private const val PIECE_SECONDS = 1

    sealed interface Probe {
        data class Playable(val durationSeconds: Double?) : Probe
        data object NoAudio : Probe
        data object Unreadable : Probe
    }

    fun probe(context: Context, uri: Uri): Probe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: return Probe.NoAudio
            val format = extractor.getTrackFormat(track)
            Probe.Playable(if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null)
        } catch (unreadable: Exception) {
            Probe.Unreadable
        } finally {
            extractor.release()
        }
    }

    /**
     * Reads the audio of a recording (the sound track of a video too) as
     * 16 kHz mono, a second or so at a time, so an hour-long lecture is
     * never in memory whole. [onPiece] returns false to stop early.
     */
    fun decode(context: Context, uri: Uri, sampleRate: Int = 16_000, onPiece: (FloatArray) -> Boolean): DecodeOutcome {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: return DecodeOutcome.NoAudio
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(input, null, null, 0)
            codec.start()
            decodeLoop(extractor, codec, input, sampleRate, onPiece)
        } catch (unreadable: Exception) {
            DecodeOutcome.Unreadable
        } catch (tooBig: OutOfMemoryError) {
            DecodeOutcome.Unreadable
        } finally {
            decoder?.let { codec -> runCatching { codec.stop() }; codec.release() }
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat, sampleRate: Int, onPiece: (FloatArray) -> Boolean): DecodeOutcome {
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var stream = SampleRateConverter.Stream(rate, sampleRate)
        var waiting = ArrayList<FloatArray>()
        var waitingSamples = 0
        var gotAny = false
        val info = MediaCodec.BufferInfo()
        var fed = false
        var idle = 0
        var ended = false
        fun pass(final: Boolean): Boolean {
            if (final) stream.finish().let { if (it.isNotEmpty()) { waiting.add(it); waitingSamples += it.size } }
            else stream.drain().let { if (it.isNotEmpty()) { waiting.add(it); waitingSamples += it.size } }
            if (waitingSamples == 0 || (!final && waitingSamples < PIECE_SECONDS * sampleRate)) return true
            val piece = FloatArray(waitingSamples)
            var at = 0
            for (part in waiting) { part.copyInto(piece, at); at += part.size }
            waiting = ArrayList()
            waitingSamples = 0
            gotAny = true
            return onPiece(piece)
        }
        while (idle < MOST_IDLE_TURNS && !ended) {
            idle++
            if (!fed) {
                val index = codec.dequeueInputBuffer(WAIT_MICROSECONDS)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    idle = 0
                }
            }
            val out = codec.dequeueOutputBuffer(info, WAIT_MICROSECONDS)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                if (newRate != rate) {
                    if (!pass(final = true)) return DecodeOutcome.Stopped
                    rate = newRate
                    stream = SampleRateConverter.Stream(rate, sampleRate)
                }
            } else if (out >= 0) {
                idle = 0
                val bytes = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                bytes.position(info.offset).limit(info.offset + info.size)
                val frames = when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> bytes.asShortBuffer().let { pcm -> FloatArray(pcm.remaining()) { pcm.get(it) / 32_768f } }
                    AudioFormat.ENCODING_PCM_FLOAT -> bytes.asFloatBuffer().let { pcm -> FloatArray(pcm.remaining()) { pcm.get(it) } }
                    else -> return DecodeOutcome.Unreadable
                }
                codec.releaseOutputBuffer(out, false)
                val count = frames.size / channels
                stream.add(FloatArray(count) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) sum += frames[frame * channels + channel]
                    sum / channels
                })
                if (!pass(final = false)) return DecodeOutcome.Stopped
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
            }
        }
        if (!pass(final = true)) return DecodeOutcome.Stopped
        return when {
            !gotAny -> DecodeOutcome.Unreadable
            !ended -> DecodeOutcome.Stalled
            else -> DecodeOutcome.Finished
        }
    }
}
