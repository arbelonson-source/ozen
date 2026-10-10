package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhisperResultFilterTest {
    private val filter = WhisperResultFilter()

    private fun segment(
        text: String,
        noSpeech: Float = 0.1f,
        logprob: Float = -0.3f,
        compression: Float = 1.2f,
    ) = WhisperSegmentSummary(text = text, noSpeechProb = noSpeech, avgLogprob = logprob, compressionRatio = compression)

    @Test
    fun `a confident, ordinary Hebrew sentence passes`() {
        assertTrue(filter.accepts(segment("מה שלומך היום")))
        assertEquals("מה שלומך היום", filter.acceptedText(listOf(segment("מה שלומך היום"))))
    }

    @Test
    fun `known silence hallucinations are dropped regardless of punctuation or brackets`() {
        assertFalse(filter.accepts(segment("תודה שצפיתם.")))
        assertFalse(filter.accepts(segment("[תודה על הצפייה]")))
        assertFalse(filter.accepts(segment("  תודה   שצפיתם!  ")))
        assertFalse(filter.accepts(segment("Thank you for watching")))
        assertFalse(filter.accepts(segment("Subtitles by the Amara.org community")))
        assertFalse(filter.accepts(segment("[מוזיקה]")))
    }

    @Test
    fun `a known hallucination is still caught with a bidi mark glued onto it`() {
        assertFalse(filter.accepts(segment("‏תודה שצפיתם")))
        assertEquals("תודה שצפיתם", WhisperResultFilter.normalize("‏תודה‏ שצפיתם‎"))
    }

    @Test
    fun `'toda' (thanks) inside a real sentence is not a hallucination`() {
        assertTrue(filter.accepts(segment("תודה רבה על העזרה עם הקניות")))
    }

    @Test
    fun `YouTube-inherited outro lines and the model's own uncertainty tag are dropped`() {
        assertFalse(filter.accepts(segment("(speaking in a foreign language)")))
        assertFalse(filter.accepts(segment("[Speaking in a foreign language]")))
        assertFalse(filter.accepts(segment("Please subscribe")))
        assertFalse(filter.accepts(segment("Don't forget to subscribe")))
        assertFalse(filter.accepts(segment("Like and subscribe")))
    }

    @Test
    fun `a clearly heard farewell is kept, one the model barely heard is dropped as invented`() {
        assertTrue(filter.accepts(segment("see you next time", noSpeech = 0.1f)))
        assertFalse(filter.accepts(segment("see you next time", noSpeech = 0.5f, logprob = -0.95f)))
    }

    @Test
    fun `high no-speech probability only rejects when the model was also unsure of its tokens`() {
        assertFalse(filter.accepts(segment("משהו", noSpeech = 0.9f, logprob = -1.5f)))
        assertTrue(filter.accepts(segment("משהו", noSpeech = 0.9f, logprob = -0.2f)))
        assertTrue(filter.accepts(segment("משהו", noSpeech = 0.3f, logprob = -1.5f)))
    }

    @Test
    fun `a repetitive decoding loop is caught by the compression ratio`() {
        assertFalse(filter.accepts(segment("תודה תודה תודה תודה תודה תודה", compression = 3.1f)))
    }

    @Test
    fun `empty and whitespace-only segments never make it through`() {
        assertFalse(filter.accepts(segment("")))
        assertFalse(filter.accepts(segment("   \n")))
        assertEquals("", filter.acceptedText(listOf(segment("  "))))
    }

    @Test
    fun `a segment that is only punctuation or symbols carries no real word`() {
        assertFalse(filter.accepts(segment("...")))
        assertFalse(filter.accepts(segment("-")))
        assertFalse(filter.accepts(segment("—")))
        assertFalse(filter.accepts(segment("♪♪")))
        assertTrue(filter.accepts(segment("3.")))
    }

    @Test
    fun `accepted text joins surviving segments and skips the junk between them`() {
        val text = filter.acceptedText(
            listOf(
                segment("בוקר טוב"),
                segment("תודה רבה", noSpeech = 0.5f, logprob = -0.4f),
                segment("איך ישנת", noSpeech = 0.2f),
            ),
        )
        assertEquals("בוקר טוב איך ישנת", text)
    }

    @Test
    fun `accepted(from) returns exactly the segments acceptedText was built from`() {
        val kept = segment("בוקר טוב")
        val rejected = segment("תודה שצפיתם")
        val alsoKept = segment("איך ישנת")
        assertEquals(listOf(kept, alsoKept), filter.accepted(listOf(kept, rejected, alsoKept)))
    }

    @Test
    fun `a punctuation-only segment between two real ones is dropped, not joined as a word`() {
        val text = filter.acceptedText(listOf(segment("בוקר טוב"), segment("..."), segment("איך ישנת")))
        assertEquals("בוקר טוב איך ישנת", text)
    }

    @Test
    fun `Whisper control tokens never reach the screen`() {
        assertEquals(
            "שלום",
            WhisperResultFilter.stripSpecialTokens("<|startoftranscript|><|he|><|transcribe|><|0.00|>שלום<|2.40|>"),
        )
        assertEquals("no tokens here", WhisperResultFilter.stripSpecialTokens("no tokens here"))
        assertEquals("<|unterminated", WhisperResultFilter.stripSpecialTokens("<|unterminated"))
        assertEquals("מה נשמע", filter.acceptedText(listOf(segment("<|0.00|> מה נשמע <|1.20|>"))))
        assertFalse(filter.accepts(segment("<|nospeech|><|endoftext|>")))
    }

    @Test
    fun `normalization strips punctuation, symbols and case`() {
        assertEquals("thank you", WhisperResultFilter.normalize("  Thank You!!  "))
        assertEquals("תודה", WhisperResultFilter.normalize("♪ תודה ♪"))
    }

    @Test
    fun `normalization strips Hebrew niqqud, matching HebrewText`() {
        assertEquals("תודה רבה", WhisperResultFilter.normalize("תּוֹדָה רַבָּה!"))
    }

    @Test
    fun `a known silence hallucination is caught even when the engine emitted it with niqqud`() {
        assertTrue(WhisperResultFilter().isKnownHallucination("תּוֹדָה שֶׁצְּפִיתֶם"))
    }

    @Test
    fun `invented credit lines with a name attached are dropped`() {
        val filter = WhisperResultFilter()
        assertTrue(filter.isKnownHallucination("כתוביות על ידי ישראל ישראלי"))
        assertTrue(filter.isKnownHallucination("כתוביות: אבי כהן"))
        assertTrue(filter.isKnownHallucination("תורגם על ידי: קהילת עמרה"))
        assertTrue(filter.isKnownHallucination("Subtitles by Jane Doe."))
        assertTrue(filter.isKnownHallucination("[תרגום: מיכל]"))
    }

    @Test
    fun `a credit label is still one behind a direction mark or with vowel points`() {
        val filter = WhisperResultFilter()
        assertTrue(filter.isKnownHallucination("‏תרגום: ישראל ישראלי"))
        assertTrue(filter.isKnownHallucination("כתוּביות: ישראל ישראלי"))
        assertTrue(filter.isKnownHallucination("‏כתוביות - ישראל ישראלי"))
        assertFalse(filter.isKnownHallucination("‏עריכה - זה היה ממש נחמד היום"))
    }

    @Test
    fun `credit lines in the abbreviated written form, and translated-and-synced credits, are dropped`() {
        val filter = WhisperResultFilter()
        assertTrue(filter.isKnownHallucination("כתוביות ע״י ישראל ישראלי"))
        assertTrue(filter.isKnownHallucination("תורגם ע\"י: דנה"))
        assertTrue(filter.isKnownHallucination("תורגם וסונכרן ע\"י אבי"))
        assertTrue(filter.isKnownHallucination("סונכרן על ידי: הצוות"))
        assertTrue(filter.isKnownHallucination("סנכרון: מיכל"))
        assertFalse(filter.isKnownHallucination("סנכרון של הטלפון לקח המון זמן"))
    }

    @Test
    fun `a bare credit label followed by a dash, not just a colon, is dropped when it's short`() {
        val filter = WhisperResultFilter()
        assertTrue(filter.isKnownHallucination("כתוביות - ישראל ישראלי"))
        assertTrue(filter.isKnownHallucination("Translation - John Doe"))
        assertTrue(filter.isKnownHallucination("עריכה — דנה"))
    }

    @Test
    fun `a real sentence that happens to pause on a dash after a credit word stays past the tighter dash cap`() {
        assertFalse(WhisperResultFilter().isKnownHallucination("עריכה - זה היה ממש נחמד היום"))
    }

    @Test
    fun `real speech that merely starts with a credit word passes`() {
        val filter = WhisperResultFilter()
        assertFalse(filter.isKnownHallucination("תרגום של הספר הזה לקח לה שלוש שנים שלמות בערך"))
        assertFalse(filter.isKnownHallucination("אני צריכה כתוביות בטלוויזיה"))
        assertFalse(filter.isKnownHallucination("תרגומים חדשים"))
        assertFalse(filter.isKnownHallucination("תרגומים: חדשים"))
        assertFalse(filter.isKnownHallucination("כתוביות בבקשה"))
        assertFalse(filter.isKnownHallucination("תרגום לאנגלית בבקשה"))
        assertFalse(filter.isKnownHallucination("הפקה של הצגה בבית הספר"))
    }

    @Test
    fun `a clearly heard 'toda raba' (thank you very much) is real conversation and is kept`() {
        assertTrue(filter.accepts(segment("תודה רבה.", noSpeech = 0.05f, logprob = -0.35f)))
        assertTrue(filter.accepts(segment("תודה!", noSpeech = 0.1f, logprob = -0.5f)))
    }

    @Test
    fun `'Shira' called across the room, or 'music' and 'laughter' said aloud, are kept when heard clearly, as bracketed sound tags or barely heard they go`() {
        assertTrue(filter.accepts(segment("שירה!")))
        assertTrue(filter.accepts(segment("מוזיקה.")))
        assertTrue(filter.accepts(segment("צחוק")))
        assertFalse(filter.accepts(segment("(צחוק)")))
        assertFalse(filter.accepts(segment("[שירה]")))
        assertFalse(filter.accepts(segment("שירה", noSpeech = 0.5f, logprob = -0.4f)))
        assertFalse(filter.accepts(segment("מוזיקה", noSpeech = 0.1f, logprob = -1.2f)))
    }

    @Test
    fun `'toda raba' (thank you very much) that the model barely heard or guessed at is dropped as invented`() {
        assertFalse(filter.accepts(segment("תודה רבה.", noSpeech = 0.45f, logprob = -0.4f)))
        assertFalse(filter.accepts(segment("[תודה רבה]", noSpeech = 0.1f, logprob = -1.0f)))
        assertFalse(filter.accepts(segment("Thank you.", noSpeech = 0.6f, logprob = -0.3f)))
    }

    @Test
    fun `an invented phrase looped two or three times is judged as the phrase itself`() {
        assertFalse(filter.accepts(segment("תודה רבה. תודה רבה.", noSpeech = 0.45f, logprob = -0.4f)))
        assertFalse(filter.accepts(segment("תודה שצפיתם תודה שצפיתם תודה שצפיתם")))
        assertTrue(filter.accepts(segment("תודה רבה, תודה רבה!", noSpeech = 0.05f, logprob = -0.35f)))
        assertTrue(filter.accepts(segment("לא לא לא")))
    }

    @Test
    fun `Hebrew YouTube outros drop like their English twins, 'enjoy watching' only when the model was unsure`() {
        val filter = WhisperResultFilter()
        fun kept(text: String, noSpeech: Float = 0.1f) =
            filter.accepts(WhisperSegmentSummary(text = text, noSpeechProb = noSpeech, avgLogprob = -0.3f, compressionRatio = 1.2f))
        for (text in listOf("תודה רבה שצפיתם", "תודה רבה לכם שצפיתם!", "תודה שצפיתם בסרטון", "הירשמו לערוץ", "אל תשכחו להירשם לערוץ")) {
            assertFalse(kept(text), text)
        }
        assertTrue(kept("צפייה מהנה!"))
        assertFalse(kept("צפייה מהנה!", noSpeech = 0.5f))
        assertFalse(kept("נתראה בסרטון הבא", noSpeech = 0.5f))
        assertTrue(kept("תודה רבה שבאתם"))
        assertTrue(kept("הוא נרשם לערוץ של הנכד"))
    }
}

