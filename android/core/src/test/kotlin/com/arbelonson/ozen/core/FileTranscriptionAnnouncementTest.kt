package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTranscriptionAnnouncementTest {
    private val model = FileModelChoice.toDownload(UILanguage.Hebrew)
    private val ready = FileTranscription.Ready("a.m4a", UILanguage.Hebrew, model, listOf(model), model, 60.0, false)

    private fun working(stage: FileTranscription.Working.Stage, fraction: Float? = null, lines: List<TranscribedLine> = emptyList()) =
        FileTranscription.Working("a.m4a", UILanguage.Hebrew, model, stage, fraction, null, lines)

    private fun done(history: HistorySave, endMissing: Boolean = false) =
        FileTranscription.Done("a.m4a", UILanguage.Hebrew, model, emptyList(), history, endMissing)

    private val transcribing = FileTranscription.Working.Stage.Transcribing
    private val downloading = FileTranscription.Working.Stage.Downloading
    private val loading = FileTranscription.Working.Stage.Loading

    private fun english(block: () -> Unit) = Localization.withLanguage(UILanguage.English, block)

    @Test
    fun startingSaysWhatThePhoneIsDoingAndEachNewStageSaysSoAgain() = english {
        assertEquals("Turning the recording into text", working(transcribing).announcement(ready))
        assertEquals("Downloading the model", working(downloading).announcement(ready))
        assertEquals("Getting the model ready", working(loading).announcement(working(downloading, 1f)))
        assertEquals("Turning the recording into text", working(transcribing).announcement(working(loading)))
    }

    @Test
    fun progressWithinAStageStaysQuiet() {
        val line = TranscribedLine(0.0, 2.0, "שלום")
        assertNull(working(transcribing, 0.2f, listOf(line)).announcement(working(transcribing, 0.1f)))
        assertNull(working(downloading, 0.5f).announcement(working(downloading, 0.4f)))
    }

    @Test
    fun finishingSaysItIsDoneAndWhereTheTextWentAndACutShortEndingToo() = english {
        assertEquals(
            "Recording turned into text. Saved to History, where you can search it.",
            done(HistorySave.Saved(UUID.randomUUID())).announcement(working(transcribing, 1f)),
        )
        val cut = done(HistorySave.Failed, endMissing = true).announcement(working(transcribing, 1f)) ?: ""
        assertTrue(cut.contains(HistorySave.Failed.message))
        assertTrue(cut.contains("The end of this recording couldn’t be read"))
        assertNull(done(HistorySave.Off).announcement(done(HistorySave.Off)))
    }

    @Test
    fun aFailureSaysWhatWentWrongAlsoWhenTheFileCannotBeOpenedAtAll() = english {
        val failed = FileTranscription.Failed("a.m4a", FileTranscriptionProblem.ModelFailed)
        assertEquals(FileTranscriptionProblem.ModelFailed.message, failed.announcement(working(loading)))
        assertEquals(
            FileTranscriptionProblem.CouldNotRead.message,
            FileTranscription.Failed("a.m4a", FileTranscriptionProblem.CouldNotRead).announcement(FileTranscription.Idle),
        )
        assertNull(failed.announcement(failed))
    }

    @Test
    fun gettingReadyToStartSaysNothing() {
        assertNull(ready.announcement(FileTranscription.Idle))
        assertNull(FileTranscription.Idle.announcement(FileTranscription.Failed("a.m4a", FileTranscriptionProblem.Offline)))
    }
}
