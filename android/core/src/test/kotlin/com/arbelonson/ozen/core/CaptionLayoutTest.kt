package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionLayoutTest {
    @Test
    fun `a short line is left exactly as it is`() {
        assertEquals("מה שלומך? טוב.", CaptionLayout.readableText("מה שלומך? טוב."))
    }

    @Test
    fun `a long monologue is broken into paragraphs at sentence ends`() {
        val text = "אתמול הלכנו לשוק בבוקר מוקדם. היה שם המון אנשים וקנינו ירקות טריים לכל השבוע. אחר כך ישבנו בבית קפה קטן ליד התחנה. ואז חזרנו הביתה באוטובוס."
        val readable = CaptionLayout.readableText(text)
        val paragraphs = readable.split("\n").filter { it.isNotEmpty() }
        assertTrue(paragraphs.size >= 2)
        assertTrue(paragraphs.all { it.length <= CaptionLayout.PARAGRAPH_CHARACTERS })
        // Nothing lost or reordered: only spaces became line breaks.
        assertEquals(text, readable.replace("\n", " "))
    }

    @Test
    fun `sentences are gathered while they fit, so short replies don't each get a line`() {
        val text = "כן. בטח. למה לא? " + "מילה ".repeat(20) + "סוף."
        val readable = CaptionLayout.readableText(text, paragraphCharacters = 40)
        assertTrue(readable.startsWith("כן. בטח. למה לא?\n"))
    }

    @Test
    fun `a paragraph is filled right up to the limit, and never a character past it`() {
        fun sentence(length: Int): String = "מ".repeat(length - 1) + "."
        assertEquals(
            sentence(10) + " " + sentence(9) + "\n" + sentence(10),
            CaptionLayout.readableText(listOf(sentence(10), sentence(9), sentence(10)).joinToString(" "), paragraphCharacters = 20),
        )
        assertEquals(
            sentence(10) + "\n" + sentence(10),
            CaptionLayout.readableText(sentence(10) + " " + sentence(10), paragraphCharacters = 20),
        )
    }

    @Test
    fun `decimals, abbreviations with gershayim, and quotes after the stop don't split wrongly`() {
        assertEquals(listOf("הוא לקח 3.5 כדורים של ד״ר כהן."), CaptionLayout.splitSentences("הוא לקח 3.5 כדורים של ד״ר כהן."))
        assertEquals(
            listOf("היא אמרה \"די!\"", "והלכה.", "באמת?!", "כן"),
            CaptionLayout.splitSentences("היא אמרה \"די!\" והלכה. באמת?!  כן"),
        )
    }

    @Test
    fun `in Hebrew every paragraph reads right to left, even one opening with a Latin word`() {
        val mark = "‏"
        assertEquals(mark + "OK, אז נתראה מחר", CaptionLayout.displayText("OK, אז נתראה מחר"))
        assertEquals(mark + "שלום", CaptionLayout.displayText("שלום", languageCode = "he-IL"))

        val long = "אתמול הלכנו לשוק בבוקר מוקדם. WhatsApp שלחה הודעה וקנינו ירקות טריים לכל השבוע. אחר כך ישבנו בבית קפה קטן ליד התחנה."
        val shown = CaptionLayout.displayText(long)
        val paragraphs = shown.split("\n").filter { it.isNotEmpty() }
        assertTrue(paragraphs.size >= 2)
        assertTrue(paragraphs.all { it.startsWith(mark) })
        // Only the marks were added.
        assertEquals(CaptionLayout.readableText(long), shown.replace(mark, ""))
    }

    @Test
    fun `trailing punctuation after a Latin word or a digit gets a mark of its own, anchoring it at the line's end`() {
        val mark = "‏"
        assertTrue(CaptionLayout.displayText("תתקשר ב-WhatsApp.").endsWith(mark))
        assertTrue(CaptionLayout.displayText("התרופה היא Acamol!").endsWith(mark))
        assertTrue(CaptionLayout.displayText("בדקו ב-WhatsApp)").endsWith(mark))
        // Punctuation after a Hebrew word needs no trailing mark: it's
        // already anchored by the paragraph's own right-to-left context.
        assertFalse(CaptionLayout.displayText("מה שלומך?").endsWith(mark))
        assertFalse(CaptionLayout.displayText("שלום").endsWith(mark))
    }

    @Test
    fun `a left-to-right language is shown exactly as laid out`() {
        assertEquals("OK, see you tomorrow.", CaptionLayout.displayText("OK, see you tomorrow.", languageCode = "en"))
        assertEquals("OK, see you tomorrow.", CaptionLayout.directed("OK, see you tomorrow.", languageCode = "en"))
    }

    @Test
    fun `a paragraph without a Hebrew letter, English said to her, keeps its own left-to-right reading`() {
        val mark = "‏"
        assertEquals("Good morning, how did you sleep?", CaptionLayout.displayText("Good morning, how did you sleep?"))
        assertEquals("I can take you, no problem.", CaptionLayout.directed("I can take you, no problem."))
        assertEquals(mark + "10:30?" + mark, CaptionLayout.directed("10:30?"))
        assertEquals(mark + "OK, אז נתראה מחר.", CaptionLayout.directed("OK, אז נתראה מחר."))

        val mixed = "אתמול הלכנו לשוק בבוקר מוקדם וקנינו ירקות טריים לכל השבוע. Pretty good, thanks, I have a doctor's appointment at ten."
        val paragraphs = CaptionLayout.displayText(mixed).split("\n").filter { it.isNotEmpty() }
        assertEquals(
            listOf(mark + "אתמול הלכנו לשוק בבוקר מוקדם וקנינו ירקות טריים לכל השבוע.", "Pretty good, thanks, I have a doctor's appointment at ten."),
            paragraphs,
        )
    }

    @Test
    fun `which text would lay itself out left to right`() {
        assertTrue(CaptionLayout.opensLeftToRight("OK, אז נתראה מחר"))
        assertTrue(CaptionLayout.opensLeftToRight("[10:30:00] WhatsApp שלחה"))
        assertEquals(false, CaptionLayout.opensLeftToRight("[10:30:00] סבתא: OK"))
        assertEquals(false, CaptionLayout.opensLeftToRight("3 כדורים ביום"))
        assertEquals(false, CaptionLayout.opensLeftToRight("12:30, 3.5 ..."))
    }

    @Test
    fun `a preview gets the marks without being broken into paragraphs`() {
        val long = "אתמול הלכנו לשוק בבוקר מוקדם. WhatsApp שלחה הודעה וקנינו ירקות טריים לכל השבוע. אחר כך ישבנו בבית קפה קטן ליד התחנה."
        assertEquals("‏" + long, CaptionLayout.directed(long))
    }

    @Test
    fun `a single sentence longer than a paragraph stays whole`() {
        val text = "מילה ".repeat(40).trim()
        assertEquals(text, CaptionLayout.readableText(text))
    }
}