class WhisperRepeatCollapseTest {
    @Test
    fun `a word said up to three times is left alone, exactly as written`() {
        assertEquals("לא, לא, לא", WhisperResultFilter.collapsingRepeats("לא, לא, לא"))
        assertEquals("כן  כן כן", WhisperResultFilter.collapsingRepeats("כן  כן כן"))
        assertEquals("שלום מה שלומך היום", WhisperResultFilter.collapsingRepeats("שלום מה שלומך היום"))
        assertEquals(" שלום  מה שלומך היום ", WhisperResultFilter.collapsingRepeats(" שלום  מה שלומך היום "))
    }

    @Test
    fun `a word looped more than three times is cut to three, keeping the closing punctuation`() {
        assertEquals(
            "אני לא יכול לבוא לבוא לבוא",
            WhisperResultFilter.collapsingRepeats("אני לא יכול לבוא לבוא לבוא לבוא לבוא לבוא"),
        )
        assertEquals("כן, כן, כן.", WhisperResultFilter.collapsingRepeats("כן, כן, כן, כן, כן."))
    }

    @Test
    fun `a short phrase looped over and over is cut the same way`() {
        assertEquals(
            "אני הולך אני הולך אני הולך הביתה",
            WhisperResultFilter.collapsingRepeats("אני הולך אני הולך אני הולך אני הולך אני הולך הביתה"),
        )
        assertEquals("מה? מה? מה? טוב", WhisperResultFilter.collapsingRepeats("מה? מה? מה? מה? טוב"))
    }

