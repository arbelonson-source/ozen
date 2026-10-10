package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionStabilizerTest {
    @Test
    fun `a single final token becomes one committed segment`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        val segment = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "שלום", isFinal = true, timestamp = 0.0))
        assertEquals("שלום", segment.text)
        assertTrue(segment.isCommitted)
        assertEquals(1, stabilizer.segments.size)
    }

    @Test
    fun `repeated partial tokens for the same utterance update in place, not append`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "אני", isFinal = false, timestamp = 0.0))
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "אני רוצה", isFinal = false, timestamp = 0.3))
        val final = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "אני רוצה לשתות", isFinal = true, timestamp = 0.9))

        assertEquals(1, stabilizer.segments.size)
        assertEquals("אני רוצה לשתות", final.text)
        assertTrue(final.isCommitted)
    }

    @Test
    fun `text is never shortened as an utterance updates - nothing gets cut off mid-flight`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        val first = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "מה שלומך", isFinal = false, timestamp = 0.0))
        val second = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "מה שלומך היום", isFinal = false, timestamp = 0.4))

        assertTrue(second.text.length >= first.text.length)
        assertFalse(first.isCommitted)
        assertFalse(second.isCommitted)
    }

    @Test
    fun `commit(id) finishes a pending segment without touching its text, and does nothing twice`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "תודה", isFinal = false, timestamp = 0.0))

        val committed = stabilizer.commit(id)
        assertEquals("תודה", committed?.text)
        assertEquals(true, committed?.isCommitted)
        assertEquals(true, stabilizer.segments.firstOrNull()?.isCommitted)

        assertNull(stabilizer.commit(id))
        assertNull(stabilizer.commit(UUID.randomUUID()))
    }

    @Test
    fun `a pending segment commits on its own after a long enough silence`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 1.0)
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "רגע...", isFinal = false, timestamp = 0.0))

        val notYet = stabilizer.commitStale(now = 0.5)
        assertTrue(notYet.isEmpty())
        assertFalse(stabilizer.segments[0].isCommitted)

        val nowCommitted = stabilizer.commitStale(now = 1.2)
        assertEquals(1, nowCommitted.size)
        assertTrue(stabilizer.segments[0].isCommitted)
    }

    @Test
    fun `after the engine's final, a straggler for the same line changes neither its words nor its state, only who said it`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "בסדר", isFinal = true, timestamp = 0.0, confidence = 0.9f))
        val straggler = stabilizer.ingest(
            TranscriptToken(utteranceID = id, text = "בסדר גמור", isFinal = false, timestamp = 0.1, speakerClusterID = 2, confidence = 0.1f),
        )

        assertTrue(straggler.isCommitted)
        assertEquals("בסדר", straggler.text)
        assertEquals(0.9f, straggler.confidence)
        assertEquals(2, straggler.speakerClusterID)
    }

    @Test
    fun `a line committed only because the engine went quiet reopens when the engine turns out to be slow, then settles on its final`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 6.0)
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "הרופא אמר", isFinal = false, timestamp = 0.0))
        assertEquals(listOf(id), stabilizer.commitStale(now = 7.0).map { it.id })
        assertEquals(true, stabilizer.segments.firstOrNull()?.isCommitted)
        assertEquals(false, stabilizer.segments.firstOrNull()?.isSettled)

        val reopened = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "הרופא אמר כדור", isFinal = false, timestamp = 8.0))
        assertEquals(false, reopened.isCommitted)
        assertEquals("הרופא אמר כדור", reopened.text)

        val settled = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "הרופא אמר כדור אחד", isFinal = true, timestamp = 9.0))
        assertTrue(settled.isCommitted)
        assertTrue(settled.isSettled)
        assertEquals("הרופא אמר כדור אחד", settled.text)

        // Now it's the engine's final: nothing more changes the words.
        val late = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "משהו אחר", isFinal = true, timestamp = 10.0))
        assertEquals("הרופא אמר כדור אחד", late.text)
    }

    @Test
    fun `lines finished because listening stopped stay as they are`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 6.0)
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "עד כאן", isFinal = false, timestamp = 0.0))
        stabilizer.commitStale(now = 7.0)
        stabilizer.commitAll()
        val late = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "עד כאן ועוד", isFinal = false, timestamp = 8.0))
        assertTrue(late.isCommitted)
        assertEquals("עד כאן", late.text)
    }

    @Test
    fun `two different utterances are tracked as two independent segments`() {
        val stabilizer = CaptionStabilizer()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = first, text = "היי", isFinal = true, timestamp = 0.0, speakerClusterID = 0))
        stabilizer.ingest(TranscriptToken(utteranceID = second, text = "מה קורה", isFinal = true, timestamp = 1.0, speakerClusterID = 1))

        assertEquals(2, stabilizer.segments.size)
        assertEquals(0, stabilizer.segments[0].speakerClusterID)
        assertEquals(1, stabilizer.segments[1].speakerClusterID)
    }

    @Test
    fun `commitStale never touches segments that already committed`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 1.0)
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "כבר גמרתי", isFinal = true, timestamp = 0.0))

        val result = stabilizer.commitStale(now = 100.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an empty update never erases text already shown, an empty final still commits it`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "שלום", isFinal = false, timestamp = 1.0))
        val afterEmpty = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "", isFinal = false, timestamp = 2.0))
        assertEquals("שלום", afterEmpty.text)
        assertEquals(2.0, afterEmpty.lastUpdateTimestamp)

        val final = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "  ", isFinal = true, timestamp = 3.0))
        assertEquals("שלום", final.text)
        assertTrue(final.isCommitted)
    }
}

class CaptionConfidenceTest {
    private fun token(id: UUID, text: String, final: Boolean, confidence: Float?) =
        TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = 1.0, confidence = confidence)

    @Test
    fun `a line keeps the engine's latest confidence, and an update without one doesn't erase it`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        stabilizer.ingest(token(id, "שלו", final = false, confidence = 0.2f))
        stabilizer.ingest(token(id, "שלום", final = false, confidence = null))
        assertEquals(0.2f, stabilizer.segments.firstOrNull()?.confidence)
        stabilizer.ingest(token(id, "שלום לכם", final = true, confidence = 0.9f))
        assertEquals(0.9f, stabilizer.segments.firstOrNull()?.confidence)
    }

    @Test
    fun `only finished lines with a real low score are marked unsure`() {
        val line = "נפגשים מחר בבוקר אצל הרופא"
        for (engine in TranscriptionEngineKind.entries) {
            assertTrue(CaptionConfidence.isUncertain(confidence = 0.3f, isCommitted = true, text = line, engine = engine))
            assertFalse(CaptionConfidence.isUncertain(confidence = 0.3f, isCommitted = false, text = line, engine = engine))
            assertFalse(CaptionConfidence.isUncertain(confidence = 0.97f, isCommitted = true, text = line, engine = engine))
            assertFalse(CaptionConfidence.isUncertain(confidence = 0f, isCommitted = true, text = line, engine = engine))
            assertFalse(CaptionConfidence.isUncertain(confidence = null, isCommitted = true, text = line, engine = engine))
        }
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.4f, isCommitted = true, text = line, engine = TranscriptionEngineKind.AppleSpeech))
    }

    @Test
    fun `Whisper's misheard lines get the mark - they score far above Apple's cutoff`() {
        // Lines ivrit.ai's models got wrong, on the phone (Turbo) and on the
        // home computer (large), with their scores: e^(mean log-probability).
        val misheard = listOf(
            "היי, טוב לי להיות עודכם שוב." to 0.689f,
            "חברת החמישית, כמה היו?" to 0.746f,
            "חברת החמישית, כמה היו?" to 0.736f,
            "ועוד חמישית כמה היו?" to 0.679f,
        )
        for ((text, score) in misheard) {
            assertTrue(CaptionConfidence.isUncertain(confidence = score, isCommitted = true, text = text, engine = TranscriptionEngineKind.WhisperKit))
            assertTrue(CaptionConfidence.isUncertain(confidence = score, isCommitted = true, text = text, engine = TranscriptionEngineKind.HomeServer))
            assertFalse(CaptionConfidence.isUncertain(confidence = score, isCommitted = true, text = text, engine = TranscriptionEngineKind.AppleSpeech))
        }
        // Their median line, nearly always right, stays unmarked.
        val line = "כי למידה מורכבת מביצוע של רוטינות"
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.97f, isCommitted = true, text = line, engine = TranscriptionEngineKind.HomeServer))
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.9f, isCommitted = true, text = line, engine = TranscriptionEngineKind.WhisperKit))
    }

    @Test
    fun `a short answer needs a lower Whisper score to be marked - one doubtful token weighs more among a few`() {
        // Short lines from broadcast speech that the large model got right,
        // and ones it got wrong, with their scores.
        val right = listOf("כן, למה לא?" to 0.737f, "שתיים" to 0.741f, "סבבה, יאללה" to 0.698f, "יאללה! אוקיי" to 0.702f, "אפשר ביס?" to 0.683f)
        val wrong = listOf("יואו!" to 0.464f, "הייו!" to 0.498f, "ריח אין." to 0.547f, "תגיד מה זה?" to 0.583f)
        for (engine in listOf(TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.HomeServer)) {
            for ((text, score) in right) {
                assertFalse(CaptionConfidence.isUncertain(confidence = score, isCommitted = true, text = text, engine = engine))
            }
            for ((text, score) in wrong) {
                assertTrue(CaptionConfidence.isUncertain(confidence = score, isCommitted = true, text = text, engine = engine))
            }
        }
        // Apple's cutoff is the same for any length.
        assertTrue(CaptionConfidence.isUncertain(confidence = 0.39f, isCommitted = true, text = "שתיים", engine = TranscriptionEngineKind.AppleSpeech))
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.45f, isCommitted = true, text = "שתיים", engine = TranscriptionEngineKind.AppleSpeech))
    }

    @Test
    fun `Ozen's noise-trained model is surer of itself, so its lines are marked a little higher up - other models and engines keep theirs`() {
        val a3 = "ozen-turbo-hebrew-a3-8bit"
        val line = "נפגשים מחר בבוקר אצל הרופא"
        val phone = TranscriptionEngineKind.WhisperKit
        assertTrue(CaptionConfidence.isUncertain(confidence = 0.82f, isCommitted = true, text = line, engine = phone, model = a3))
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.84f, isCommitted = true, text = line, engine = phone, model = a3))
        assertTrue(CaptionConfidence.isUncertain(confidence = 0.62f, isCommitted = true, text = "ריח אין.", engine = phone, model = a3))
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.82f, isCommitted = true, text = line, engine = phone, model = "ivrit-large-v3-turbo-8bit"))
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.62f, isCommitted = true, text = "ריח אין.", engine = phone))
        // The right short answers of the test above still go unmarked.
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.683f, isCommitted = true, text = "אפשר ביס?", engine = phone, model = a3))
        // The home computer runs its own models, whichever one the phone has.
        assertFalse(CaptionConfidence.isUncertain(confidence = 0.82f, isCommitted = true, text = line, engine = TranscriptionEngineKind.HomeServer, model = a3))
    }

    @Test
    fun `the phone's score for a line is averaged as the cutoffs were measured - over its words' tokens and the end, not the four that open every line`() {
        // "hey oho" for "ah ho", a broadcast line Turbo got wrong: five
        // tokens, 0.43 on the home computer's scale.
        val tokens = listOf(-1.0f, -1.2f, -0.9f, -1.1f, -0.86f)
        val average = assertNotNull(WhisperSegmentSummary.averageLogprob(wordTokenLogprobs = tokens))
        assertTrue(abs(exp(average) - 0.43f) < 0.005f)
        assertTrue(CaptionConfidence.isUncertain(confidence = exp(average), isCommitted = true, text = "היי אוהו", engine = TranscriptionEngineKind.WhisperKit))
        // WhisperKit's own average also counts the line's start, language,
        // task and no-timestamps tokens and its end at 0: 0.60, unmarked.
        val whisperKits = exp(tokens.fold(0f) { sum, value -> sum + value } / (tokens.size + 5).toFloat())
        assertFalse(CaptionConfidence.isUncertain(confidence = whisperKits, isCommitted = true, text = "היי אוהו", engine = TranscriptionEngineKind.WhisperKit))
        assertNull(WhisperSegmentSummary.averageLogprob(wordTokenLogprobs = emptyList()))
    }

    @Test
    fun `a line the phone's engine had to decode again at a raised temperature is shown as unsure, however sure its retry reads`() {
        val words = "פגשתי מהרופא שתביא לי את הטלפון"
        val plain = WhisperSegmentSummary(text = words, noSpeechProb = 0f, avgLogprob = ln(0.95f), compressionRatio = 1.2f)
        val retried = plain.copy(temperature = 0.2f)
        val sure = assertNotNull(CaptionConfidence.whisperConfidence(listOf(plain)))
        assertTrue(abs(sure - 0.95f) < 0.001f)
        assertFalse(CaptionConfidence.isUncertain(confidence = sure, isCommitted = true, text = words, engine = TranscriptionEngineKind.WhisperKit))
        val doubtful = assertNotNull(CaptionConfidence.whisperConfidence(listOf(plain, retried)))
        assertTrue(CaptionConfidence.isUncertain(confidence = doubtful, isCommitted = true, text = words, engine = TranscriptionEngineKind.WhisperKit))
        assertTrue(CaptionConfidence.isUncertain(confidence = doubtful, isCommitted = true, text = "כן", engine = TranscriptionEngineKind.WhisperKit))
        assertNull(CaptionConfidence.whisperConfidence(emptyList()))
    }
}

