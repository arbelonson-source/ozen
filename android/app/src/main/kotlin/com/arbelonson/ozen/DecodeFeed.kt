package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscriptionProblem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

internal object DecodeFeed {
    /** Runs a decode into [pieces] and closes it. Returns whether the end of the recording is missing: the decoder stalled, or it failed after pieces were delivered, so the text so far is kept. */
    fun run(pieces: Channel<FloatArray>, decode: ((FloatArray) -> Boolean) -> DecodeOutcome): Boolean {
        var delivered = 0
        val outcome = decode { piece -> pieces.trySendBlocking(piece).isSuccess.also { if (it) delivered++ } }
        when (outcome) {
            DecodeOutcome.Unreadable -> {
                if (delivered == 0) pieces.close(FileTranscriber.Problem(FileTranscriptionProblem.CouldNotRead))
                else { pieces.close(); return true }
            }
            DecodeOutcome.NoAudio -> pieces.close(FileTranscriber.Problem(FileTranscriptionProblem.NoAudio))
            DecodeOutcome.Stalled -> { pieces.close(); return true }
            else -> pieces.close()
        }
        return false
    }
}
