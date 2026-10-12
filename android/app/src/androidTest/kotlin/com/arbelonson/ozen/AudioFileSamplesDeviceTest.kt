package com.arbelonson.ozen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.SampleRateConverter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioFileSamplesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir, "audio-file-test").apply { deleteRecursively(); mkdirs() }
    private val original = DeviceClips.asset("speakerA_clip1.wav")

    @Test
    fun aRecordingAlreadyAtSixteenKilohertzComesBackSampleForSample() {
        val file = File(folder, "clip.wav")
        file.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("speakerA_clip1.wav").use { it.readBytes() })
        val loaded = AudioFileSamples.load(context, Uri.fromFile(file))
        assertNotNull(loaded)
        assertTrue("${loaded!!.size} samples against ${original.size}", abs(loaded.size - original.size) < 50)
        val largest = (0 until min(loaded.size, original.size)).maxOf { abs(loaded[it] - original[it]) }
        assertTrue("differs by $largest", largest < 0.001f)
    }

    @Test
    fun aFortyEightKilohertzStereoRecordingIsTheSameVoiceAtSixteenKilohertz() {
        val file = File(folder, "stereo.wav")
        val high = FloatArray(original.size * 3) {
            val low = original[it / 3]
            low + (original[min(it / 3 + 1, original.size - 1)] - low) * (it % 3) / 3f
        }
        DeviceClips.writeWav(file, high, rate = 48_000, channels = 2)
        val loaded = AudioFileSamples.load(context, Uri.fromFile(file))
        assertNotNull(loaded)
        assertTrue("${loaded!!.size} samples against ${original.size}", abs(loaded.size - original.size) < 200)
        assertSameVoice(loaded, 0.95f)
    }

    @Test
    fun aVoiceMemoCompressedAsAacIsTheSameVoiceAtSixteenKilohertz() {
        val file = File(folder, "memo.m4a")
        writeAac(file, SampleRateConverter.convert(original, from = 16_000, to = 44_100), rate = 44_100)
        val loaded = AudioFileSamples.load(context, Uri.fromFile(file))
        assertNotNull(loaded)
        assertTrue("${loaded!!.size} samples against ${original.size}", abs(loaded.size - original.size) < 2_048)
        assertSameVoice(loaded, 0.9f)
    }

    // Ten minutes is the most a recording is read for. At 48 kHz its
    // samples alone are 115 MB, and a second copy of them took the app past
    // Android's 192 MB limit: the recording was turned away as unusable.
    @Test
    fun aTenMinuteRecordingAtFortyEightKilohertzLoadsWhole() {
        val file = File(folder, "long.wav")
        val seconds = AudioFileSamples.MAXIMUM_SECONDS.toInt()
        writeLongStereoWav(file, seconds, rate = 48_000)
        try {
            val loaded = AudioFileSamples.load(context, Uri.fromFile(file))
            assertNotNull("a ten-minute recording at 48 kHz came back as nothing", loaded)
            assertTrue("${loaded!!.size} samples", abs(loaded.size - seconds * 16_000) < 2_000)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aFileThatIsNotAudioGivesNothingInsteadOfCrashing() {
        val file = File(folder, "note.m4a").apply { writeText("not a recording") }
        assertNull(AudioFileSamples.load(context, Uri.fromFile(file)))
    }

    private fun writeLongStereoWav(file: File, seconds: Int, rate: Int) {
        val frames = seconds.toLong() * rate
        val bytes = frames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4)
        header.putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(bytes.toInt())
        file.outputStream().buffered(1 shl 20).use { out ->
            out.write(header.array())
            val chunk = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0L
            while (written < frames) {
                chunk.clear()
                repeat(min(rate.toLong(), frames - written).toInt()) {
                    val value = (original[((written + it) % original.size).toInt()] * 32_767).toInt().toShort()
                    chunk.putShort(value).putShort(value)
                }
                out.write(chunk.array(), 0, chunk.position())
                written += rate
            }
        }
    }

    private fun assertSameVoice(loaded: FloatArray, least: Float) {
        val embedder = CamPlusPlusEmbedder(context)
        val before = embedder.embed(original, 16_000.0)
        val after = embedder.embed(loaded, 16_000.0)
        assertNotNull(before)
        assertNotNull(after)
        val score = DeviceClips.cosine(before!!, after!!)
        assertTrue("the voice scored $score", score > least)
    }

    private fun writeAac(file: File, samples: FloatArray, rate: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) pcm.putShort((value.coerceIn(-1f, 1f) * 32_767).toInt().toShort())
        pcm.flip()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var fed = false
        var frames = 0L
        while (true) {
            if (!fed) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = encoder.getInputBuffer(index)!!
                    buffer.clear()
                    val count = min(buffer.remaining(), pcm.remaining()) / 2 * 2
                    val time = frames * 1_000_000L / rate
                    if (count == 0) {
                        encoder.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        val chunk = ByteArray(count)
                        pcm.get(chunk)
                        buffer.put(chunk)
                        encoder.queueInputBuffer(index, 0, count, time, 0)
                        frames += count / 2
                    }
                }
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.outputFormat)
                muxer.start()
            } else if (out >= 0) {
                val buffer = encoder.getOutputBuffer(out)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) muxer.writeSampleData(track, buffer, info)
                encoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }
}
