package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscriptionProblem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

/** How the pieces from a decode ended. */
internal enum class DecodeEnd { Whole, Stalled, FailedPartway }

internal object DecodeFeed {
    /** Runs a decode into [pieces] and closes it. A read failure after pieces were delivered ends it as FailedPartway so the text so far is kept. */
    fun run(pieces: Channel<FloatArray>, decode: ((FloatArray) -> Boolean) -> DecodeOutcome): DecodeEnd {
        var delivered = 0
        val outcome = decode { piece -> pieces.trySendBlocking(piece).isSuccess.also { if (it) delivered++ } }
        when (outcome) {
            DecodeOutcome.Unreadable -> {
                if (delivered == 0) pieces.close(FileTranscriber.Problem(FileTranscriptionProblem.CouldNotRead))
                else { pieces.close(); return DecodeEnd.FailedPartway }
            }
            DecodeOutcome.NoAudio -> pieces.close(FileTranscriber.Problem(FileTranscriptionProblem.NoAudio))
            DecodeOutcome.Stalled -> { pieces.close(); return DecodeEnd.Stalled }
            else -> pieces.close()
        }
        return DecodeEnd.Whole
    }

    /** What to tell when the decode ended with no text at all: a read failure is not silence. */
    fun problemWithoutText(end: DecodeEnd): FileTranscriptionProblem =
        if (end == DecodeEnd.FailedPartway) FileTranscriptionProblem.CouldNotRead else FileTranscriptionProblem.NoSpeech
}
