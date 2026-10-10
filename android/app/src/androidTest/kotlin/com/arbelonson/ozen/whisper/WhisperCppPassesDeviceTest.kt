package com.arbelonson.ozen.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.WhisperFallbackPasses
import com.arbelonson.ozen.core.WhisperPassOptions
import com.arbelonson.ozen.core.WhisperSegment as PassSegment
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperCppPassesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.filesDir, "device-test")
    private lateinit var model: WhisperModel
    private lateinit var passes: WhisperCppPasses

    @Before
    fun load() {
        val file = File(folder, "model.bin")
        assumeTrue("put model.bin, clip.wav and clip.txt in ${folder.path}", file.exists() && File(folder, "clip.wav").exists())
        model = checkNotNull(WhisperModel.load(file.path, context.applicationInfo.nativeLibraryDir)) { "the model did not load" }
        passes = WhisperCppPasses(model, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }

    @After
    fun close() {
        if (::model.isInitialized) model.close()
    }

    private fun words(segments: List<PassSegment>): String {
        val bytes = ByteArrayOutputStream()
        for (token in segments.flatMap { it.tokens }.filter { !it.isSpecial }) bytes.write(token.piece)
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun theEndOfTextTokenIsWhereWhisperKitPutsTheSpecialTokens() {
        assertEquals(50_257, passes.specialTokenBegin)
    }

    @Test
    fun hebrewTokenizesIntoWordPiecesThatJoinBackIntoTheSameText() {
        val ids = passes.tokenize(" שלום, מה שלומך")
        assertTrue(ids.isNotEmpty() && ids.all { it < passes.specialTokenBegin })
        val bytes = ByteArrayOutputStream()
        model.withHandle { handle -> ids.forEach { bytes.write(WhisperCpp.tokenPiece(handle, it)) } }
        assertEquals(" שלום, מה שלומך", String(bytes.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun aFinalPassGivesTheSegmentsTextBackFromItsOwnTokens() {
        val audio = clip()
        val segments = runBlocking { WhisperFallbackPasses(passes).run(audio, WhisperPassOptions.final("he")) }
        assertTrue("nothing heard", segments.isNotEmpty())
        val text = segments.joinToString("") { it.text }
        assertEquals(text.trim(), words(segments).trim())
        val chances = segments.flatMap { it.tokens }.map { it.probability }
        assertTrue("$chances", chances.all { it > 0f && it <= 1f })
        assertTrue("an end of text token", segments.last().tokens.any { it.id == passes.specialTokenBegin })
    }

    @Test
    fun aLivePassStopsAtItsTokenCap() {
        val audio = clip()
        val segments = runBlocking { passes.once(audio, WhisperPassOptions.live("he").copy(maxTokens = 3), 0f) }
        val wordTokens = segments.flatMap { it.tokens }.count { !it.isSpecial }
        assertTrue("$wordTokens word tokens: ${words(segments)}", wordTokens in 1..4)
    }

    @Test
    fun aNamesPromptIsTakenWithoutBreakingThePass() {
        val audio = clip()
        val prompt = passes.tokenize(" דוד, רותי, אבי")
        val segments = runBlocking { passes.once(audio, WhisperPassOptions.final("he").copy(promptTokens = prompt), 0f) }
        assertTrue("nothing heard with a prompt", words(segments).isNotBlank())
    }

    private fun clip(): FloatArray = readWav(File(folder, "clip.wav")).let { it.copyOf(minOf(it.size, 28 * 16_000)) }

    private fun readWav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") return FloatArray(size / 2) { bytes.getShort(offset + 8 + it * 2) / 32768f }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }
}