class CaptionLayoutNumberDirectionTest {
    private val open = "⁦"
    private val close = "⁩"
    private val mark = "‏"

    @Test
    fun `a star code, a number in spaced groups and an international number are each kept left to right in a Hebrew line`() {
        assertEquals(mark + "תתקשרי לקופה " + open + "*2700" + close, CaptionLayout.displayText("תתקשרי לקופה *2700"))
        assertEquals(mark + "תתקשרי " + open + "050 123 4567" + close + " מחר", CaptionLayout.displayText("תתקשרי 050 123 4567 מחר"))
        assertEquals(mark + "אליו " + open + "+972-3-1234567" + close + ".", CaptionLayout.directed("אליו +972-3-1234567."))
        assertEquals(mark + "המוקד " + open + "1-700-50-50-50" + close + " פתוח", CaptionLayout.directed("המוקד 1-700-50-50-50 פתוח"))
    }

    @Test
    fun `times, doses and English lines are left as they were, and the numbers still dial from the shown line`() {
        assertEquals(mark + "בשעה 10:30, 3 כדורים", CaptionLayout.displayText("בשעה 10:30, 3 כדורים"))
        assertEquals("call 050 123 4567", CaptionLayout.displayText("call 050 123 4567", languageCode = "en"))
        val shown = CaptionLayout.displayText("תתקשרי 050 123 4567 או +972-3-1234567 מחר")
        assertEquals(listOf("0501234567", "+97231234567"), PhoneNumbers.matches(shown).map { it.dialable })
    }

