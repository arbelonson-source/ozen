package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocabularyTest {
    @Test
    fun `normalizing trims, drops blanks and duplicates, and keeps first-seen order`() {
        val cleaned = VocabularyHints.normalized(listOf("  רותי ", "", "אבי", "רותי", "אָבִי", "   ", "Dani", "dani"))
        assertEquals(listOf("רותי", "אבי", "Dani"), cleaned)
    }

    @Test
    fun `a geresh makes a different word, and a typed apostrophe is the same mark`() {
        assertEquals(listOf("ציפס", "צ׳יפס"), VocabularyHints.normalized(listOf("ציפס", "צ׳יפס")))
        assertEquals(listOf("צ׳יפס"), VocabularyHints.normalized(listOf("צ׳יפס", "צ'יפס")))
        assertEquals("צ׳יפס", VocabularyHints.listedEntry("צ'יפס", listOf("צ׳יפס")))
        assertNull(VocabularyHints.listedEntry("ציפס", listOf("צ׳יפס")))
        assertEquals(2, VocabularyHints.normalized(listOf("ד״ר כהן", "דר כהן")).size)
    }

    @Test
    fun `the marks a phone types for a geresh or gershayim, with Smart Punctuation on or off, are the same mark`() {
        assertEquals(listOf("צ׳יפס"), VocabularyHints.normalized(listOf("צ׳יפס", "צ’יפס", "צ‘יפס")))
        assertEquals(
            listOf("ד״ר כהן"),
            VocabularyHints.normalized(listOf("ד״ר כהן", "ד\"ר כהן", "ד”ר כהן", "ד“ר כהן")),
        )
        assertEquals("ד״ר כהן", VocabularyHints.listedEntry("ד”ר כהן", listOf("ד״ר כהן")))
        assertEquals("צ׳יפס", VocabularyHints.listedEntry("צ’יפס", listOf("צ׳יפס")))
    }

    @Test
    fun `an over-long entry is clipped and the list is capped`() {
        val long = "א".repeat(100)
        assertEquals(listOf("א".repeat(VocabularyHints.maximumTermLength)), VocabularyHints.normalized(listOf(long)))

        val many = (0 until 300).map { "שם$it" }
        assertEquals(VocabularyHints.maximumTerms, VocabularyHints.normalized(many).size)
    }

    @Test
    fun `a typed word already on the list is found, ignoring case, niqqud and spaces`() {
        val terms = listOf("Ruti", "אבי")
        assertEquals("Ruti", VocabularyHints.listedEntry("  ruti ", terms))
        assertEquals("אבי", VocabularyHints.listedEntry("אָבִי", terms))
        assertNull(VocabularyHints.listedEntry("Rotem", terms))
        assertNull(VocabularyHints.listedEntry("   ", terms))
    }

    @Test
    fun `the Whisper prompt is a comma list ending in a period, or empty when there is nothing to say`() {
        assertEquals("", VocabularyHints.whisperPrompt(emptyList()))
        assertEquals("", VocabularyHints.whisperPrompt(listOf("", "  ")))
        assertEquals("אבי, רותי.", VocabularyHints.whisperPrompt(listOf("אבי", "רותי ")))
    }

    @Test
    fun `a names list too long for the prompt keeps whole names from the top, never half of the next one`() {
        val letters: (String) -> Int = { it.length }
        val terms = listOf("Avi", "Ruti", "Savta", "Dani")
        assertEquals("Avi, Ruti, Savta, Dani.", VocabularyHints.whisperPrompt(terms, 100, letters))
        assertEquals("Avi, Ruti, Savta.", VocabularyHints.whisperPrompt(terms, 18, letters))
        assertEquals("Avi, Ruti.", VocabularyHints.whisperPrompt(terms, 17, letters))
        assertEquals("", VocabularyHints.whisperPrompt(terms, 4, letters))
        assertEquals(
            VocabularyHints.whisperPrompt(listOf("Avi,", "", "Ruti")),
            VocabularyHints.whisperPrompt(listOf("Avi,", "", "Ruti"), 100, letters),
        )
    }

    @Test
    fun `a comma typed after a name, or Whisper's control text pasted into one, stays out of the prompt`() {
        assertEquals("Avi, Ruti.", VocabularyHints.whisperPrompt(listOf("Avi,", "Ruti;")))
        assertEquals(listOf("אבי"), VocabularyHints.normalized(listOf("<|endoftext|>", "אבי<|he|>")))
        assertEquals("Avi", VocabularyHints.listedEntry("Avi,", listOf("Avi")))
    }

    @Test
    fun `an enabled keyword alert's phrase joins the vocabulary, a disabled one does not`() {
        val alerts = listOf(
            KeywordAlert(phrase = "סבתא"),
            KeywordAlert(phrase = "אמבולנס", isEnabled = false),
        )
        assertEquals(listOf("רותי", "סבתא"), VocabularyHints.combining(listOf("רותי"), alerts))
    }

    @Test
    fun `an alert phrase already in the vocabulary is not duplicated`() {
        val alerts = listOf(KeywordAlert(phrase = "סבתא"), KeywordAlert(phrase = "  סבתא "))
        assertEquals(listOf("סבתא", "רותי"), VocabularyHints.combining(listOf("סבתא", "רותי"), alerts))
    }

    @Test
    fun `combining still respects the term cap and length clip`() {
        val long = "א".repeat(100)
        val combined = VocabularyHints.combining(emptyList(), listOf(KeywordAlert(phrase = long)))
        assertEquals(listOf("א".repeat(VocabularyHints.maximumTermLength)), combined)

        val many = (0 until 300).map { KeywordAlert(phrase = "שם$it") }
        assertEquals(VocabularyHints.maximumTerms, VocabularyHints.combining(emptyList(), many).size)
    }

    @Test
    fun `an entry is clipped by characters, so a pointed letter is never split from its vowel points`() {
        val pointed = "שָׁ".repeat(60)
        val clipped = VocabularyHints.normalized(listOf(pointed)).single()
        assertEquals("שָׁ".repeat(VocabularyHints.maximumTermLength), clipped)
    }
}