    @Test
    fun `repeats that aren't back to back aren't touched`() {
        val text = "כן אמרתי כן אמרתי לו כן ואז כן"
        assertEquals(text, WhisperResultFilter.collapsingRepeats(text))
    }

    @Test
    fun `accepted text from Whisper comes out collapsed`() {
        val filter = WhisperResultFilter()
        val segment = WhisperSegmentSummary(
            text = "תבואי תבואי תבואי תבואי תבואי מחר", noSpeechProb = 0.01f, avgLogprob = -0.2f, compressionRatio = 1.5f,
        )
        assertEquals("תבואי תבואי תבואי מחר", filter.acceptedText(listOf(segment)))
    }

    @Test
    fun `a word called out four or five times and nothing else is shown cut to three, though the phone scores it past the loop line`() {
        val filter = WhisperResultFilter()
        val called = listOf(
            Triple("די די די די", 2.46f, "די די די"),
            Triple("די, די, די, די!", 2.67f, "די, די, די!"),
            Triple("סבתא סבתא סבתא סבתא", 2.82f, "סבתא סבתא סבתא"),
            Triple("די די די די די", 3.08f, "די די די"),
            Triple("לא עדני עדני עדני עדני", 3.25f, "לא עדני עדני עדני"),
            Triple("זה מלא מלא מלא מלא", 2.57f, "זה מלא מלא מלא"),
        )
        for ((said, ratio, shown) in called) {
            val segment = WhisperSegmentSummary(text = said, noSpeechProb = 0.0f, avgLogprob = -0.1f, compressionRatio = ratio)
            assertEquals(shown, filter.acceptedText(listOf(segment)), said)
        }
        for ((copies, ratio) in listOf(8 to 4.92f, 10 to 7.06f, 75 to 39.1f)) {
            val loop = WhisperSegmentSummary(
                text = List(copies) { "פאק" }.joinToString(" "), noSpeechProb = 0.0f, avgLogprob = -0.1f, compressionRatio = ratio,
            )
            assertFalse(filter.accepts(loop), "$copies copies")
        }
    }
}