    @Test
    fun `a copied line keeps its phone number left to right when pasted into a Hebrew chat, and plain words are copied as said`() {
        assertEquals("תתקשרי " + open + "050 123 4567" + close + " מחר", CaptionLayout.copiedText("תתקשרי 050 123 4567 מחר"))
        assertEquals("לקופה " + open + "*2700" + close, CaptionLayout.copiedText("לקופה *2700"))
        assertEquals("בשעה 10:30, 3 כדורים", CaptionLayout.copiedText("בשעה 10:30, 3 כדורים"))
        assertEquals(listOf("0501234567"), PhoneNumbers.matches(CaptionLayout.copiedText("תתקשרי 050 123 4567")).map { it.dialable })
    }
}

class CaptionLayoutSpeakerLabelTest {
    private fun line(cluster: Int?) =
        TranscriptSegment(id = UUID.randomUUID(), text = "שלום", isCommitted = true, speakerClusterID = cluster, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    @Test
    fun `the name appears when the speaker changes, not on every line`() {
        val lines = listOf(line(0), line(0), line(1), line(1), line(0))
        val shown = lines.indices.map { CaptionLayout.showsSpeakerLabel(lines[it], if (it > 0) lines[it - 1] else null) }
        assertEquals(listOf(true, false, true, false, true), shown)
    }

    @Test
    fun `five quiet minutes between lines puts a time between them, and the name heads the run again`() {
        fun at(start: Double, end: Double, speaker: Int? = 1) =
            TranscriptSegment(id = UUID.randomUUID(), text = "line", isCommitted = true, speakerClusterID = speaker, startTimestamp = start, lastUpdateTimestamp = end)
        val first = at(1000.0, 1010.0)
        assertFalse(CaptionLayout.startsAfterQuiet(first, null as TranscriptSegment?))
        assertFalse(CaptionLayout.startsAfterQuiet(at(1309.0, 1320.0), first))
        assertTrue(CaptionLayout.startsAfterQuiet(at(1310.0, 1320.0), first))
        assertFalse(CaptionLayout.showsSpeakerLabel(at(1020.0, 1030.0), first))
        assertTrue(CaptionLayout.showsSpeakerLabel(at(1400.0, 1410.0), first))
        assertFalse(CaptionLayout.showsSpeakerLabel(at(1400.0, 1410.0, speaker = null), first))
    }

    @Test
    fun `two voices with one name are one speaker - her name doesn't head every line`() {
        val names = mapOf(0 to "Savta", 1 to "Savta", 2 to "Dana")
        val lines = listOf(line(0), line(1), line(0), line(2), line(1))
        val shown = lines.indices.map {
            CaptionLayout.showsSpeakerLabel(lines[it], if (it > 0) lines[it - 1] else null) { segment -> names[segment.speakerClusterID ?: -1] ?: "" }
        }
        assertEquals(listOf(true, false, false, true, true), shown)
        assertFalse(CaptionLayout.showsSpeakerLabel(line(null), line(0)) { "Savta" })
    }

    @Test
    fun `a line with no identified speaker has no label, and the next identified line gets one`() {
        assertEquals(false, CaptionLayout.showsSpeakerLabel(line(null), null as TranscriptSegment?))
        assertEquals(false, CaptionLayout.showsSpeakerLabel(line(null), line(2)))
        assertTrue(CaptionLayout.showsSpeakerLabel(line(2), line(null)))
    }
}

class CaptionLayoutSavedSpeakerLabelTest {
    private fun line(name: String?) =
        SavedSegment(id = UUID.randomUUID(), text = "שלום", speakerName = name, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true)