class PromptEchoDetectorTest {
    private val detector = PromptEchoDetector(listOf("אבי", "רותי", "דני", "ד״ר כהן", "אקמול"))

    @Test
    fun `the prompt read back, whole or as a run of three or more entries, is an echo`() {
        assertTrue(detector.isEcho("אבי, רותי, דני, ד״ר כהן, אקמול."))
        assertTrue(detector.isEcho("רותי, דני, ד\"ר כהן"))
        assertTrue(detector.isEcho("דני ד״ר כהן אקמול"))
    }

    @Test
    fun `a single name, or two, is real speech and passes`() {
        assertFalse(detector.isEcho("אבי"))
        assertFalse(detector.isEcho("אבי!"))
        assertFalse(detector.isEcho("אבי, רותי"))
    }

    @Test
    fun `names out of list order, or mixed with other words, are real speech`() {
        assertFalse(detector.isEcho("דני, אבי, רותי"))
        assertFalse(detector.isEcho("אבי רותי ודני באים"))
        assertFalse(detector.isEcho("צריך לקנות אקמול"))
    }

    @Test
    fun `with a two-name list the whole list counts, but either name alone does not`() {
        val small = PromptEchoDetector(listOf("אבי", "רותי"))
        assertTrue(small.isEcho("אבי, רותי."))
        assertFalse(small.isEcho("רותי"))
    }

    @Test
    fun `a one-name list never flags anything`() {
        val single = PromptEchoDetector(listOf("סבתא"))
        assertFalse(single.isEcho("סבתא"))
        assertFalse(single.isEcho("סבתא."))
    }

    @Test
    fun `naming two people who share a word (a name and a titled form of it) is real speech, not an echo`() {
        val small = PromptEchoDetector(listOf("רותי", "ד״ר רותי"))
        assertFalse(small.isEcho("רותי, ד״ר רותי"))

        val larger = PromptEchoDetector(listOf("אבי", "רותי", "ד״ר רותי"))
        assertFalse(larger.isEcho("אבי, רותי, ד״ר רותי"))
    }

    @Test
    fun `the filter drops an echo segment and keeps the real one next to it`() {
        val filter = WhisperResultFilter()
        val segments = listOf(
            WhisperSegmentSummary(text = " אבי, רותי, דני.", noSpeechProb = 0.2f, avgLogprob = -0.3f, compressionRatio = 1.2f),
            WhisperSegmentSummary(text = " דני, בוא לאכול", noSpeechProb = 0.1f, avgLogprob = -0.2f, compressionRatio = 1.1f),
        )
        assertEquals("דני, בוא לאכול", filter.acceptedText(segments, detector))
        assertEquals("אבי, רותי, דני. דני, בוא לאכול", filter.acceptedText(segments))
    }
}