class WhisperRepeatedSentenceTest {
    private val filter = WhisperResultFilter()
    private val cave = "המערה שוכנת בפסגת אחד ההרים מצפון למכה והיא מבודדת לחלוטין מכל שאר העולם."

    private fun segment(text: String, compression: Float) =
        WhisperSegmentSummary(text = text, noSpeechProb = 0.0f, avgLogprob = -0.03f, compressionRatio = compression)

    @Test
    fun `the same sentence written twice is kept, once, though the repeat pushes the compression past the loop line`() {
        val twice = segment("$cave $cave", 2.67f)
        assertTrue(filter.accepts(twice))
        assertEquals(cave, filter.acceptedText(listOf(twice)))
    }

    @Test
    fun `a second copy heard a word differently still counts as the same sentence`() {
        val other = cave.replace("ההרים", "הערים")
        val twice = segment("$cave $other", 2.6f)
        assertTrue(filter.accepts(twice))
        assertEquals(cave, filter.acceptedText(listOf(twice)))
        assertEquals(cave, filter.acceptedText(listOf(segment("$cave $cave $cave", 4.1f))))
    }

    @Test
    fun `a sentence said again that doubles a word itself, as 'slowly, slowly' and 'yes, yes' do, is kept once`() {
        val slowly = "סבתא, תלכי לאט לאט כשאת יורדת במדרגות כי הן עדיין רטובות מהגשם."
        val twice = segment("$slowly $slowly", 2.55f)
        assertTrue(filter.accepts(twice))
        assertEquals(slowly, filter.acceptedText(listOf(twice)))
        val yes = "כן כן, התור לרופא המשפחה נקבע ליום שלישי הבא בעשר וחצי בבוקר."
        assertEquals(yes, filter.acceptedText(listOf(segment("$yes $yes", 2.46f))))
        val wait = "רגע רגע, התור לרופא הוא ביום שלישי בבוקר."
        assertEquals(wait, filter.acceptedText(listOf(segment("$wait $wait $wait", 3.07f))))
    }

