package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionStateTest {
    private fun line(text: String, committed: Boolean) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = committed, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private fun show(phase: PipelinePhase, segments: List<TranscriptSegment> = emptyList(), interrupted: Boolean = false) =
        CaptionState.show(phase, engine = null, interruptedBySystem = interrupted, scheduledRetry = null, segments = segments)

    @Test
    fun `the pipeline's lines show in order, the committed ones as final`() {
        val done = line("שלום לכולם", committed = true)
        val growing = line("מה שלומך", committed = false)
        show(PipelinePhase.Listening, listOf(done, growing))
        assertEquals(
            listOf(
                CaptionLine(done.id, CaptionLayout.displayText("שלום לכולם"), isFinal = true, copied = CaptionLayout.copiedText("שלום לכולם")),
                CaptionLine(growing.id, CaptionLayout.displayText("מה שלומך"), isFinal = false, copied = CaptionLayout.copiedText("מה שלומך")),
            ),
            CaptionState.screen.value.lines,
        )
    }

    @Test
    fun `a line of only numbers reads the way the spoken language does, not always from the right`() {
        val time = line("10:30", committed = true)
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(time), languageCode = "en")
        assertEquals("10:30", CaptionState.screen.value.lines.single().text)
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(time), languageCode = "he")
        assertEquals(CaptionLayout.displayText("10:30", "he"), CaptionState.screen.value.lines.single().text)
        assertTrue(CaptionState.screen.value.lines.single().text != "10:30")
    }

    @Test
    fun `a starred line shows starred, a star given shows at once, and a line copies as said`() {
        val pills = line("Take two pills after dinner", committed = true)
        val phone = line("תתקשרי למרפאה 050 123 4567", committed = true)
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(pills, phone), starred = { it == pills.id })
        assertEquals(listOf(true, false), CaptionState.screen.value.lines.map { it.starred })
        assertEquals(CaptionLayout.copiedText("תתקשרי למרפאה 050 123 4567"), CaptionState.screen.value.lines[1].copied)

        CaptionState.markStarred(phone.id, true)
        CaptionState.markStarred(pills.id, false)
        assertEquals(listOf(false, true), CaptionState.screen.value.lines.map { it.starred })
    }

    @Test
    fun `a phone number becomes callable only once the engine has settled its line`() {
        val settled = line("Call the clinic on 050 123 4567", committed = true)
        val provisional = TranscriptSegment(
            id = UUID.randomUUID(), text = "Or the office on 03 123 4567", isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0, isProvisionalCommit = true,
        )
        val growing = line("And the pharmacy on 052 765 4321", committed = false)
        show(PipelinePhase.Listening, listOf(settled, provisional, growing))
        assertEquals(listOf(listOf("0501234567"), emptyList(), emptyList()), CaptionState.screen.value.lines.map { line -> line.phones.map { it.dialable } })
    }

    @Test
    fun `a line with an important word is marked, and a word heard stays through new captions until cleared`() {
        val name = line("Grandma, dinner is ready", committed = true)
        val other = line("Pass the salt", committed = true)
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(name, other), keywordHits = setOf(name.id))
        assertEquals(listOf(true, false), CaptionState.screen.value.lines.map { it.important })
        val word = HeardWord(UUID.randomUUID(), "Grandma", 1_800_000_000.0)
        CaptionState.heard(word)
        show(PipelinePhase.Listening, listOf(name, other))
        assertEquals(word, CaptionState.screen.value.heard)
        CaptionState.clear()
        assertNull(CaptionState.screen.value.heard)
    }

    @Test
    fun `the phone speaking stays known through new captions until it stops`() {
        CaptionState.speaking(true)
        show(PipelinePhase.Paused, listOf(line("Hello", committed = true)))
        assertTrue(CaptionState.screen.value.speaking)
        CaptionState.speaking(false)
        assertFalse(CaptionState.screen.value.speaking)
    }

    @Test
    fun `a failed save stays on the screen through new captions until it is cleared`() {
        CaptionState.savingTrouble(true)
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)))
        assertTrue(CaptionState.screen.value.savingTrouble)
        CaptionState.savingTrouble(false)
        assertFalse(CaptionState.screen.value.savingTrouble)
    }

    @Test
    fun `the microphones and the one in use stay through new captions`() {
        val phone = AudioInputDescriptor("builtInMic:", "Phone microphone", AudioPortType.BuiltInMic)
        val lapel = AudioInputDescriptor("usb:1", "Lapel mic", AudioPortType.Usb)
        CaptionState.inputs(listOf(phone, lapel), selected = "usb:1")
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)))
        assertEquals(listOf(phone, lapel), CaptionState.screen.value.inputs)
        assertEquals(lapel, CaptionState.screen.value.selectedInput)
        CaptionState.inputs(listOf(phone), selected = "usb:1")
        assertNull(CaptionState.screen.value.selectedInput)
        CaptionState.clear()
    }

    @Test
    fun `a microphone that dropped stays said through new captions until the pipeline lets it go`() {
        val phone = AudioInputDescriptor("builtInMic:", "Phone microphone", AudioPortType.BuiltInMic)
        val lapel = AudioInputDescriptor("usb:1", "Lapel mic", AudioPortType.Usb)
        CaptionState.inputs(listOf(phone), selected = "builtInMic:", dropped = lapel)
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)))
        assertEquals(lapel, CaptionState.screen.value.dropped)
        CaptionState.inputs(listOf(phone), selected = "builtInMic:")
        assertNull(CaptionState.screen.value.dropped)
        CaptionState.clear()
    }

    @Test
    fun `captions count as running only while listening and not held for a call, as on the iPhone`() {
        assertTrue(CaptionScreenState(phase = PipelinePhase.Listening).captionsRunning)
        assertFalse(CaptionScreenState(phase = PipelinePhase.Listening, interruptedBySystem = true).captionsRunning)
        assertFalse(CaptionScreenState(phase = PipelinePhase.Paused).captionsRunning)
        assertFalse(CaptionScreenState(phase = PipelinePhase.Idle).captionsRunning)
    }

    @Test
    fun `the important words heard lately are the last ten, newest first`() {
        val grandma = UUID.randomUUID()
        val hits = (1..12).map { KeywordHit(segmentID = UUID.randomUUID(), match = KeywordMatch(grandma, "סבתא", "סבתא", 0), timestamp = it.toDouble()) }
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), recentlyHeard = hits)
        assertEquals((12 downTo 3).map { it.toDouble() }, CaptionState.screen.value.recentlyHeard.map { it.timestamp })
    }

    @Test
    fun `only the newest two hundred lines are kept on the screen`() {
        val segments = (1..205).map { line("line $it", committed = true) }
        show(PipelinePhase.Listening, segments)
        val lines = CaptionState.screen.value.lines
        assertEquals(200, lines.size)
        assertEquals(segments[5].id, lines.first().id)
        assertEquals(segments.last().id, lines.last().id)
    }

    @Test
    fun `each line on screen opens the saved conversation of its place in the whole transcript`() {
        val segments = (1..205).map { line("line $it", committed = true) }
        val conversations = segments.indices.map { if (it % 3 == 0) null else UUID.randomUUID() }
        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = segments, conversation = conversations::get)
        assertEquals(conversations.takeLast(200), CaptionState.screen.value.lines.map { it.conversation })
    }

    @Test
    fun `the status button offers what the pipeline's phase allows`() {
        show(PipelinePhase.Listening)
        assertEquals(PhasePresentation.Action.Pause, CaptionState.screen.value.status.action)
        show(PipelinePhase.Paused)
        assertEquals(PhasePresentation.Action.Resume, CaptionState.screen.value.status.action)
        show(PipelinePhase.Idle)
        assertEquals(PhasePresentation.Action.Start, CaptionState.screen.value.status.action)
    }

    @Test
    fun `a call that silences the microphone says so on the status button`() {
        show(PipelinePhase.Listening, interrupted = true)
        assertEquals("phone.fill", CaptionState.screen.value.status.systemImage)
    }

    @Test
    fun `lines are laid out as on the iPhone, a mixed line read from the right and a long one in paragraphs`() {
        val mixed = line("OK, \u05D0\u05D6 \u05E0\u05EA\u05D7\u05D9\u05DC", committed = true)
        val english = line("Good morning", committed = true)
        val sentence = "\u05E9\u05DC\u05D5\u05DD \u05DC\u05DB\u05D5\u05DC\u05DD \u05D5\u05D1\u05E8\u05D5\u05DB\u05D9\u05DD \u05D4\u05D1\u05D0\u05D9\u05DD."
        val long = line(List(5) { sentence }.joinToString(" "), committed = true)
        show(PipelinePhase.Listening, listOf(mixed, english, long))
        val shown = CaptionState.screen.value.lines.map { it.text }
        assertTrue(shown[0].startsWith("\u200F"), shown[0])
        assertEquals("Good morning", shown[1])
        assertTrue(shown[2].contains("\n"), shown[2])
    }

    private fun showAt(phase: PipelinePhase, lastLineAt: Double, listeningSince: Double) = CaptionState.show(
        phase,
        engine = null,
        interruptedBySystem = false,
        scheduledRetry = null,
        segments = listOf(TranscriptSegment(id = UUID.randomUUID(), text = "a line", isCommitted = true, startTimestamp = lastLineAt, lastUpdateTimestamp = lastLineAt)),
        listeningStartedAt = listeningSince,
    )

    @Test
    fun `the screen stays on while listening, until fifteen minutes pass with nothing said`() {
        showAt(PipelinePhase.Listening, lastLineAt = 1_000.0, listeningSince = 400.0)
        val screen = CaptionState.screen.value
        assertTrue(screen.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0 + 14 * 60))
        assertFalse(screen.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0 + 15 * 60))
        assertFalse(screen.keepsScreenOn(keepAwakeWhileListening = false, now = 1_000.0))
    }

    @Test
    fun `listening that began after the last line counts from when it began`() {
        showAt(PipelinePhase.Listening, lastLineAt = 1_000.0, listeningSince = 5_000.0)
        assertTrue(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = true, now = 5_000.0 + 14 * 60))
    }

    @Test
    fun `paused captions let the screen turn off, and getting ready keeps it on`() {
        showAt(PipelinePhase.Paused, lastLineAt = 1_000.0, listeningSince = 400.0)
        assertFalse(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0))
        showAt(PipelinePhase.StartingAudio, lastLineAt = 1_000.0, listeningSince = 400.0)
        assertTrue(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = false, now = 1_000.0 + 3_600))
    }

    @Test
    fun `the buttons slide away once captions have run five seconds untouched`() {
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)))
        val screen = CaptionState.screen.value
        assertFalse(screen.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 103.0))
        assertTrue(screen.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 106.0))
    }

    @Test
    fun `the buttons stay when switched off, scrolled up, with a screen reader, paused, empty or cut off by a call`() {
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)))
        val screen = CaptionState.screen.value
        assertFalse(screen.hidesButtons(autoHide = false, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 160.0))
        assertFalse(screen.hidesButtons(autoHide = true, followingLatest = false, screenReaderOn = false, lastTouchAt = 100.0, now = 160.0))
        assertFalse(screen.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = true, lastTouchAt = 100.0, now = 160.0))
        show(PipelinePhase.Paused, listOf(line("Hello", committed = true)))
        assertFalse(CaptionState.screen.value.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 160.0))
        show(PipelinePhase.Listening)
        assertFalse(CaptionState.screen.value.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 160.0))
        show(PipelinePhase.Listening, listOf(line("Hello", committed = true)), interrupted = true)
        assertFalse(CaptionState.screen.value.hidesButtons(autoHide = true, followingLatest = true, screenReaderOn = false, lastTouchAt = 100.0, now = 160.0))
    }

    @Test
    fun `a finished line the engine doubted is marked with the words it doubted, a sure or growing one is not`() {
        val doubted = line("we will meet at the clinic at ten", committed = true).copy(confidence = 0.5f, uncertainWords = listOf("ten"))
        val sure = line("see you there then", committed = true).copy(confidence = 0.95f)
        val growing = line("and bring the letter from", committed = false).copy(confidence = 0.3f, uncertainWords = listOf("letter"))
        show(PipelinePhase.Listening, listOf(doubted, sure, growing))
        val lines = CaptionState.screen.value.lines
        assertEquals(listOf(true, false, false), lines.map { it.unsure })
        assertEquals(listOf(listOf("ten"), emptyList(), emptyList()), lines.map { it.doubtfulWords })
    }

    @Test
    fun `a line without its own scorer is judged by the model transcribing now`() {
        val segment = line("we will meet at the clinic at ten", committed = true).copy(confidence = 0.82f)
        fun unsureWith(model: String): Boolean {
            CaptionState.show(
                PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(segment),
                scoringEngine = TranscriptionEngineKind.WhisperKit, scoringModel = model,
            )
            return CaptionState.screen.value.lines.single().unsure
        }
        assertTrue(unsureWith("ozen-turbo-hebrew-a3-8bit"))
        assertFalse(unsureWith("ivrit-large-v3-turbo-8bit"))
    }

    @Test
    fun `the way back to the newest line says how many came since`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("To the last line", jumpToLatestTitle(0))
        assertEquals("1 new line", jumpToLatestTitle(1))
        assertEquals("4 new lines", jumpToLatestTitle(4))
    }

    @Test
    fun `new lines are counted from the newest one on screen when she scrolled back, even past the two hundred kept`() {
        val segments = (1..200).map { line("line $it", committed = true) }
        show(PipelinePhase.Listening, segments)
        val newestThen = CaptionState.screen.value.lines.last().id
        assertEquals(0, linesSince(CaptionState.screen.value.lines, newestThen))
        show(PipelinePhase.Listening, segments + (201..203).map { line("line $it", committed = true) })
        assertEquals(3, linesSince(CaptionState.screen.value.lines, newestThen))
        assertEquals(200, linesSince(CaptionState.screen.value.lines, UUID.randomUUID()))
    }

    @Test
    fun `clearing starts the screen over, captions off and no lines`() {
        show(PipelinePhase.Listening, listOf(line("שלום", committed = true)))
        CaptionState.clear()
        assertEquals(PipelinePhase.Idle, CaptionState.screen.value.phase)
        assertEquals(emptyList(), CaptionState.screen.value.lines)
    }
}
