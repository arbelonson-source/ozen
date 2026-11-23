package com.arbelonson.ozen.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhisperPassTextTest {
    private val texts = listOf(
        "מה שלומך היום", " שלום, מה נשמע?", "דני, בוא לאכול", "בואי לאכול.", "תבואי תבואי תבואי מחר",
        "תודה רבה", "תודה רבה.", " תודה", "תודה רבה תודה רבה תודה רבה", "תודה שצפיתם.", "[תודה על הצפייה]",
        "Thank you.", "you", "Music", "Thank you for watching", "Subtitles by the Amara.org community",
        "כתוביות: ישראל ישראלי", "תרגום: מיכל", "תרגום - מיכל", "Subtitles by default are off",
        "...", "-", "♪", "?!", "", "   ", "[מוזיקה]", "(צוחק) כן, בטח", "(מוזיקה)", "<|0.00|> מה נשמע <|1.20|>",
        "לא לא לא לא לא לא לא", "די די די די!", "Come on, come on, come on, come on, let's go!",
        "אחת שתיים שלוש אחת שתיים שלוש אחת שתיים שלוש אחת שתיים שלוש", "0 5 4 3 2 1 0 9 8 7 6 5",
        "אני הולך הביתה עכשיו. אני הולך הביתה עכשיו. אני הולך הביתה עכשיו.",
        "אבי, רותי, דני.", "אבי, רותי, דני. אבי, רותי, דני.", "סבתא.", " סבתא!", "סבתא, בואי.",
        "I think we should go now.", "Bonjour, comment ça va ?", "Merci.", "Hola, ¿cómo estás?", "Gracias.",
        "Olá, tudo bem?", "Obrigado.", "مرحبا كيف حالك", "شكرا", "Привет, как дела?", "Спасибо.",
        "今日はいい天気ですね。", "ご視聴ありがとうございました",
    )
    private val filters = listOf("he", "en", "ar", "fr", "es", "pt", "ru", "ja").map { WhisperResultFilter().forSpeech(it) }
    private val echoes = listOf(null, PromptEchoDetector(listOf("סבתא")), PromptEchoDetector(listOf("אבי", "רותי", "דני")))
    private val noSpeech = listOf(0f, 0.2f, 0.3f, 0.7f)
    private val logprobs = listOf(-0.05f, -0.5f, -0.95f, -1.2f, Float.NEGATIVE_INFINITY)
    private val ratios = listOf(0.6f, 1.2f, 2.6f, 3.5f, 5f, Float.POSITIVE_INFINITY)
    private val voiced = listOf(null, 0, 1, 2, 5)

    private fun segment(random: Random) = WhisperSegmentSummary(
        text = texts.random(random),
        noSpeechProb = noSpeech.random(random),
        avgLogprob = logprobs.random(random),
        compressionRatio = ratios.random(random),
        uncertainWords = if (random.nextBoolean()) emptyList() else listOf("מחר"),
        temperature = if (random.nextInt(4) == 0) 0.2f else 0f,
    )

    @Test
    fun `a pass shows exactly what checking its text and its segments apart showed`() {
        val random = Random(757)
        var shown = 0
        var unvoiced = 0
        var partly = 0
        var echoed = 0
        repeat(20_000) { case ->
            val filter = filters.random(random)
            val run = random.nextInt(4) == 0
            val one = segment(random)
            val segments = if (run) List(random.nextInt(2, 5)) { one } else List(random.nextInt(0, 6)) { segment(random) }
            val echo = echoes.random(random)
            val voicedChunks = voiced.random(random)

            val dropped = filter.isUnvoicedPhrase(filter.acceptedText(segments, echo), voicedChunks, echo)
            val segmentsBefore = if (dropped) emptyList() else filter.accepted(segments, echo)
            val textBefore = if (dropped) "" else filter.acceptedText(segments, echo)

            val pass = filter.passText(segments, voicedChunks, echo)
            val what = "case $case: ${segments.map { it.text }} echo ${echo != null} voiced $voicedChunks"
            assertEquals(textBefore, pass.text, what)
            assertEquals(segmentsBefore, pass.segments, what)
            assertTrue(segmentsBefore.indices.all { segmentsBefore[it] === pass.segments[it] }, what)
            assertEquals(CaptionConfidence.whisperConfidence(segmentsBefore), CaptionConfidence.whisperConfidence(pass.segments), what)
            assertEquals(segmentsBefore.flatMap { it.uncertainWords }, pass.segments.flatMap { it.uncertainWords }, what)

            val kept = filter.accepted(segments, echo)
            if (textBefore.isNotEmpty()) shown += 1
            if (dropped && kept.isNotEmpty()) unvoiced += 1
            if (kept.isNotEmpty() && kept.size < segments.size) partly += 1
            if (echo != null && kept.size < filter.accepted(segments).size) echoed += 1
        }
        assertTrue(shown > 2_000 && unvoiced > 50 && partly > 1_000 && echoed > 50, "shown $shown, unvoiced $unvoiced, partly $partly, echoed $echoed")
    }
}