    @Test
    fun `a loop of four or more copies, a short phrase looped, or repetitive text that is not copies is still dropped`() {
        assertFalse(filter.accepts(segment(List(4) { cave }.joinToString(" "), 5.4f)))
        assertFalse(filter.accepts(segment("אני לא יודע אני לא יודע אני לא יודע", 3.0f)))
        assertFalse(filter.accepts(segment("$cave והיא מבודדת לחלוטין והיא מבודדת לחלוטין והיא מבודדת", 2.9f)))
        assertFalse(filter.accepts(segment("Subtitles by the Amara.org community. Subtitles by the Amara.org community.", 2.5f)))
        assertFalse(filter.accepts(segment(List(75) { "פאק" }.joinToString(" "), 21.9f)))
        assertFalse(filter.accepts(segment(List(4) { "התקדם בנושא הזה" }.joinToString(" "), 2.9f)))
        assertFalse(filter.accepts(segment(List(20) { "התקדם בנושא הזה" }.joinToString(" "), 13.2f)))
    }

    @Test
    fun `a sentence said once is shown exactly as written`() {
        assertEquals(cave, filter.acceptedText(listOf(segment(cave, 1.5f))))
    }
}

class WhisperKitDecodeRoomTest {
    @Test
    fun `the longest line leaves room for its words after the names, at the fastest rate real speech needed`() {
        val fastestTokensPerSecond = 8.3
        for (prompt in 0..WhisperKitDecodeRoom.maxPromptTokens) {
            val seconds = WhisperKitDecodeRoom.longestLineSeconds(prompt, 28.0)
            val opening = 4 + (if (prompt > 0) prompt + 1 else 0)
            assertTrue(opening + seconds * fastestTokensPerSecond <= 223, "$prompt prompt tokens")
        }
    }