    @Test
    fun `a saved conversation shows each name when the speaker changes`() {
        val lines = listOf(line("שרה"), line("שרה"), line("דובר 2"), line("שרה"))
        val shown = lines.indices.map { CaptionLayout.showsSpeakerLabel(lines[it], if (it > 0) lines[it - 1] else null) }
        assertEquals(listOf(true, false, true, true), shown)
    }

    @Test
    fun `unknown-speaker lines have no label and don't break a run`() {
        assertEquals(false, CaptionLayout.showsSpeakerLabel(line(EmbeddingClusterer.unknownSpeakerName), null as SavedSegment?))
        assertEquals(false, CaptionLayout.showsSpeakerLabel(line("Unknown speaker"), null as SavedSegment?))
        assertEquals(false, CaptionLayout.showsSpeakerLabel(line(null), line("שרה")))
        assertTrue(CaptionLayout.showsSpeakerLabel(line("שרה"), line(EmbeddingClusterer.unknownSpeakerName)))
    }

    @Test
    fun `six quiet minutes between two saved lines repeats the same speaker's name, like the live view does`() {
        fun at(start: Double, name: String? = "שרה") = SavedSegment(
            id = UUID.randomUUID(), text = "שלום", speakerName = name, speakerClusterID = null,
            startTimestamp = 1_790_000_000 + start, isCommitted = true,
        )
        val first = at(0.0)
        assertFalse(CaptionLayout.startsAfterQuiet(at(299.0), first))
        assertTrue(CaptionLayout.startsAfterQuiet(at(300.0), first))
        assertFalse(CaptionLayout.showsSpeakerLabel(at(120.0), first))
        assertTrue(CaptionLayout.showsSpeakerLabel(at(360.0), first))
        assertFalse(CaptionLayout.showsSpeakerLabel(at(360.0, name = null), first))
    }
}

class CaptionLayoutTimeMarkTest {
    private fun line(at: Double) =
        SavedSegment(id = UUID.randomUUID(), text = "x", speakerName = null, speakerClusterID = null, startTimestamp = at, isCommitted = true)

    @Test
    fun `the first line, then the first line five minutes after the last time shown`() {
        val lines = listOf(line(0.0), line(60.0), line(299.0), line(300.0), line(400.0), line(700.0), line(2_000.0))
        val marked = CaptionLayout.timeMarkedLineIDs(lines)
        assertEquals(listOf(true, false, false, true, false, true, true), lines.map { it.id in marked })
    }

    @Test
    fun `no lines, no marks`() {
        assertTrue(CaptionLayout.timeMarkedLineIDs(emptyList()).isEmpty())
    }
}

class CaptionLayoutOnScreenTest {
    @Test
    fun `only the newest lines are drawn once there are more than the limit`() {
        assertEquals(0, CaptionLayout.firstOnScreenIndex(0))
        assertEquals(0, CaptionLayout.firstOnScreenIndex(CaptionLayout.ON_SCREEN_LINE_LIMIT))
        assertEquals(1, CaptionLayout.firstOnScreenIndex(CaptionLayout.ON_SCREEN_LINE_LIMIT + 1))
        assertEquals(20_000 - CaptionLayout.ON_SCREEN_LINE_LIMIT, CaptionLayout.firstOnScreenIndex(20_000))
    }

    @Test
    fun `the README and the troubleshooting guide give the screen's line limit`() {
        val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
        for (doc in listOf("README.md", "docs/troubleshooting.md")) {
            val text = File(root, doc).readText(Charsets.UTF_8)
            assertTrue(text.contains("newest ${CaptionLayout.ON_SCREEN_LINE_LIMIT} lines"), doc)
        }
    }
}
