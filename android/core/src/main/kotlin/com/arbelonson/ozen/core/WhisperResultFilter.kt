package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

/**
 * The per-segment numbers Whisper reports alongside its text, reduced to what
 * the filter below needs. Kept as a plain class so the filtering rules are
 * testable without WhisperKit's own result types.
 */
data class WhisperSegmentSummary(
    var text: String,
    /** Probability the model assigns to "this window contains no speech". */
    var noSpeechProb: Float,
    /** Mean log-probability of the emitted tokens; very negative means the model was guessing. */
    var avgLogprob: Float,
    /**
     * zlib compression ratio - high values mean repetitive output, the
     * signature of a decoding loop ("toda toda toda ..."). The home computer
     * measures it over the text; the phone's engine (WhisperKit) over its
     * token numbers, where the same short repeat scores higher.
     */
    var compressionRatio: Float,
    /** The words of this segment the model was least sure of (see [UncertainWords]), when the caller worked them out. */
    var uncertainWords: List<String> = emptyList(),
    /**
     * Above 0 when the plain decode failed the model's own checks and this is
     * a retry with sharpened odds.
     */
    var temperature: Float = 0f,
) {
    companion object {
        /**
         * The words' token scores summed, over their count plus one for the
         * end of text: the reference Whisper's average, and the home
         * computer's, on which every cutoff here and the unsure mark's were
         * measured. WhisperKit 1.1's own also counts the four tokens that open
         * every line and the end at a perfect 0, which lifted a misheard short
         * line from 0.43 to 0.60 and kept the question mark off it: of 839
         * broadcast lines through Turbo, 10 of the 21 marked lost the mark, 9
         * of them with a word wrong. The end's own score, which WhisperKit
         * doesn't keep, counts as 0.
         */
        fun averageLogprob(wordTokenLogprobs: List<Float>): Float? {
            if (wordTokenLogprobs.isEmpty()) return null
            return wordTokenLogprobs.fold(0f) { sum, value -> sum + value } / (wordTokenLogprobs.size + 1).toFloat()
        }
    }
}

/**
 * How much a line the phone's engine can write out whole. WhisperKit 1.1
 * decodes in 224 token positions (half of Whisper's 448) and stops at 223: the
 * four that open a line, the names prompt with its marker, then the words;
 * what it hasn't written by then is skipped with the rest of the window. Real
 * long lines needed up to 8.3 tokens a second (123 broadcast lines of 22-27 s
 * through ivrit.ai's Turbo, median 6.2), so with 60 tokens of names 86 of them
 * would have lost their end, and with WhisperKit's most, every one. A line is
 * cut short enough to fit instead, at a breath, and nothing is lost.
 */
object WhisperKitDecodeRoom {
    const val positions = 223
    const val tokensPerSecond = 8.5

    /**
     * WhisperKit keeps the last 111 tokens of a longer prompt, which would
     * drop the names listed first; cut here, from the end, instead. Each token
     * of names takes 0.12 s off the longest line.
     */
    const val maxPromptTokens = 80

    fun longestLineSeconds(promptTokens: Int, cap: Double): Double {
        val opening = 4 + (if (promptTokens > 0) promptTokens + 1 else 0)
        return minOf(cap, (positions - opening) / tokensPerSecond)
    }

    /**
     * How many words a pass over a line still being said may write. On 839
     * recordings through ivrit.ai's Turbo, 11 of 3,117 such passes looped on a
     * drawn-out sound (an "ehhh" written as one letter over and over) and ran
     * to the decoder's end, 224 tokens: the filter throws the pass away, but
     * the phone had spent a whole decode on it and waits twice as long again
     * before the next. Real speech, there and in 3,485 more passes over 82
     * windows of up to 27 s, never wrote more than 12 tokens in 0.6 s, 20 in
     * 1.2 s or 50 in 4.8 s (17.4 a second at the 99.9th percentile), so a pass
     * that fills this much room is a loop, and the screen keeps what it had.
     * The pass that ends the line keeps the whole decoder.
     */
    fun livePassTokens(seconds: Double): Int = minOf(positions, ceil(seconds * 20).toInt() + 16)

    fun livePassRanOut(wordTokens: Int, seconds: Double): Boolean = wordTokens >= livePassTokens(seconds)
}

