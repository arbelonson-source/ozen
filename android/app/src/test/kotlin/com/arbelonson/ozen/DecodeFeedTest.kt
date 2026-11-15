package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscriptionProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

class DecodeFeedTest {
    private fun feed(outcome: DecodeOutcome, pieces: Int): Pair<Boolean, Channel<FloatArray>> {
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        val missing = DecodeFeed.run(channel) { onPiece ->
            repeat(pieces) { onPiece(FloatArray(16_000)) }
            outcome
        }
        return missing to channel
    }

    private fun drain(channel: Channel<FloatArray>): Pair<Int, Throwable?> = runBlocking {
        var count = 0
        try {
            for (piece in channel) count++
            count to null
        } catch (failure: Throwable) {
            count to failure
        }
    }

    @Test
    fun aReadFailurePartwayKeepsThePiecesAndMarksTheEndMissing() {
        val (missing, channel) = feed(DecodeOutcome.Unreadable, pieces = 3)
        val (count, failure) = drain(channel)
        assertTrue(missing)
        assertEquals(3, count)
        assertEquals(null, failure)
    }

    @Test
    fun aFileThatFailsBeforeAnyPieceStillCouldNotBeRead() {
        val (missing, channel) = feed(DecodeOutcome.Unreadable, pieces = 0)
        val (_, failure) = drain(channel)
        assertFalse(missing)
        assertIs<FileTranscriber.Problem>(failure)
        assertEquals(FileTranscriptionProblem.CouldNotRead, failure.kind)
    }

    @Test
    fun aStalledDecoderStillMarksTheEndMissing() {
        val (missing, channel) = feed(DecodeOutcome.Stalled, pieces = 2)
        assertTrue(missing)
        assertEquals(null, drain(channel).second)
    }

    @Test
    fun aFinishedFileIsNotMarked() {
        val (missing, channel) = feed(DecodeOutcome.Finished, pieces = 2)
        assertFalse(missing)
        assertEquals(2 to null, drain(channel))
    }
}
