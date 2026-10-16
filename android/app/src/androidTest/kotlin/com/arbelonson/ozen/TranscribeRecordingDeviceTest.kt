package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.TranscriptFormats
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribeRecordingDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as OzenApplication
    private val transcriber get() = app.transcriber
    private val folder = File(context.cacheDir, "recording-test").apply { mkdirs() }
    private val sharedFolder = File(context.cacheDir, CaptionSession.SHARED_FOLDER).apply { mkdirs() }
    private val saved = mutableListOf<UUID>()
    private val savingBefore = app.settings.current.value.saveHistory

    @Before
    fun hebrewModelIsThere() {
        assumeTrue("put ${PhoneModelCatalog.OZEN_HEBREW_FILE} in ${context.filesDir}", File(context.filesDir, PhoneModelCatalog.OZEN_HEBREW_FILE).isFile)
        app.settings.change { it.saveHistory = true; it.languageCode = "he" }
    }

    @After
    fun tidy() {
        transcriber.cancel()
        waitUntilIdle()
        transcriber.close()
        for (conversation in app.captions.savedConversations.list()) {
            if (conversation.id in saved || conversation.title in sharedNames) app.captions.savedConversations.delete(conversation.id)
        }
        app.settings.change { it.saveHistory = savingBefore }
        folder.deleteRecursively()
    }

    private val sharedNames = mutableSetOf<String>()

    private fun lecture(seconds: Int, name: String): Uri {
        val clip = File(context.filesDir, "device-test/clip.wav")
        assumeTrue("put clip.wav in ${clip.parent}", clip.isFile)
        val whole = DeviceClips.readWav(clip)
        val samples = FloatArray(seconds * 16_000) { whole[it % whole.size] }
        return shareable(name) { DeviceClips.writeWav(it, samples) }
    }

    private fun shareable(name: String, write: (File) -> Unit): Uri {
        val file = File(sharedFolder, name)
        write(file)
        sharedNames += name.substringBeforeLast('.')
        return FileProvider.getUriForFile(context, context.packageName + ".share", file)
    }

    private fun share(uri: Uri, type: String) = ActivityScenario.launch<MainActivity>(
        Intent(Intent.ACTION_SEND).setClassName(context, MainActivity::class.java.name).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun waitUntilIdle() {
        val until = SystemClock.elapsedRealtime() + 60_000
        while (transcriber.state.value is FileTranscription.Working && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
    }

    private fun waitFor(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting for $what; state ${transcriber.state.value}" }
            Thread.sleep(200)
        }
    }

    @Test
    fun aVoiceNoteSharedToOzenIsTranscribedSavedToHistoryAndExportedAsSubtitles() {
        val uri = lecture(20, "Shared lecture note.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("transcribe-name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcribe-privacy").assertExists()
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            waitFor(600_000, "the transcript") { transcriber.state.value is FileTranscription.Done }
            val done = transcriber.state.value as FileTranscription.Done
            assertEquals(PhoneModelCatalog.OZEN_HEBREW_FILE, done.model.file)
            assertTrue("no lines came out", done.lines.isNotEmpty())
            Log.i("OzenTranscribe", "LINES: " + done.lines.joinToString(" | ") { TranscriptFormats.clock(it.startSeconds) + " " + it.text })
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(TRANSCRIBE_LINE).fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(done.recordId)
            val conversation = app.captions.savedConversations.list().first { it.id == done.recordId }
            assertEquals("Shared lecture note", conversation.title)
            val record = app.captions.savedConversations.load(done.recordId!!)!!
            assertEquals(done.lines.size, record.segments.size)
            assertEquals(done.lines.map { it.text }, record.segments.map { it.text })
            saved += done.recordId!!
            val cues = TranscriptFormats.srt(done.lines).trim().split("\n\n")
            assertEquals(done.lines.size, cues.size)
            assertTrue(cues.all { it.lines().size >= 3 && "-->" in it.lines()[1] })
            assertTrue(app.captions.savedConversations.search(done.lines.first().text.split(" ").first { it.length > 2 }).any { it.id == done.recordId })
        }
    }

    @Test
    fun cancellingStopsTheWorkQuicklyAndLeavesNothingInHistory() {
        val uri = lecture(240, "Cancel me.wav")
        val before = app.captions.savedConversations.list().size
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            waitFor(300_000, "work to begin") { (transcriber.state.value as? FileTranscription.Working)?.stage == FileTranscription.Working.Stage.Transcribing }
            Thread.sleep(3_000)
            compose.onNodeWithTag(TRANSCRIBE_CANCEL).performClick()
            val asked = SystemClock.elapsedRealtime()
            waitFor(15_000, "the stop") { transcriber.state.value is FileTranscription.Ready }
            Log.i("OzenTranscribe", "cancel took ${SystemClock.elapsedRealtime() - asked} ms")
            assertEquals(before, app.captions.savedConversations.list().size)
        }
    }

    @Test
    fun aFileThatIsNotAudioGetsAPlainMessageInsteadOfACrash() {
        val uri = shareable("Notes.m4a") { it.writeText("this is not a recording") }
        share(uri, "audio/mp4").use {
            waitFor(30_000, "the message") { transcriber.state.value is FileTranscription.Failed }
            assertEquals(FileTranscriptionProblem.CouldNotRead, (transcriber.state.value as FileTranscription.Failed).problem)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("transcribe-problem").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun anOggOpusVoiceNoteLikeWhatsAppSendsIsDecodedToSixteenKilohertzMono() {
        val bytes = instrumentation.context.assets.open("voice-note.ogg").use { it.readBytes() }
        val uri = shareable("PTT-20261016-WA0001.opus") { it.writeBytes(bytes) }
        val pieces = mutableListOf<FloatArray>()
        val outcome = AudioFileDecoder.decode(context, uri) { pieces += it; true }
        assertEquals(DecodeOutcome.Finished, outcome)
        val seconds = pieces.sumOf { it.size } / 16_000.0
        assertTrue("$seconds s", seconds in 1.6..2.0)
        assertTrue(pieces.any { piece -> piece.any { kotlin.math.abs(it) > 0.01f } })
    }

    @Test
    fun aLongRecordingIsReadAboutASecondAtATimeNeverWhole() {
        val minutes = 8
        val uri = shareable("Long lecture.wav") { DeviceClips.writeWav(it, FloatArray(minutes * 60 * 16_000) { index -> 0.1f * kotlin.math.sin(index * 0.05f) }) }
        val sizes = mutableListOf<Int>()
        val outcome = AudioFileDecoder.decode(context, uri) { sizes += it.size; true }
        assertEquals(DecodeOutcome.Finished, outcome)
        assertTrue("${sizes.size} pieces", sizes.size >= minutes * 60 - 5)
        assertTrue("largest piece ${sizes.max()}", sizes.max() <= 3 * 16_000)
        assertTrue("${sizes.sum()} samples", kotlin.math.abs(sizes.sum() - minutes * 60 * 16_000) < 2_000)
    }

    @Test
    fun historyOffersToTranscribeARecording() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-transcribe").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Transcribe a recording").assertExists()
        }
    }

    @Test
    fun aVoiceNoteWithNoModelOnThePhoneOffersTheNormalDownload() {
        val aside = ModelFilesAside(context.filesDir)
        aside.hold()
        try {
            val uri = lecture(5, "Needs a model.wav")
            share(uri, "audio/wav").use {
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("transcribe-needs-model").fetchSemanticsNodes().isNotEmpty() }
                val ready = transcriber.state.value as FileTranscription.Ready
                assertEquals(PhoneModelCatalog.OZEN_HEBREW_FILE, ready.toDownload.file)
                assertEquals(null, ready.model)
            }
        } finally {
            aside.putBack()
        }
    }

    @Test
    fun aMinuteOfHebrewSpeechTimedWithTheOzenModel() {
        val uri = lecture(60, "One minute.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            val started = SystemClock.elapsedRealtime()
            waitFor(900_000, "the transcript") { transcriber.state.value is FileTranscription.Done }
            val done = transcriber.state.value as FileTranscription.Done
            saved += done.recordId!!
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i("OzenTranscribe", "TIMING: 60 s of Hebrew in %.1f s with %s, %d lines".format(seconds, done.model.file, done.lines.size))
            Log.i("OzenTranscribe", "LINES: " + done.lines.joinToString(" | ") { TranscriptFormats.clock(it.startSeconds) + " " + it.text })
            val reference = File(context.filesDir, "device-test/clip.txt")
            if (reference.isFile) {
                val heard = done.lines.filter { it.startSeconds < 52 }.joinToString(" ") { it.text }
                Log.i("OzenTranscribe", "WER over the first 52 s: %.1f%%".format(DeviceClips.wordErrorRate(reference.readText(), heard) * 100))
            }
        }
    }
}