    @Test
    fun `with no names a line runs about as long as before, a full list shortens it, never past the cap`() {
        val none = WhisperKitDecodeRoom.longestLineSeconds(0, 28.0)
        val full = WhisperKitDecodeRoom.longestLineSeconds(WhisperKitDecodeRoom.maxPromptTokens, 28.0)
        assertTrue(none > 25 && none <= 28)
        assertTrue(full > 15 && full < none)
        assertEquals(20.0, WhisperKitDecodeRoom.longestLineSeconds(0, 20.0))
    }

    @Test
    fun `the names are cut before WhisperKit would cut them from the front, where the most important are`() {
        assertTrue(WhisperKitDecodeRoom.maxPromptTokens <= 111)
    }

    @Test
    fun `a live pass has room to spare for the most words real speech wrote in that much audio`() {
        val fastest = listOf(0.6 to 12, 1.2 to 20, 1.8 to 26, 2.4 to 30, 3.0 to 33, 4.8 to 50)
        for ((seconds, tokens) in fastest) {
            val room = WhisperKitDecodeRoom.livePassTokens(seconds)
            assertTrue(room >= tokens + 16, "$seconds s")
            assertFalse(WhisperKitDecodeRoom.livePassRanOut(tokens, seconds))
        }
        for (tenths in 6..280) {
            val seconds = tenths.toDouble() / 10
            assertTrue(WhisperKitDecodeRoom.livePassTokens(seconds).toDouble() >= minOf(223.0, seconds * 17.4), "$seconds s")
        }
    }

    @Test
    fun `a pass looping on a long sound in the first second stops long before the decoder's end, and is dropped`() {
        val room = WhisperKitDecodeRoom.livePassTokens(0.6)
        assertTrue(room <= 32)
        assertTrue(WhisperKitDecodeRoom.livePassTokens(1.2) <= 48)
        assertTrue(WhisperKitDecodeRoom.livePassRanOut(room, 0.6))
        assertFalse(WhisperKitDecodeRoom.livePassRanOut(room - 1, 0.6))
    }

    @Test
    fun `a long window keeps the whole decoder, so what stops it is the same as before`() {
        assertEquals(WhisperKitDecodeRoom.positions, WhisperKitDecodeRoom.livePassTokens(28.0))
        assertFalse(WhisperKitDecodeRoom.livePassRanOut(218, 28.0))
    }
}

class UnvoicedPhraseTest {
    private val filter = WhisperResultFilter()

    @Test
    fun `a lone thank-you heard in one voiced chunk is dropped, in two it is shown`() {
        assertTrue(filter.isUnvoicedPhrase("תודה רבה.", 1))
        assertTrue(filter.isUnvoicedPhrase("‫תודה", 0))
        assertFalse(filter.isUnvoicedPhrase("תודה רבה", 2))
    }

    @Test
    fun `a thank-you inside a longer line, or any other short word, is never touched`() {
        assertFalse(filter.isUnvoicedPhrase("תודה רבה, סבתא", 1))
        assertFalse(filter.isUnvoicedPhrase("כן", 1))
        assertFalse(filter.isUnvoicedPhrase("", 0))
    }

    @Test
    fun `without a count (no voice model, or captions stopping) nothing is dropped`() {
        assertFalse(filter.isUnvoicedPhrase("תודה רבה", null))
    }

    @Test
    fun `the phrase said twice over is still the phrase`() {
        assertTrue(filter.isUnvoicedPhrase("תודה תודה", 1))
    }
}