private fun graphemesOfWhisper(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun isWhisperWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
        codePoint == 0x205F || codePoint == 0x3000

private fun splitOnWhisperWhitespace(text: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    text.codePoints().forEach { codePoint ->
        if (isWhisperWhitespace(codePoint)) {
            if (current.isNotEmpty()) words.add(current.toString())
            current.setLength(0)
        } else {
            current.appendCodePoint(codePoint)
        }
    }
    if (current.isNotEmpty()) words.add(current.toString())
    return words
}

private fun isWhisperSpaceOrNewline(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85
    }

private fun trimmingWhisperWhitespace(text: String): String = trimmingWhisperScalars(text) { isWhisperSpaceOrNewline(it) }

private inline fun trimmingWhisperScalars(text: String, isTrimmed: (Int) -> Boolean): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && isTrimmed(scalars[from])) from++
    while (to > from && isTrimmed(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}

private fun isWhisperPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

private fun isWhisperSymbol(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
    else -> false
}

/**
 * Whisper is famous for hallucinating on silence and background noise: given
 * a quiet room it will happily emit "toda raba" ("thanks"), "ktuviot al yedei
 * ..." ("captions by ..."), or "Subtitles by the Amara.org community". For a
 * captioning app that's worse than showing nothing - the reader can't tell an
 * invented sentence from a real one. This applies the same three statistical
 * checks the reference Whisper implementation uses to decide a window is junk,
 * plus a short list of phrases the model is known to invent on silence in
 * Hebrew and English.
 *
 * On the home computer (faster-whisper 1.2 on CTranslate2 4.8, October 2026)
 * the large-v3 models, ivrit.ai's Turbo and large and OpenAI's Turbo, put the
 * no-speech probability at about 0.0001 on everything, pure silence included
 * (ivrit.ai's large is 99.9% sure silence is Hebrew), where OpenAI's Small
 * gives 0.87 on silence and 0.78 on kitchen noise; and they write a confident
 * "toda raba" on faint hiss. For its lines the checks below that read it never
 * fire, and what keeps an invented "thank you" off the screen is the voice
 * check before the model hears a line (the computer's speech gate; on the
 * phone, `VoiceEvidence`, and for a lone thank-you, how much voice it held,
 * [isUnvoicedPhrase]). The phone's engine never works it out: WhisperKit 1.1
 * reports 0 for every segment ("TODO: implement no speech prob" in its
 * TextDecoder), so on the phone too they never fire.
 */
class WhisperResultFilter(
    noSpeechThreshold: Float = 0.6f,
    logprobThreshold: Float = -1.0f,
    compressionRatioThreshold: Float = 2.4f,
    knownHallucinations: Set<String> = defaultKnownHallucinations,
    ambiguousHallucinations: Set<String> = defaultAmbiguousHallucinations,
    ambiguousNoSpeechThreshold: Float = 0.25f,
    ambiguousLogprobThreshold: Float = -0.9f,
    hallucinatedCreditPrefixes: List<String> = defaultCreditPrefixes,
    hallucinatedCreditLabels: List<String> = defaultCreditLabels,
    maximumCreditLineWords: Int = 7,
    maximumDashCreditLineWords: Int = 4,
) {
    var noSpeechThreshold: Float = noSpeechThreshold
    var logprobThreshold: Float = logprobThreshold
    var compressionRatioThreshold: Float = compressionRatioThreshold

    /** Stored pre-normalized so lookups compare like with like. */
    var knownHallucinations: Set<String> = knownHallucinations.map { normalize(it) }.toSet()

    /**
     * Phrases Whisper invents on noise that people also genuinely say in
     * conversation ("toda", "toda raba" - "thanks", "thank you very much").
     * Dropped only when the segment's own statistics look like noise, or the
     * voice model heard too little voice under it ([isUnvoicedPhrase]), never
     * just for being the phrase: missing a real "thank you" is its own kind of
     * wrong.
     */
    var ambiguousHallucinations: Set<String> = ambiguousHallucinations.map { normalize(it) }.toSet()

    /**
     * Above this no-speech probability an ambiguous phrase is treated as
     * invented. Real short speech sits far below it (and so does everything
     * the home computer's large models and the phone's engine send).
     */
    var ambiguousNoSpeechThreshold: Float = ambiguousNoSpeechThreshold

    /** Below this mean log-probability an ambiguous phrase is treated as a guess. */
    var ambiguousLogprobThreshold: Float = ambiguousLogprobThreshold

    /** See [isUnvoicedPhrase]. */
    var minimumPhraseVoicedChunks: Int = 2

    /**
     * Openings of the credit lines Whisper invents on silence, which come with
     * an arbitrary name attached ("ktuviot al yedei <name>" - "captions by
     * <name>"), so an exact-phrase list can't catch them.
     */
    var hallucinatedCreditPrefixes: List<String> = hallucinatedCreditPrefixes.map { normalize(it) }

    /** A credit prefix only condemns a short segment; a long one that happens to start the same way is someone actually talking. */
    var maximumCreditLineWords: Int = maximumCreditLineWords

    /**
     * A bare label followed by a dash is a real, common subtitle-community
     * sign-off, but a dash is also just how someone pauses mid-sentence after
     * saying that same word. Tighter than [maximumCreditLineWords] since a
     * genuine credit line is short.
     */
    var maximumDashCreditLineWords: Int = maximumDashCreditLineWords

    /**
     * Bare labels ("ktuviot" / "captions", "targum" / "translation") are
     * ordinary words too, so they only count as a credit when a colon follows,
     * as in "targum: Michal" ("translation: Michal").
     */
    var hallucinatedCreditLabels: List<String> = hallucinatedCreditLabels.map { it.lowercase(Locale.ROOT) }

    /**
     * The text worth showing from one decoding pass, with junk segments
     * dropped. Empty when nothing survives - the caller should then emit
     * nothing rather than a blank token.
     *
     * [echo] drops segments that are just the vocabulary prompt read back (see
     * [PromptEchoDetector]).
     */
    fun acceptedText(segments: List<WhisperSegmentSummary>, echo: PromptEchoDetector? = null): String = textOf(accepted(segments, echo))

    /**
     * What one pass of the phone's engine shows: [accepted] and the
     * [acceptedText] made of them, or nothing at all when that text is a
     * lone phrase heard in too little voice ([isUnvoicedPhrase]). Each
     * segment is checked once for both, where asking for the two
     * separately checks them twice; on a 40-word line a check is about
     * half a megabyte and, on a cheap phone, one to two milliseconds.
     */
    fun passText(segments: List<WhisperSegmentSummary>, voicedChunks: Int?, echo: PromptEchoDetector? = null): PassText {
        val kept = accepted(segments, echo)
        val text = textOf(kept)
        return if (isUnvoicedPhrase(text, voicedChunks, echo)) PassText.nothing else PassText(kept, text)
    }

    /** See [passText]. */
    class PassText(val segments: List<WhisperSegmentSummary>, val text: String) {
        companion object {
            val nothing = PassText(emptyList(), "")
        }
    }

    private fun textOf(accepted: List<WhisperSegmentSummary>): String {
        val joined = accepted
            .map { segment ->
                val text = withoutNotes(trimmingWhisperWhitespace(stripSpecialTokens(segment.text)))
                if (segment.compressionRatio > compressionRatioThreshold) repeatedSentence(text) ?: text else text
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return collapsingRepeats(joined)
    }

    /**
     * Whether [text] is nothing but one of the ambiguous phrases, heard in
     * fewer than [minimumPhraseVoicedChunks] of the voice model's 0.256 s
     * chunks. Household noise that gets past the voice check comes back as a
     * "toda raba" scored like a real one: 35 real thank-yous (five cut from
     * broadcast lines, each clean and at 10 to 0 dB of kitchen and living-room
     * noise) averaged -0.07 or better, nowhere near
     * [ambiguousLogprobThreshold]. The voice check is what tells them apart.
     * Through the phone's gate, 45 minutes of kitchen, living-room and laundry
     * noise (three microphones in each room) gave 7 lines that came back as a
     * thank-you, 5 of them with one voiced chunk; the real ones (0.35 to 0.42
     * s) had two or more in 312 of 320 placements across the chunk grid.
     * Without a count nothing is dropped.
     *
     * With [echo], the same goes for the whole prompt of a one-entry list
     * ([PromptEchoDetector.isWholePrompt]), which comes back on silence and
     * would then set off its alert. Not measured on names: the thank-you
     * numbers above are all there is.
     */
    fun isUnvoicedPhrase(text: String, voicedChunks: Int?, echo: PromptEchoDetector? = null): Boolean {
        if (voicedChunks == null || voicedChunks >= minimumPhraseVoicedChunks) return false
        return normalize(collapsingRepeats(text, maxRepeats = 1)) in ambiguousHallucinations ||
            echo?.isWholePrompt(text) == true
    }

    /**
     * The subset of [segments] that survive into [acceptedText], for a caller
     * that needs to derive something else (confidence, timing) from exactly
     * the content actually shown, not from segments that were rejected as
     * hallucinations or noise.
     *
     * The whole prompt of a one-entry list goes only when the model was unsure
     * of it or thought the window held no speech, like an ambiguous phrase (see
     * [PromptEchoDetector.isWholePrompt]); said with confidence it stays. With
     * other speech in the same pass it stays too: the name was most likely
     * said, as part of the line.
     */
    fun accepted(segments: List<WhisperSegmentSummary>, echo: PromptEchoDetector? = null): List<WhisperSegmentSummary> {
        val candidates = segments.filter { segment ->
            accepts(segment) && echo?.isEcho(stripSpecialTokens(segment.text)) != true
        }
        if (echo == null || !candidates.all { echo.isWholePrompt(stripSpecialTokens(it.text)) }) return candidates
        return candidates.filter { segment ->
            segment.noSpeechProb <= ambiguousNoSpeechThreshold && segment.avgLogprob >= ambiguousLogprobThreshold
        }
    }

    /**
     * The filter for speech in [languageCode]. In English a lone "you" is a
     * real answer as often as it is Whisper's filler for silence, and "music",
     * "applause" and "laughter" are words people say as well as its sound
     * tags, so there they only go when the model was also unsure, like "thank
     * you" (and in brackets, see [isBracketed]); in any other language they
     * can only be the filler.
     *
     * Speech in a language with its own list in
     * Sources/OzenKit/Resources/WhisperLanguageLines.json, shared with the
     * iPhone app, also drops what Whisper writes on silence in that language:
     * the community-subtitle credits it learned from Amara.org files and the
     * channel outros. A plain thank-you there is only ambiguous, since people
     * say it.
     */
    fun forSpeech(languageCode: String): WhisperResultFilter {
        val language = UILanguage.match(languageCode)
        var known = knownHallucinations
        var ambiguous = ambiguousHallucinations
        var prefixes = hallucinatedCreditPrefixes
        if (language == UILanguage.English) {
            val said = englishWordsAlsoSaid.map { normalize(it) }.filter { it in known }
            known = known - said
            ambiguous = ambiguous + said
        }
        WhisperLanguageLines.lines(language)?.let { lines ->
            known = known + lines.known.map { normalize(it) }
            ambiguous = ambiguous + lines.ambiguous.map { normalize(it) }
            prefixes = prefixes + lines.creditPrefixes.map { normalize(it) }
        }
        if (known == knownHallucinations && ambiguous == ambiguousHallucinations && prefixes == hallucinatedCreditPrefixes) return this
        val tuned = WhisperResultFilter(
            noSpeechThreshold = noSpeechThreshold,
            logprobThreshold = logprobThreshold,
            compressionRatioThreshold = compressionRatioThreshold,
            knownHallucinations = known,
            ambiguousHallucinations = ambiguous,
            ambiguousNoSpeechThreshold = ambiguousNoSpeechThreshold,
            ambiguousLogprobThreshold = ambiguousLogprobThreshold,
            hallucinatedCreditPrefixes = prefixes,
            hallucinatedCreditLabels = hallucinatedCreditLabels,
            maximumCreditLineWords = maximumCreditLineWords,
            maximumDashCreditLineWords = maximumDashCreditLineWords,
        )
        tuned.minimumPhraseVoicedChunks = minimumPhraseVoicedChunks
        return tuned
    }

    /**
     * "[Música]", "[音乐]", "(掌声)": Whisper writes a sound as a note in
     * brackets, in whatever language it hears. Real speech never comes in
     * square brackets, so any note there goes, alone or inside a line
     * ("[Music] Hello there" keeps "Hello there"); digits stay, so "[10:30]"
     * is kept. Round brackets also hold real words ("(I think)", "(HSBC)"),
     * so a part in them goes only when it is a known sound or outro, or when
     * the whole line is one bracketed part (a segment that is nothing but
     * "(...)" is a tag, as in [isBracketed]).
     */
    internal fun withoutNotes(text: String): String {
        if (text.none { it == '[' || it == '(' || it == '\uFF08' }) return text
        if (wholeRoundNote.matches(text.trim())) return ""
        var cleaned = squareNote.replace(text, "")
        cleaned = roundNote.replace(cleaned) { match ->
            val inner = normalize(match.groupValues[1])
            if (inner in knownHallucinations || inner in ambiguousHallucinations) "" else match.value
        }
        return multipleSpaces.replace(cleaned, " ").trim()
    }

    fun accepts(segment: WhisperSegmentSummary): Boolean {
        val text = withoutNotes(trimmingWhisperWhitespace(stripSpecialTokens(segment.text)))
        if (text.isEmpty()) return false
        // A lone "...", "-" or a music note is a well-known Whisper
        // hallucination on a quiet or noisy window that doesn't trip the
        // noSpeech/logprob thresholds together; normalize() already strips
        // exactly punctuation and symbols, so an empty result means no real
        // word survived.
        if (normalize(text).isEmpty()) return false
        // "toda raba toda raba": a decoding loop over a known phrase is judged
        // as the phrase itself.
        val once = collapsingRepeats(text, maxRepeats = 1)
        if (isKnownHallucination(text) || isKnownHallucination(once)) return false
        if (normalize(once) in ambiguousHallucinations &&
            (isBracketed(text) || segment.noSpeechProb > ambiguousNoSpeechThreshold || segment.avgLogprob < ambiguousLogprobThreshold)
        ) {
            return false
        }
        // The reference implementation only treats "no speech" as decisive when
        // the model was also unsure of its tokens; a confident transcript in a
        // window the VAD thought was quiet is kept.
        if (segment.noSpeechProb > noSpeechThreshold && segment.avgLogprob < logprobThreshold) return false
        // A sentence that merely opens like a credit ("Subtitles by default
        // are off") is kept when the model was sure of it; an unsure one is
        // the made-up credit it so often writes on quiet.
        if ((segment.noSpeechProb > ambiguousNoSpeechThreshold || segment.avgLogprob < ambiguousLogprobThreshold) &&
            sequenceOf({ text }, { once }, { repeatedSentence(text) ?: text }).any {
                it().let { line -> isCreditLine(line, normalize(line), anyRemainder = true) }
            }
        ) {
            return false
        }
        if (segment.compressionRatio > compressionRatioThreshold) {
            // Two or three copies of a sentence measured 2.5 to 3.6; the loops
            // Whisper wrote on household noise 11 to 25.
            if (segment.compressionRatio > 2 * compressionRatioThreshold) return false
            // "Come on, come on, come on, come on, let's go!" (a phrase said
            // over for emphasis, with at least two real words outside it, from
            // a model that was sure) is kept below, cut to three.
            // "di di di di!" (enough!) and nothing else: the phone's engine
            // scores repetition over its token numbers, where a word said four
            // or five times on its own already measures 2.5 to 3.3 (the
            // computer, over the letters, 1.2 to 1.7), and the pass came back
            // empty. Kept, it is cut to three like any repeat; a longer phrase
            // over again, or "toda toda toda...", is still a loop.
            val word = normalize(once)
            if (once != text && word.split(" ").count { it.isNotEmpty() } <= 2 && word !in ambiguousHallucinations) return true
            val beside = wordsBesideEmphasis(text)
            if (beside != null && beside >= 2 &&
                segment.noSpeechProb <= ambiguousNoSpeechThreshold &&
                segment.avgLogprob >= ambiguousLogprobThreshold &&
                !isKnownHallucination(collapsingRepeats(text))
            ) {
                return true
            }
            val sentence = repeatedSentence(text)
            if (sentence == null || isKnownHallucination(sentence)) return false
        }
        return true
    }

    /**
     * Case-, punctuation- and bracket-insensitive lookup, so "[toda raba]",
     * "toda raba." and "toda raba!" all match one entry.
     */
    fun isKnownHallucination(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized in knownHallucinations) return true
        return isCreditLine(text, normalized)
    }

    /**
     * "ktuviot: Yisrael Yisraeli" ("captions: Israel Israeli"), "Subtitles by
     * XYZ": a short segment that opens with a credit phrase, followed by a
     * separator (the normalizer already turned ":" into nothing) or a name.
     * Whole words only, so "ktuviotayim" or "targumim" never match.
     */
    internal fun isCreditLine(raw: String, normalized: String, anyRemainder: Boolean = false): Boolean {
        if (isUnspacedCreditLine(normalized)) return true
        val words = normalized.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > maximumCreditLineWords) return false
        val rawWords = raw.split(whisperSpaces).filter { normalize(it).isNotEmpty() }
        val byPhrase = hallucinatedCreditPrefixes.any { prefix ->
            val prefixWords = prefix.split(" ").filter { it.isNotEmpty() }
            words.size >= prefixWords.size && words.take(prefixWords.size) == prefixWords &&
                (anyRemainder || looksLikeCredit(rawWords.drop(prefixWords.size)))
        }
        if (byPhrase) return true

        // Without the stray U+200F and vowel points `normalize` drops for the
        // other checks, but with the colon and dash this one needs.
        val unmarked = HebrewText.stripNiqqud(raw).codePoints().toArray()
            .filter { Character.getType(it) != Character.FORMAT.toInt() }
        val cased = trimmingWhisperScalars(String(unmarked.toIntArray(), 0, unmarked.size)) {
            isWhisperSpaceOrNewline(it) || (it <= 0xFFFF && it.toChar() in "[](){}<>\"'״-–—")
        }
        val opening = cased.lowercase(Locale.ROOT)
        val casedGraphemes = graphemesOfWhisper(cased)
        return hallucinatedCreditLabels.any { label ->
            if (!opening.startsWith(label)) return@any false
            val afterLabel = casedGraphemes.drop(graphemesOfWhisper(label).size)
                .dropWhile { it.codePointAt(0).let(::isWhisperWhitespace) }
            val separator = afterLabel.firstOrNull() ?: return@any false
            val remainder = { afterLabel.drop(1).joinToString("").split(whisperSpaces).filter { normalize(it).isNotEmpty() } }
            if (separator == ":") return@any anyRemainder || looksLikeCredit(remainder())
            if (separator !in creditLabelDashes) return@any false
            words.size <= maximumDashCreditLineWords && (anyRemainder || looksLikeCredit(remainder()))
        }
    }

    /** Chinese and Japanese have no spaces, so a credit prefix there is matched by characters, on a line short enough to be a credit. */
    internal fun isUnspacedCreditLine(normalized: String): Boolean {
        val compact = normalized.filter { !it.isWhitespace() }
        if (compact.isEmpty() || compact.codePointCount(0, compact.length) > maximumUnspacedCreditLineCharacters) return false
        return hallucinatedCreditPrefixes.any { prefix ->
            ' ' !in prefix && prefix.codePoints().anyMatch(::isUnspacedScript) && compact.startsWith(prefix)
        }
    }

    override fun equals(other: Any?): Boolean = other is WhisperResultFilter &&
        noSpeechThreshold == other.noSpeechThreshold && logprobThreshold == other.logprobThreshold &&
        compressionRatioThreshold == other.compressionRatioThreshold &&
        knownHallucinations == other.knownHallucinations && ambiguousHallucinations == other.ambiguousHallucinations &&
        ambiguousNoSpeechThreshold == other.ambiguousNoSpeechThreshold &&
        ambiguousLogprobThreshold == other.ambiguousLogprobThreshold &&
        minimumPhraseVoicedChunks == other.minimumPhraseVoicedChunks &&
        hallucinatedCreditPrefixes == other.hallucinatedCreditPrefixes &&
        maximumCreditLineWords == other.maximumCreditLineWords &&
        maximumDashCreditLineWords == other.maximumDashCreditLineWords &&
        hallucinatedCreditLabels == other.hallucinatedCreditLabels

    override fun hashCode(): Int = listOf(
        noSpeechThreshold, logprobThreshold, compressionRatioThreshold, knownHallucinations, ambiguousHallucinations,
        ambiguousNoSpeechThreshold, ambiguousLogprobThreshold, minimumPhraseVoicedChunks, hallucinatedCreditPrefixes,
        maximumCreditLineWords, maximumDashCreditLineWords, hallucinatedCreditLabels,
    ).hashCode()

    companion object {
        /**
         * Each also in the short written form of "by" that subtitle files use
         * (ayin-gershayim-yod, which the normalizer reads without its mark),
         * and the "translated and synced by" credit of Hebrew subtitle sites.
         */
        val defaultCreditPrefixes: List<String> = listOf(
            "כתוביות על ידי", "תורגם על ידי", "תרגום על ידי", "תמלול על ידי", "תוכתב על ידי",
            "כתוביות ע״י", "תורגם ע״י", "תרגום ע״י", "תמלול ע״י",
            "תורגם וסונכרן", "סונכרן על ידי", "סונכרן ע״י",
            "subtitles by", "subtitled by", "translated by", "transcribed by", "captions by",
            "ترجمة نانسي قنقر", "تمت الترجمة بواسطة",
            "subtítulos realizados por la comunidad de amara.org",
            "sous-titres réalisés par la communauté d'amara.org",
            "untertitel der amara.org-community",
            "legendas pela comunidade amara.org",
            "субтитры сделал",
        )

        /** See [forSpeech]. */
        internal val englishWordsAlsoSaid: List<String> = listOf("you", "music", "applause", "laughter")

        val defaultCreditLabels: List<String> = listOf(
            "כתוביות", "תרגום", "תמלול", "הפקה", "עריכה", "סנכרון", "subtitles", "translation", "captions", "ترجمة",
        )

        /** Nobody says these to someone across a dinner table: they are broadcast credits and sound tags, always dropped. */
        val defaultKnownHallucinations: Set<String> = setOf(
            "תודה שצפיתם", "תודה על הצפייה", "תודה על הצפיה", "תודה שהאזנתם",
            "כתוביות", "תרגום", "תרגום וכתוביות", "כתוביות על ידי", "תרגום על ידי",
            "מחיאות כפיים",
            // English leaks through even with the language forced to Hebrew.
            "thanks for watching", "thank you for watching",
            "subtitles by the amara.org community", "subtitles by", "you",
            "music", "applause", "laughter",
            // Inherited from Whisper's YouTube-heavy training data: an outro
            // nobody in a real conversation says, and the model's own
            // uncertainty tag for audio it can't place (brackets and
            // parentheses are already gone by the time this is compared).
            "speaking in a foreign language", "please subscribe",
            "don't forget to subscribe", "like and subscribe",
            // The same outros as Hebrew YouTube and subtitle files word them.
            "תודה רבה שצפיתם", "תודה רבה לכם שצפיתם", "תודה שצפיתם בסרטון",
            "הירשמו לערוץ", "תירשמו לערוץ", "אל תשכחו להירשם לערוץ",
            "اشتركوا في القناة", "اشترك في القناة", "شكرا على المشاهدة", "شكرا لمشاهدتكم",
            "продолжение следует",
        )

        val defaultAmbiguousHallucinations: Set<String> = setOf(
            "תודה", "תודה רבה", "תודה לכם", "thank you",
            // The sound tags Whisper writes on music and laughter, but also
            // words people say, and Shira is a common girl's name: a call of
            // her name across the room was dropped however clearly it was
            // heard. In brackets they are still always tags (see
            // [isBracketed]).
            "מוזיקה", "שירה", "צחוק",
            // Unlike the subscribe lines above, a real farewell could
            // plausibly sound like this, so it only drops when the model was
            // also unsure of itself.
            "see you next time", "see you in the next video",
            "נתראה בסרטון הבא", "צפייה מהנה", "צפיה מהנה",
            "شكرا", "شكرا لكم",
            "gracias", "merci", "danke", "obrigado", "спасибо",
        )

        /**
         * A decoding loop that stays short enough to pass the compression
         * check still puts "lavo lavo lavo lavo lavo lavo" ("come come
         * come...") on screen. Any word or short phrase repeated back to back
         * more than [maxRepeats] times is cut down to that many: people do say
         * "lo, lo, lo" ("no, no, no"), but nobody says it six times. The kept
         * copies are the first ones and the last, so the sentence keeps its
         * closing punctuation.
         *
         * Digits and counting words may repeat up to [maxNumberRepeats] times,
         * because phone numbers, codes and amounts are read out one digit at a
         * time ("zero five zero five five five five five"), and a number shown
         * shorter than it was said is wrong with no sign that it is. Every
         * other word is still cut to [maxRepeats], and a caller asking for a
         * single copy gets a single copy of a number as well.
         *
         * Text with nothing to collapse comes back exactly as it was.
         */
        fun collapsingRepeats(text: String, maxRepeats: Int = 3, maxPhraseWords: Int = 4, maxNumberRepeats: Int = 10): String =
            collapse(text, maxRepeats, maxPhraseWords, maxNumberRepeats, Int.MAX_VALUE).text

        /**
         * The words of [text] outside every phrase said three or more times
         * back to back, once each phrase is cut to one copy: "Come on, come
         * on, come on, come on, let's go!" leaves 2 ("let's go"); null when
         * no phrase is said that often.
         */
        internal fun wordsBesideEmphasis(text: String): Int? {
            val result = collapse(text, 1, 4, 10, 3)
            return if (result.emphasized > 0) result.words - result.emphasized else null
        }

        private class Collapsed(val text: String, val words: Int, val emphasized: Int)

        private fun collapse(text: String, maxRepeats: Int, maxPhraseWords: Int, maxNumberRepeats: Int, emphasisFrom: Int): Collapsed {
            var words = splitOnWhisperWhitespace(text)
            var changed = false
            if (maxRepeats >= 1 && emphasisFrom == Int.MAX_VALUE) {
                words = words.map { word ->
                    val shortened = collapsingUnspacedRuns(word, maxRepeats, maxNumberRepeats)
                    if (shortened != null) changed = true
                    shortened ?: word
                }
            }
            var emphasis = List(words.size) { false }
            if (maxRepeats < 1 || words.size <= maxRepeats) {
                return Collapsed(if (changed) words.joinToString(" ") else text, words.count { normalize(it).isNotEmpty() }, 0)
            }
            val numberLimit = if (maxRepeats > 1) maxOf(maxRepeats, maxNumberRepeats) else maxRepeats
            for (phraseLength in 1..maxPhraseWords) {
                val keys = words.map { normalize(it) }
                val result = mutableListOf<String>()
                val resultEmphasis = mutableListOf<Boolean>()
                var index = 0
                while (index < words.size) {
                    val phraseEnd = index + phraseLength
                    if (phraseEnd > words.size || keys.subList(index, phraseEnd).all { it.isEmpty() }) {
                        result.add(words[index])
                        resultEmphasis.add(emphasis[index])
                        index += 1
                        continue
                    }
                    val phrase = keys.subList(index, phraseEnd)
                    var repeats = 1
                    while (index + (repeats + 1) * phraseLength <= words.size &&
                        keys.subList(index + repeats * phraseLength, index + (repeats + 1) * phraseLength) == phrase
                    ) {
                        repeats += 1
                    }
                    val limit = if (repeats > maxRepeats && phrase.all(::isSpokenNumber)) numberLimit else maxRepeats
                    if (repeats > limit) {
                        result.addAll(words.subList(index, index + (limit - 1) * phraseLength))
                        val lastStart = index + (repeats - 1) * phraseLength
                        result.addAll(words.subList(lastStart, lastStart + phraseLength))
                        repeat(limit * phraseLength) { resultEmphasis.add(repeats >= emphasisFrom) }
                        index += repeats * phraseLength
                        changed = true
                    } else {
                        result.add(words[index])
                        resultEmphasis.add(emphasis[index])
                        index += 1
                    }
                }
                words = result
                emphasis = resultEmphasis
            }
            val spoken = words.indices.filter { normalize(words[it]).isNotEmpty() }
            return Collapsed(if (changed) words.joinToString(" ") else text, spoken.size, spoken.count { emphasis[it] })
        }

        /**
         * A loop in a script written without spaces ("好的好的好的好的好的好的",
         * "はいはいはいはいはい") has no words to split on, so [collapse] looks inside
         * each word for a unit of one to eight characters said back to back more
         * than [maxRepeats] times and cuts it to that many, like the spaced
         * path. A unit may carry CJK or full-width punctuation ("好的，"), but
         * needs at least one character of such a script; the last copy may lack
         * its trailing punctuation ("好的，好的，好的，好的"). A single character
         * is cut only past six copies, because "谢谢谢谢" and "哈哈哈" are
         * ordinary speech. Numerals may repeat up to [maxNumberRepeats] times,
         * like spoken digits. Null when nothing is cut.
         */
        private fun collapsingUnspacedRuns(word: String, maxRepeats: Int, maxNumberRepeats: Int): String? {
            val points = word.codePoints().toArray()
            if (points.size <= maxRepeats || points.none(::isRunScript)) return null
            var current = points.toList()
            var changed = false
            for (unit in 1..8) {
                val result = ArrayList<Int>()
                var index = 0
                while (index < current.size) {
                    val end = index + unit
                    if (end > current.size ||
                        !current.subList(index, end).all { isRunScript(it) || isRunPunctuation(it) } ||
                        current.subList(index, end).none(::isRunScript) ||
                        (unit > 1 && current.subList(index, end).toSet().size == 1)
                    ) {
                        result.add(current[index])
                        index += 1
                        continue
                    }
                    val chunk = current.subList(index, end).toList()
                    var repeats = 1
                    while (index + (repeats + 1) * unit <= current.size &&
                        current.subList(index + repeats * unit, index + (repeats + 1) * unit) == chunk
                    ) {
                        repeats += 1
                    }
                    val core = chunk.dropLastWhile { !isRunScript(it) }
                    val tailStart = index + repeats * unit
                    val hasTail = core.size < unit && tailStart + core.size <= current.size &&
                        current.subList(tailStart, tailStart + core.size) == core
                    val copies = repeats + if (hasTail) 1 else 0
                    var limit = if (unit == 1) maxOf(maxRepeats, 6) else maxRepeats
                    if (maxRepeats > 1 && chunk.filter(::isRunScript).all(::isUnspacedNumeral)) limit = maxOf(limit, maxNumberRepeats)
                    if (copies > limit) {
                        repeat(if (hasTail) limit - 1 else limit) { result.addAll(chunk) }
                        if (hasTail) result.addAll(core)
                        index = tailStart + if (hasTail) core.size else 0
                        changed = true
                    } else {
                        result.add(current[index])
                        index += 1
                    }
                }
                current = result
            }
            if (!changed) return null
            return String(current.toIntArray(), 0, current.size)
        }

        private fun isRunPunctuation(value: Int): Boolean = value in 0x3000..0x303F || value in 0xFF01..0xFF0F ||
            value in 0xFF1A..0xFF20 || value in 0xFF5B..0xFF65 || value == 0x2026

        private fun isRunScript(value: Int): Boolean = value in 0x3400..0x4DBF || value in 0x4E00..0x9FFF ||
            value in 0xF900..0xFAFF || value in 0x20000..0x2FA1F || value in 0x30000..0x3134F ||
            value in 0x3041..0x309F || value in 0x30A0..0x30FF || value in 0x31F0..0x31FF || value in 0xFF66..0xFF9F ||
            value in 0x1100..0x11FF || value in 0x3130..0x318F || value in 0xAC00..0xD7AF ||
            value in 0x0E01..0x0EFF || value in 0x1000..0x109F || value in 0x1780..0x17FF

        private fun isUnspacedNumeral(value: Int): Boolean = "零〇一二三四五六七八九十百千万萬亿億两兩".any { it.code == value }

        /**
         * Whether a word, already through [normalize], is a number: written in
         * digits (in any script; [normalize] turns the hyphen in "5-5" into a
         * space, so spaces are allowed) or a counting word: Hebrew, with or
         * without prefixes attached, or one from any language with a word list (there is no
         * language to go by here).
         */
        private fun isSpokenNumber(key: String): Boolean {
            if (key.isEmpty()) return false
            return key.codePoints().allMatch { it == ' '.code || isNumberCodePoint(it) } || NumberEmphasis.isNumberWord(key) ||
                key.split(' ').all { NumberWordProfile.isCountingWordInAnyLanguage(it) }
        }

        private fun isNumberCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }

        /**
         * Someone saying a sentence again with no pause, as people do for a
         * listener who didn't catch it, comes back as the sentence written
         * twice, and that alone takes the compression ratio past 2.4: a Hebrew
         * FLEURS sentence of ten words or more did 90% of the time, and the
         * whole line was dropped (7 of 24 finals and 9 of 24 live passes, two
         * speakers 0.3 s apart, turbo and large-v3). Two or three back-to-back
         * copies of one sentence of at least [minimumWords] words, alike word
         * for word to [minimumSimilarity], are that sentence, shown once. A
         * decoding loop runs to more copies or over fewer words, and stays
         * dropped.
         */
        fun repeatedSentence(text: String, minimumWords: Int = 5, minimumSimilarity: Double = 0.7): String? {
            val tokens = splitOnWhisperWhitespace(text).filter { normalize(it).isNotEmpty() }
            val keys = tokens.map { normalize(it) }
            var bestSimilarity = 0.0
            var bestSize = -1
            for (copies in 2..3) {
                val average = keys.size / copies
                if (average < minimumWords) continue
                for (size in maxOf(minimumWords, average - 2)..(average + 2)) {
                    if (size >= keys.size) continue
                    val similarity = copySimilarity(keys, size, copies) ?: continue
                    if (similarity >= minimumSimilarity && similarity > (if (bestSize >= 0) bestSimilarity else 0.0)) {
                        bestSimilarity = similarity
                        bestSize = size
                    }
                }
            }
            if (bestSize < 0) return null
            val sentence = tokens.subList(0, bestSize).joinToString(" ")
            // Two copies of two copies is a loop of four, and a "sentence" that
            // is itself a word or short phrase over again is a loop cut in half
            // ("pak pak pak..." on dripping water). One that only doubles a word
            // of its own ("lat lat", slowly; "ken ken", yes) is still a
            // sentence: refusing any doubled word dropped the line.
            val unlooped = splitOnWhisperWhitespace(collapsingRepeats(sentence, maxRepeats = 1))
            if (unlooped.size < minimumWords || repeatedSentence(sentence, minimumWords, minimumSimilarity) != null) return null
            return sentence
        }

        /**
         * How alike the least alike later copy is to the first [size] words,
         * each copy free to run a few words longer or shorter; null when more
         * than a word is left over after the last one.
         */
        internal fun copySimilarity(keys: List<String>, size: Int, copies: Int): Double? {
            val first = keys.subList(0, size)
            var start = size
            var least = 1.0
            for (copy in 1 until copies) {
                var bestSimilarity = 0.0
                var bestLength = -1
                for (length in maxOf(1, size - 3)..(size + 3)) {
                    if (start + length > keys.size) continue
                    val similarity = wordSimilarity(first, keys.subList(start, start + length))
                    if (similarity > (if (bestLength >= 0) bestSimilarity else -1.0)) {
                        bestSimilarity = similarity
                        bestLength = length
                    }
                }
                if (bestLength < 0) return null
                least = minOf(least, bestSimilarity)
                start += bestLength
            }
            return if (keys.size - start <= 1) least else null
        }

        /** Twice the longest run of words the two share in order, over both lengths. */
        internal fun wordSimilarity(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            var previous = IntArray(b.size + 1)
            for (word in a) {
                val current = IntArray(b.size + 1)
                for ((index, other) in b.withIndex()) {
                    current[index + 1] = if (word == other) previous[index] + 1 else maxOf(previous[index + 1], current[index])
                }
                previous = current
            }
            return (2 * previous[b.size]).toDouble() / (a.size + b.size).toDouble()
        }

        internal const val maximumUnspacedCreditLineCharacters = 24
        private val squareNote = Regex("\\[[\\p{L}\\p{M} _-]*\\]")
        private val wholeRoundNote = Regex("^[(\\uFF08][^()\\uFF08\\uFF09]*[)\\uFF09][.!\\u3002\\uFF01]*$")
        private val roundNote = Regex("[(\\uFF08]([^()\\uFF08\\uFF09]*)[)\\uFF09]")
        private val multipleSpaces = Regex("\\s{2,}")

        internal fun isUnspacedScript(codePoint: Int): Boolean =
            codePoint in 0x3040..0x30FF || codePoint in 0x3400..0x9FFF || codePoint in 0xF900..0xFAFF || codePoint in 0x0E00..0x0E7F

        /** Bracketed or parenthesised labels such as a sound tag: nobody's speech comes out in brackets. */
        internal fun isBracketed(text: String): Boolean {
            val graphemes = graphemesOfWhisper(text)
            val first = graphemes.firstOrNull() ?: return false
            val last = graphemes.last()
            return (first == "[" && last == "]") || (first == "(" && last == ")")
        }

        private val whisperSpaces = Regex("[\\p{Z}\\s\\u0085]+")

        internal val nameConnectors: Set<String> = setOf(
            "and", "&", "de", "del", "la", "van", "von", "der", "den", "di", "da", "el", "al", "bin", "y", "e", "du", "des", "le", "ten", "ter",
        )

        internal val leadingArticles: Set<String> = setOf("the", "la", "le", "les", "el", "los", "las", "die", "der", "das")

        internal val communityWords: Set<String> = setOf(
            "community", "comunidad", "comunidade", "communauté", "gemeinschaft", "сообщество", "спільнота",
        )

        private fun isPunctuationOrSymbol(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        }

        private fun isAddress(word: String): Boolean {
            val lower = word.lowercase(Locale.ROOT)
            if ('@' in word || lower.startsWith("www.") || lower.startsWith("http")) return true
            return word.indices.any {
                word[it] == '.' && it > 0 && it < word.length - 1 &&
                    Character.isLetter(word.codePointBefore(it)) && Character.isLetter(word.codePointAt(it + 1))
            }
        }

        /**
         * What follows a credit opening decides whether it is a credit or a
         * person talking about captions ("Subtitles by default are off",
         * "Subtitles: on or off?"). A credit names someone or something: a
         * capitalised name, an address ("amara.org", "@name"), or "the
         * community", with nothing but such words in the line. Words in a script without letter case (Hebrew, Arabic,
         * Chinese, Hindi...) show no such difference, so they count as a name
         * and a line there is dropped as before, as is a bare year. Nothing
         * after the opening ("Subtitles by") is a credit too.
         */
        internal fun looksLikeCredit(remainder: List<String>): Boolean {
            val words = remainder.map { raw ->
                val word = run {
                    val cps = raw.codePoints().toArray().toList()
                    val start = cps.indexOfFirst { !isPunctuationOrSymbol(it) }
                    val end = cps.indexOfLast { !isPunctuationOrSymbol(it) }
                    if (start < 0) "" else String(cps.subList(start, end + 1).toIntArray(), 0, end - start + 1)
                }
                raw to word
            }.filter { it.second.isNotEmpty() }
            if (words.isEmpty()) return true
            return words.withIndex().all { (index, pair) ->
                val (raw, word) = pair
                val plain = normalize(word)
                if (isAddress(raw) || plain in communityWords) return@all true
                if (index == 0 && plain in leadingArticles) return@all true
                if (plain in nameConnectors) return@all true
                val letter = word.codePoints().toArray().firstOrNull { Character.isLetter(it) }
                    ?: return@all word.codePoints().toArray().any { Character.isDigit(it) }
                !Character.isLowerCase(letter)
            }
        }

        /**
         * A dash reads as a separator only for the tighter, dash-specific word
         * cap above - a colon needs no such caution since real speech almost
         * never opens with "word:".
         */
        internal val creditLabelDashes: Set<String> = setOf("-", "–", "—")

        /**
         * Removes Whisper's control tokens (`<|startoftranscript|>`, `<|he|>`,
         * `<|0.00|>` timestamps, ...) that leak into segment text depending on
         * decoding options. Belt and braces: the engine asks for them to be
         * skipped, and this makes sure none reach the screen.
         */
        fun stripSpecialTokens(text: String): String {
            val result = StringBuilder()
            var index = 0
            while (index < text.length) {
                if (text.startsWith("<|", index)) {
                    val close = text.indexOf("|>", index)
                    if (close >= 0) {
                        index = close + 2
                        continue
                    }
                }
                result.append(text[index])
                index += 1
            }
            return result.toString()
        }

        /**
         * The words of [normalize]`(text)`, read from the front one at a
         * time, each normalized by itself. Normalizing never looks past a
         * space or a word joiner (a capital sigma's final form looks no
         * further than its own word), so these are the same words; a caller
         * that needs only the first few no longer normalizes a whole line
         * that comes again at every word.
         */
        internal fun normalizedWords(text: String): Iterator<String> = iterator {
            var start = -1
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                if (isWhisperWhitespace(codePoint) || HebrewText.isWordJoiner(codePoint)) {
                    if (start >= 0) normalize(text.substring(start, index)).let { if (it.isNotEmpty()) yield(it) }
                    start = -1
                } else if (start < 0) {
                    start = index
                }
                index += Character.charCount(codePoint)
            }
            if (start >= 0) normalize(text.substring(start)).let { if (it.isNotEmpty()) yield(it) }
        }

        internal fun normalize(text: String): String {
            // Also strips Unicode format characters (bidi marks, zero-width
            // joiners): WhisperKit's Hebrew/Arabic output regularly carries a
            // stray U+200F alongside otherwise-exact hallucinated text, which
            // survived punctuation/symbol stripping alone and made every
            // known-hallucination and credit-line comparison in this file miss
            // what would otherwise be an exact match.
            //
            // Niqqud (Hebrew vowel points) is stripped the same way
            // `HebrewText.normalize` strips it, via the same
            // `separatingJoiners` + `stripNiqqud` pair. Apple's on-device
            // recognizer and home-server Hebrew models occasionally emit
            // pointed text; without this, a pointed silence hallucination would
            // never match `knownHallucinations`, which is stored unpointed, and
            // would reach the screen instead of being dropped.
            val withoutNiqqud = HebrewText.stripNiqqud(HebrewText.separatingJoiners(text))
            // Arabic vowel marks and the stretching tatweel: Whisper's lines
            // come with and without them.
            val unmarked = StringBuilder(withoutNiqqud.length)
            withoutNiqqud.codePoints().forEach {
                if (it !in 0x064B..0x065F && it != 0x0670 && it != 0x0640) unmarked.appendCodePoint(it)
            }
            val stripped = StringBuilder(unmarked.length)
            unmarked.codePoints().forEach {
                if (!isWhisperPunctuation(it) && !isWhisperSymbol(it) && Character.getType(it) != Character.FORMAT.toInt()) {
                    stripped.appendCodePoint(it)
                }
            }
            val joined = splitOnWhisperWhitespace(stripped.toString().lowercase(Locale.ROOT)).joinToString(" ")
            return Normalizer.normalize(joined, Normalizer.Form.NFC)
        }
    }
}