class CaptionStabilizerCommitAllTest {
    @Test
    fun `stopping settles lines committed only by a pause, as written, and returns them for the screen`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 6.0)
        val quiet = UUID.randomUUID()
        val reopened = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = quiet, text = "התקשרי ל-050", isFinal = false, timestamp = 0.0))
        stabilizer.ingest(TranscriptToken(utteranceID = reopened, text = "עוד", isFinal = false, timestamp = 0.0))
        stabilizer.commitStale(now = 7.0)
        stabilizer.ingest(TranscriptToken(utteranceID = reopened, text = "עוד משהו", isFinal = false, timestamp = 8.0))

        val finished = stabilizer.commitAll()
        assertEquals(setOf(quiet, reopened), finished.map { it.id }.toSet())
        assertTrue(stabilizer.segments.all { it.isSettled })
        assertEquals("התקשרי ל-050", stabilizer.segments.firstOrNull()?.text)
    }

    @Test
    fun `a final whose words were suppressed settles a line committed only by a pause`() {
        val stabilizer = CaptionStabilizer(silenceCommitThreshold = 6.0)
        val id = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "תודה", isFinal = false, timestamp = 0.0))
        stabilizer.commitStale(now = 7.0)

        assertEquals(true, stabilizer.commit(id)?.isSettled)
        assertNull(stabilizer.commit(id))
        val late = stabilizer.ingest(TranscriptToken(utteranceID = id, text = "תודה רבה", isFinal = false, timestamp = 8.0))
        assertTrue(late.isSettled)
        assertEquals("תודה", late.text)
    }

    @Test
    fun `commitAll finishes open lines only, and returns just those`() {
        val stabilizer = CaptionStabilizer()
        val open = UUID.randomUUID()
        val done = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = done, text = "שלום", isFinal = true, timestamp = 1.0))
        stabilizer.ingest(TranscriptToken(utteranceID = open, text = "מה נש", isFinal = false, timestamp = 2.0))

        val finished = stabilizer.commitAll()
        assertEquals(listOf(open), finished.map { it.id })
        assertTrue(stabilizer.segments.all { it.isCommitted })
        assertTrue(stabilizer.commitAll().isEmpty())
        assertEquals(listOf("שלום", "מה נש" + CaptionStabilizer.CUT_OFF_MARK), stabilizer.segments.map { it.text })
    }

    @Test
    fun `a live line finished by its final pass, or by commit(id), is closed - stopping doesn't mark it cut off`() {
        val stabilizer = CaptionStabilizer()
        val id = UUID.randomUUID()
        val suppressed = UUID.randomUUID()
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "אני רוצה", isFinal = false, timestamp = 1.0))
        stabilizer.ingest(TranscriptToken(utteranceID = id, text = "אני רוצה לשתות", isFinal = true, timestamp = 2.0))
        stabilizer.ingest(TranscriptToken(utteranceID = suppressed, text = "כן", isFinal = false, timestamp = 3.0))
        stabilizer.commit(suppressed)

        assertFalse(stabilizer.hasOpenLine)
        assertTrue(stabilizer.commitAll().isEmpty())
        assertEquals(listOf("אני רוצה לשתות", "כן"), stabilizer.segments.map { it.text })
    }

    @Test
    fun `a line that already trails off in three dots is not marked cut off a second time`() {
        assertEquals("ואז הוא...", CaptionStabilizer.markingCutOff("ואז הוא..."))
        assertEquals("ואז הוא" + CaptionStabilizer.CUT_OFF_MARK, CaptionStabilizer.markingCutOff("ואז הוא" + CaptionStabilizer.CUT_OFF_MARK))
        assertEquals("ואז הוא" + CaptionStabilizer.CUT_OFF_MARK, CaptionStabilizer.markingCutOff("ואז הוא"))

        val stabilizer = CaptionStabilizer()
        stabilizer.ingest(TranscriptToken(utteranceID = UUID.randomUUID(), text = "ואז הוא...", isFinal = false, timestamp = 1.0))
        stabilizer.commitAll()
        assertEquals(listOf("ואז הוא..."), stabilizer.segments.map { it.text })
    }
}
