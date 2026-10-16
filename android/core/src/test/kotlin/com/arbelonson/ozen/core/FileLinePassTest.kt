package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

private class RecordingPasses(private val reply: (Int) -> List<WhisperSegment>) : WhisperPasses {
    override val specialTokenBegin = 50_000
    val optionsSeen = ArrayList<WhisperPassOptions>()
    val audioSeen = ArrayList<FloatArray>()
    var aborts = 0
    var inPass: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        optionsSeen.add(options)
        audioSeen.add(audio)
        inPass?.complete(Unit)
        release?.await()
        return reply(optionsSeen.size - 1)
    }

    override fun abortRunningPass() {
        aborts += 1
        release?.complete(Unit)
    }
}

private fun said(text: String) = listOf(WhisperSegment(text, 0f, listOf(WhisperToken(100, " $text", 0.9f))))

class FileLinePassTest {
    private val quiet = FloatArray(16_000) { if (it % 2 == 0) 0.1f else -0.1f }

    @Test
    fun aStretchGetsTheCarefulFinalPassWithItsRetries() = runBlocking {
        val passes = RecordingPasses { said("hello there") }
        val text = FileLinePass(passes, "en", emptyList(), Dispatchers.Default).text(quiet, voicedChunks = 4)
        assertEquals("hello there", text)
        val options = passes.optionsSeen.single()
        assertTrue(options.isFinal)
        assertEquals(2, options.temperatureFallbackCount)
        assertEquals("en", options.language)
    }

    @Test
    fun theNamesAndImportantWordsGoInAsThePrompt() = runBlocking {
        val passes = RecordingPasses { said("Dana is here") }
        val pass = FileLinePass(passes, "en", listOf("Dana", "Omer"), Dispatchers.Default)
        pass.text(quiet, voicedChunks = 4)
        val prompt = passes.optionsSeen.single().promptTokens
        assertTrue(prompt != null && prompt.isNotEmpty())
        assertTrue(pass.promptTokenCount > 0)
    }

    @Test
    fun noWordsMeansNoPrompt() = runBlocking {
        val passes = RecordingPasses { said("x y") }
        val pass = FileLinePass(passes, "en", emptyList(), Dispatchers.Default)
        pass.text(quiet, voicedChunks = 4)
        assertEquals(null, passes.optionsSeen.single().promptTokens)
        assertEquals(0, pass.promptTokenCount)
    }

    @Test
    fun aMadeUpThanksForWatchingLineDoesNotAppear() = runBlocking {
        val passes = RecordingPasses { said("תודה שצפיתם") }
        assertEquals("", FileLinePass(passes, "he", emptyList(), Dispatchers.Default).text(quiet, voicedChunks = 4))
    }

    @Test
    fun aQuietVoiceIsBroughtUpToACommonLevelLikeLiveCaptions() = runBlocking {
        val passes = RecordingPasses { said("hello there") }
        FileLinePass(passes, "en", emptyList(), Dispatchers.Default).text(FloatArray(16_000) { if (it % 2 == 0) 0.01f else -0.01f }, voicedChunks = 4)
        assertTrue(passes.audioSeen.single().maxOf { kotlin.math.abs(it) } > 0.4f)
    }

    @Test
    fun cancellingStopsTheRunningPassAtOnceAndWaitsForIt() = runBlocking {
        val passes = RecordingPasses { said("never") }
        passes.inPass = CompletableDeferred()
        passes.release = CompletableDeferred()
        val job = async { FileLinePass(passes, "en", emptyList(), Dispatchers.Default).text(quiet, voicedChunks = 4) }
        passes.inPass!!.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, passes.aborts)
    }

    @Test
    fun aPassThatFailsOnceIsTriedOnceMore() = runBlocking {
        var calls = 0
        val passes = RecordingPasses { if (calls++ == 0) error("busy") else said("second try") }
        assertEquals("second try", FileLinePass(passes, "en", emptyList(), Dispatchers.Default, retryDelayMillis = 1).text(quiet, voicedChunks = 4))
    }

    @Test
    fun theLongestStretchShrinksWhenTheNamesTakeRoom() {
        val none = FileLinePass(RecordingPasses { said("x") }, "en", emptyList(), Dispatchers.Default)
        val many = FileLinePass(RecordingPasses { said("x") }, "en", List(30) { "Name number $it" }, Dispatchers.Default)
        assertEquals(WhisperKitDecodeRoom.longestLineSeconds(0, 28.0), none.longestStretchSeconds)
        assertTrue(many.longestStretchSeconds < 28.0)
    }
}
