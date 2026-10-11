package com.arbelonson.ozen

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arbelonson.ozen.core.SampleRateConverter
import java.nio.ByteOrder

object AudioFileSamples {
    const val MAXIMUM_SECONDS = 600.0
    private const val WAIT_MICROSECONDS = 10_000L
    private const val MOST_IDLE_TURNS = 1_000

    fun load(context: Context, uri: Uri, sampleRate: Int = 16_000): FloatArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(input, null, null, 0)
            codec.start()
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val seconds = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else 10.0
            var mono = FloatArray((minOf(seconds, MAXIMUM_SECONDS) * rate).toInt() + rate)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var fed = false
            var idle = 0
            while (idle < MOST_IDLE_TURNS && count < MAXIMUM_SECONDS * rate) {
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
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (out >= 0) {
                    idle = 0
                    val bytes = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    bytes.position(info.offset).limit(info.offset + info.size)
                    val frames = when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> bytes.asShortBuffer().let { pcm -> FloatArray(pcm.remaining()) { pcm.get(it) / 32_768f } }
                        AudioFormat.ENCODING_PCM_FLOAT -> bytes.asFloatBuffer().let { pcm -> FloatArray(pcm.remaining()) { pcm.get(it) } }
                        else -> return null
                    }
                    codec.releaseOutputBuffer(out, false)
                    val added = frames.size / channels
                    if (count + added > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, count + added))
                    for (frame in 0 until added) {
                        var sum = 0f
                        for (channel in 0 until channels) sum += frames[frame * channels + channel]
                        mono[count + frame] = sum / channels
                    }
                    count += added
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val heard = mono.copyOf(minOf(count, (MAXIMUM_SECONDS * rate).toInt()))
            if (heard.isEmpty()) null else SampleRateConverter.convert(heard, from = rate, to = sampleRate)
        } catch (unreadable: Exception) {
            null
        } catch (tooBig: OutOfMemoryError) {
            null
        } finally {
            decoder?.let { codec -> runCatching { codec.stop() }; codec.release() }
            extractor.release()
        }
    }
}
