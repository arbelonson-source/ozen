package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One word or phrase the reader has asked to be alerted about: her own name,
 * a grandchild's name, "ambulans" ("ambulance"), "trufa" ("medicine"). Kept
 * as plain data (no matching logic here) so it can be stored as JSON and
 * edited from a simple list screen.
 */
data class KeywordAlert(
    val id: UUID = UUID.randomUUID(),
    var phrase: String,
    var isEnabled: Boolean = true,
) {
    fun toJson(): String = buildJsonObject {
        put("id", id.toString().uppercase(Locale.ROOT))
        put("phrase", phrase)
        put("isEnabled", isEnabled)
    }.toString()

    companion object {
        /**
         * Tolerant like the settings: an alert saved before the enable/disable
         * toggle existed has no `isEnabled` key at all, and that must not be
         * treated as "off" - it behaves like a freshly created alert, which
         * defaults to on.
         */
        fun fromJson(json: String): KeywordAlert {
            val container = Json.parseToJsonElement(json).jsonObject
            val id = container["id"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { UUID.fromString(it) }
                ?: throw IllegalArgumentException("a keyword alert needs an id")
            val phrase = container["phrase"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("a keyword alert needs a phrase")
            val enabled = (container["isEnabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            return KeywordAlert(id, phrase, enabled ?: true)
        }
    }
}

/**
 * One place a keyword was found in a caption: which alert fired, the alert's
 * own phrase (for display), the text as it actually appeared in the caption
 * (which may carry an attached prefix, e.g. "le-savta" - "to grandma"), and
 * the index of its first word within the caption's word list, used by
 * [KeywordAlertDeduplicator] to tell a repeated partial update from a
 * genuinely new occurrence.
 */
data class KeywordMatch(
    var alertID: UUID,
    var phrase: String,
    var matchedText: String,
    var wordIndex: Int,
)

private fun graphemesOf(text: String): List<String> {
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

private fun isKeywordWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
        codePoint == 0x205F || codePoint == 0x3000

private fun splitOnKeywordWhitespace(text: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    text.codePoints().forEach { codePoint ->
        if (isKeywordWhitespace(codePoint)) {
            if (current.isNotEmpty()) words.add(current.toString())
            current.setLength(0)
        } else {
            current.appendCodePoint(codePoint)
        }
    }
    if (current.isNotEmpty()) words.add(current.toString())
    return words
}

private fun isKeywordPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

private fun isKeywordSymbol(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
    else -> false
}

private fun isKeywordVariationSelector(codePoint: Int): Boolean =
    codePoint in 0x180B..0x180D || codePoint == 0x180F || codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

private fun String.filteringScalars(keep: (Int) -> Boolean): String {
    val result = StringBuilder(length)
    codePoints().forEach { if (keep(it)) result.appendCodePoint(it) }
    return result.toString()
}

/**
 * Hebrew-aware text normalization shared by the keyword matcher. Kept as a
 * namespace (no stored state) since every function here is a pure
 * transformation of a string.
 */
object HebrewText {
    /**
     * Hebrew niqqud (vowel points) and cantillation marks occupy the Unicode
     * block U+0591...U+05C7. Whisper and Apple's recognizer never emit them,
     * but a keyword phrase typed or pasted by the user might carry them, so
     * both sides of a comparison are stripped down to consonants first.
     *
     * The same block also holds four Hebrew punctuation marks that are not
     * vowels at all: Maqaf (U+05BE, the Hebrew hyphen), Paseq (U+05C0), Sof
     * Pasuq (U+05C3) and Nun Hafukha (U+05C6). Only the scalars in this block
     * that are a nonspacing mark (Unicode category Mn) are removed, so a word
     * joined by a Maqaf is left for [separatingJoiners] to turn into a space
     * rather than silently vanishing and fusing the two halves together.
     */
    fun stripNiqqud(text: String): String = text.filteringScalars {
        !(it in 0x0591..0x05C7 && Character.getType(it) == Character.NON_SPACING_MARK.toInt())
    }

    /**
     * Invisible marks that only steer which way text runs: the left-to-right
     * and right-to-left marks, the embeddings, overrides and isolates, and the
     * Arabic letter mark. ivrit.ai's Hebrew model, trained on subtitles,
     * starts some lines with one; glued to the first word, it made that word
     * a different string, so her name at the start of a line never raised its
     * alert.
     */
    internal val directionMarks: Set<Int> = setOf(
        0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x061C,
    )

    fun removingDirectionMarks(text: String): String {
        if (text.codePoints().noneMatch { it in directionMarks }) return text
        return text.filteringScalars { it !in directionMarks }
    }

    /** Hyphen, Hebrew maqaf, hyphen variants, en and em dashes, slash. */
    internal val wordJoiners: Set<Int> = setOf('-'.code, 0x05BE, 0x2010, 0x2011, 0x2013, 0x2014, '/'.code)

    /**
     * Turns characters that join two words into spaces, so a hyphenated
     * "Tel-Aviv", the same with a maqaf in place of the hyphen, and a plain
     * "Tel Aviv" all split into the same two words. Must run before
     * [stripNiqqud]: the maqaf sits inside the niqqud block and would
     * otherwise vanish and glue the words together.
     */
    fun separatingJoiners(text: String): String {
        val result = StringBuilder(text.length)
        text.codePoints().forEach { result.appendCodePoint(if (it in wordJoiners) ' '.code else it) }
        return result.toString()
    }

    /**
     * The canonical form every keyword comparison is done in: niqqud gone,
     * punctuation and symbols gone, case folded, whitespace collapsed to
     * single spaces. Mirrors `WhisperResultFilter.normalize` with the added
     * niqqud pass Hebrew needs.
     *
     * Other invisible characters go too: a zero-width space, joiner or soft
     * hyphen pasted along with a name from a web page or a contact, and emoji
     * variation selectors, made the word a different string.
     *
     * The result is composed (NFC), so two spellings that Swift's string
     * comparison treats as equal compare equal here too.
     */
    fun normalize(text: String): String {
        val withoutNiqqud = stripNiqqud(separatingJoiners(removingDirectionMarks(text)))
        val stripped = withoutNiqqud.filteringScalars {
            !isKeywordPunctuation(it) && !isKeywordSymbol(it) &&
                Character.getType(it) != Character.FORMAT.toInt() && !isKeywordVariationSelector(it)
        }
        val joined = splitOnKeywordWhitespace(stripped.lowercase(Locale.ROOT)).joinToString(" ")
        return Normalizer.normalize(joined, Normalizer.Form.NFC)
    }

    /**
     * A word typed only in another alphabet ("Sarah", a Cyrillic name) while
     * the captions come out in Hebrew letters: its alert stays silent, so the
     * screens that add one say so. Digits alone are left alone.
     */
    fun isInOtherLetters(phrase: String, captionLanguage: String): Boolean {
        if (!captionLanguage.startsWith("he")) return false
        val hasHebrew = phrase.codePoints().anyMatch { it in 0x05D0..0x05F2 || it in 0xFB1D..0xFB4F }
        return !hasHebrew && phrase.codePoints().anyMatch { Character.isAlphabetic(it) }
    }

    /**
     * [normalize], split into individual words. Used for a phrase, where only
     * the resulting word list matters, not each word's position in the
     * original phrase text.
     */
    fun words(text: String): List<String> = normalize(text).split(" ").filter { it.isNotEmpty() }

    /**
     * Hebrew attaches single-letter prepositions and conjunctions directly to
     * the following word with no space - vav ("and"), he ("the"), bet
     * ("in/with"), lamed ("to"), mem ("from"), shin ("that"), kaf ("as") - and
     * these stack, e.g. "vichshe" ("and when"). This list is a heuristic tuned
     * for the prefixes that show up before names and everyday nouns in speech,
     * not a morphological analyzer: it will miss rarer stackings and, in
     * principle, could strip a letter that happens to start the word itself,
     * but that trade-off is the right one for an alert that must not stay
     * silent just because a caption said "le-savta" instead of "savta".
     *
     * Deliberately missing: bet, kaf and lamed swallowing the article, as in
     * "le-rofe" ("to the doctor"). Matching that against a phrase that starts
     * with he would also fire the name "Hila" on every "layla" ("night") and
     * "Hillel" on every "klal" ("rule"), so a phrase saved as "ha-rofe" ("the
     * doctor") catches "ha-rofe" and "she-ha-rofe" but not "le-rofe"; saved as
     * "rofe" ("doctor") it catches all of them.
     */
    val attachedPrefixes: Set<String> = setOf(
        "ו", "ה", "ב", "ל", "מ", "ש", "כ",
        "וה", "וב", "ול", "ומ", "וש", "וכ",
        "שה", "שב", "של", "שמ",
        "כש", "בה", "לה", "מה",
        "וכש", "ולכ", "ושה",
        // "when the", "and when the", "and from the", "that from the":
        // "kshe-ha-rofe amar" ("when the doctor said") is how a doctor's
        // visit gets retold.
        "כשה", "וכשה", "ומה", "שמה",
    )

    /**
     * True if [word] is [stem] on its own, or [stem] with one of the attached
     * prefixes glued to the front. Deliberately does not touch the end of the
     * word, so a suffix change ("savta'ot") is correctly left unmatched.
     */
    fun stripAttachedPrefix(word: String, stem: String): Boolean {
        if (word == stem) return true
        if (everydayWordsNotNames[stem]?.contains(word) == true) return false
        return attachedPrefixes.any { word == it + stem }
    }

    /**
     * Short names that, with a prefix glued on, spell an everyday word far
     * more often than they mean the person: an alert for "Li" fired on every
     * "sheli" ("mine") and "bli" ("without"), "Ben" on every "lavan"
     * ("white"). Kept as a closed list per name, like [affectionateVariants],
     * so the prefix rule still finds "and Tal" or "to Dan" everywhere else.
     */
    val everydayWordsNotNames: Map<String, Set<String>> = mapOf(
        "לי" to setOf("שלי", "בלי", "כלי", "ולי"),
        "בן" to setOf("לבן", "הבן"),
        "טל" to setOf("בטל"),
        "חן" to setOf("לחן"),
        "גל" to setOf("הגל"),
        "ים" to setOf("הים", "לים", "בים"),
        "גיל" to setOf("בגיל", "הגיל", "לגיל"),
        // Common names of her generation, and the words they hide in:
        // a job, kosher and the minister; a camp; the uncle; Sharon.
        "שרה" to setOf("משרה", "ומשרה", "כשרה", "השרה", "והשרה", "שהשרה"),
        "חנה" to setOf("מחנה", "ומחנה"),
        "דוד" to setOf("הדוד", "והדוד", "שהדוד", "כשהדוד"),
        "רון" to setOf("שרון", "ושרון"),
        // Found in real speech the home computer wrote: "pains of" (as in
        // headaches), "full", "buildings of"; and a name never takes "the".
        "אבי" to setOf("כאבי", "וכאבי"),
        "לאה" to setOf("מלאה", "ומלאה", "שמלאה"),
        "בני" to setOf("מבני", "ומבני"),
        "מרים" to setOf("המרים", "והמרים", "שהמרים"),
        "אור" to setOf("האור", "באור", "לאור"),
        "שיר" to setOf("השיר", "לשיר", "בשיר"),
        "אביב" to setOf("באביב", "האביב"),
        // A doctor's abbreviated title after the article spells the name
        // Hadar, which is said far more often than "the Dr.".
        "דר" to setOf("הדר", "והדר", "שהדר", "להדר", "בהדר", "מהדר", "כשהדר", "וכשהדר", "ושהדר", "שמהדר"),
    )

    /**
     * The Hebrew geresh, and the plain and typographic apostrophes a
     * transcript sometimes uses in its place, when they mark a diminutive
     * nickname ending glued straight onto a word with no space -
     * "savta'le" ("grandma," affectionately). [normalize] treats this the same
     * as any other punctuation and drops it, which would make it
     * indistinguishable from a genuine suffix change like "savta'ot"
     * ("grandmas") once every letter after the stem is kept; a diminutive
     * ending must instead be told apart by the marker actually being there in
     * the caption.
     */
    internal val diminutiveMarkers: Set<Int> = setOf(0x05F3, '\''.code, 0x2019)

    /**
     * [normalize], but cut at the first diminutive marker instead of dropping
     * it, so "savta'le" produces "savta" (the stem alone, with whatever
     * nickname ending followed the marker discarded) rather than the
     * un-tellable-apart "savtale". Empty when [word] carries no such marker at
     * all. The same ending is as often written with the mark after its lamed
     * ("savtal'e"), so a lamed right before a mark that only he follows also
     * gives the stem without it; the stem with it stays, for a name that ends
     * in lamed ("Michal'e").
     */
    internal fun diminutiveCores(word: String): List<String> {
        val scalars = word.codePoints().toArray()
        // A mark opening the word is a quote around it ('savta'le'), not the
        // one before a nickname ending.
        val markerIndex = (1 until scalars.size).firstOrNull { scalars[it] in diminutiveMarkers } ?: return emptyList()
        val core = normalize(String(scalars, 0, markerIndex))
        val ending = normalize(String(scalars, markerIndex + 1, scalars.size - markerIndex - 1))
        val coreGraphemes = graphemesOf(core)
        if (ending != "ה" || coreGraphemes.size <= 2 || coreGraphemes.last() != "ל") return listOf(core)
        return listOf(core, coreGraphemes.dropLast(1).joinToString(""))
    }

    /**
     * A short, closed list of affectionate nicknames and alternate spellings
     * for the everyday address words a reader's alerts are mostly built from -
     * "savta" ("grandma") and "ima"/"ama" ("mom") - that do not decompose into
     * the stem plus a marked ending at all, so the geresh rule above cannot
     * catch them. Kept as an explicit word-to-word list, never a general
     * suffix or phonetic rule: a general rule would risk matching an
     * unrelated short configured word against some other short word that
     * happens to share an ending, which a fixed list tied to the specific
     * configured word cannot do.
     */
    val affectionateVariants: Map<String, Set<String>> = mapOf(
        "סבתא" to setOf("סבתוש", "סבתושה"),
        "אמא" to setOf("אימא"),
        "אימא" to setOf("אמא"),
    )

    /**
     * The plural, feminine and construct forms of the everyday words the app
     * suggests as alerts. Endings are otherwise never matched (see
     * [stripAttachedPrefix]), which left "did you take the medicines?" and
     * "the doctor (she) said" silent for alerts on "medicine" and "doctor". A
     * closed list for the same reason as [affectionateVariants]. A doctor's
     * title is also said and written both in full and as the abbreviation
     * (which normalizes to "dr"), so an alert on "Dr. Cohen" saved one way
     * stayed silent when the caption used the other.
     */
    val otherForms: Map<String, Set<String>> = mapOf(
        "תרופה" to setOf("תרופות", "תרופת"),
        "רופא" to setOf("רופאה", "רופאים", "רופאות", "רופאת"),
        "דר" to setOf("דוקטור"),
        "דוקטור" to setOf("דר"),
    )

    internal val finalLetters: Map<String, String> = mapOf("ך" to "כ", "ם" to "מ", "ן" to "נ", "ף" to "פ", "ץ" to "צ")

    /**
     * Small words that follow a noun far more often than they finish a name:
     * "shir li" ("a song for me") must not read as "shirli".
     */
    internal val wordsThatNeverEndAName: Set<String> = setOf(
        "לי", "לו", "לה", "לך", "לנו", "לכם", "להם", "של", "את", "עם", "על", "אל", "כי", "גם", "זה", "זו", "לא",
    )

    /**
     * A word with each final letter written as its ordinary form, the shape
     * it takes inside a longer word: "ben" + "tzion" joined is "bentzion",
     * with the nun in its ordinary form.
     */
    internal fun foldingFinalLetters(word: String): String =
        graphemesOf(word).joinToString("") { finalLetters[it] ?: it }

    internal fun alternateForms(stem: String): Set<String> =
        (affectionateVariants[stem] ?: emptySet()) + (otherForms[stem] ?: emptySet())

    /**
     * True if [normalizedWord] is a recognized affectionate form of [stem]:
     * [stem] itself, [rawWord] with a diminutive marker glued onto it (see
     * [diminutiveCores]), or one of [affectionateVariants]' fixed alternate
     * spellings/nicknames for [stem]. [rawWord] is the same caption word
     * before [normalize] stripped the marker that distinguishes a nickname
     * ending from a real suffix change.
     */
    fun isAffectionateVariant(rawWord: String, normalizedWord: String, stem: String): Boolean {
        if (normalizedWord == stem) return true
        if (diminutiveCores(rawWord).contains(stem)) return true
        return alternateForms(stem).contains(normalizedWord)
    }

    /**
     * [stripAttachedPrefix], extended to also accept an affectionate
     * diminutive or alternate-spelling form of [stem] - bare, or behind one of
     * the attached prepositions ("le-savta'le", "to grandma'le").
     */
    fun stripAttachedPrefixOrVariant(rawWord: String, normalizedWord: String, stem: String): Boolean {
        if (stripAttachedPrefix(normalizedWord, stem)) return true
        // A word with a mark in it can still be a listed nickname: the closing
        // quote of a quoted 'savtush' stopped it being looked up.
        if (diminutiveCores(rawWord).any { stripAttachedPrefix(it, stem) }) return true
        val variants = alternateForms(stem)
        return variants.contains(normalizedWord) || variants.any { stripAttachedPrefix(normalizedWord, it) }
    }
}

/**
 * Finds every place a caption mentions a keyword the reader cares about.
 * Multi-word phrases must match consecutively, whole caption words only (so
 * "Dan" never matches "Dana"), and the attached-prefix rule applies only to a
 * phrase's first word - a caption is far more likely to attach a prefix to the
 * word right after a preposition than in the middle of a fixed phrase. A
 * caption word that is standalone punctuation ("," set off by spaces on both
 * sides) does not break a phrase's consecutive words apart; a real word in
 * between still does. A word said as an affectionate nickname or alternate
 * spelling of a phrase's word still matches; see
 * [HebrewText.isAffectionateVariant].
 */
data class KeywordAlertMatcher(var alerts: List<KeywordAlert>) {
    /**
     * The matches on a line still being written. A live pass that stops
     * mid-word ends on the model's ellipsis ("hatel..." on its way to
     * "hatelefon", the phone), and the stub can read as a name: over 6,602
     * live passes on broadcast speech it buzzed for "Tal" once, with nobody
     * called. The stub is left out until a later pass or the finished line
     * says the whole word; of the 16 names really said there, 15 buzzed on the
     * same pass as before and one 0.6 s later.
     */
    fun matchesInLiveText(text: String): List<KeywordMatch> = matches(droppingCutOffWord(text))

    fun matches(text: String): List<KeywordMatch> {
        val rawWords = splitOnKeywordWhitespace(HebrewText.separatingJoiners(text))
        if (rawWords.isEmpty()) return emptyList()
        // Normalizing word-by-word (rather than normalizing the whole string
        // and re-splitting) keeps this array the same length as `rawWords`, so
        // a word's index always means the same thing on both sides even when a
        // word is pure punctuation and normalizes to the empty string.
        val normalizedWords = rawWords.map { HebrewText.normalize(it) }
        val trimmedWords = rawWords.map { trimmingEdgePunctuation(it) }

        val unordered = mutableListOf<Pair<KeywordMatch, Int>>()
        var sequence = 0
        for (alert in alerts) {
            if (!alert.isEnabled) continue
            val phraseWords = HebrewText.words(alert.phrase)
            // No skipping a line with fewer words than the phrase: a name
            // saved as two words can be the whole line written as one.
            if (phraseWords.isEmpty()) continue

            for (start in normalizedWords.indices) {
                if (!HebrewText.stripAttachedPrefixOrVariant(rawWords[start], normalizedWords[start], phraseWords[0])) continue
                // A caption word that is pure punctuation ("," on its own,
                // surrounded by spaces) normalizes to the empty string; a
                // phrase's later words must still be found consecutively past
                // it, so it is skipped rather than treated as a real word that
                // breaks the phrase. A real filler word never normalizes to
                // empty, so it still blocks the match below.
                var cursor = start
                var isFullMatch = true
                for (offset in 1 until phraseWords.size) {
                    cursor += 1
                    while (cursor < normalizedWords.size && normalizedWords[cursor].isEmpty()) cursor += 1
                    if (cursor >= normalizedWords.size ||
                        !HebrewText.isAffectionateVariant(rawWords[cursor], normalizedWords[cursor], phraseWords[offset])
                    ) {
                        isFullMatch = false
                        break
                    }
                }
                if (!isFullMatch) continue

                val matchedText = trimmedWords.subList(start, cursor + 1).filter { it.isNotEmpty() }.joinToString(" ")
                unordered.add(KeywordMatch(alert.id, alert.phrase, matchedText, start) to sequence)
                sequence += 1
            }

            // A two-part name is written both as one word and as two
            // ("bentzion", "ben tzion", "ben-tzion"): whichever way it was
            // saved, the caption may use the other.
            val joinedPhrase = HebrewText.foldingFinalLetters(phraseWords.joinToString(""))
            if (phraseWords.size == 2) {
                for (index in normalizedWords.indices) {
                    if (!HebrewText.stripAttachedPrefix(HebrewText.foldingFinalLetters(normalizedWords[index]), joinedPhrase)) continue
                    unordered.add(KeywordMatch(alert.id, alert.phrase, trimmedWords[index], index) to sequence)
                    sequence += 1
                }
            } else if (phraseWords.size == 1 && graphemesOf(joinedPhrase).size >= 4) {
                for (index in 0 until normalizedWords.size - 1) {
                    if (normalizedWords[index].isEmpty() || normalizedWords[index + 1].isEmpty()) continue
                    if (normalizedWords[index + 1] in HebrewText.wordsThatNeverEndAName) continue
                    val glued = HebrewText.foldingFinalLetters(normalizedWords[index] + normalizedWords[index + 1])
                    if (!HebrewText.stripAttachedPrefix(glued, joinedPhrase)) continue
                    val matchedText = trimmedWords[index] + " " + trimmedWords[index + 1]
                    unordered.add(KeywordMatch(alert.id, alert.phrase, matchedText, index) to sequence)
                    sequence += 1
                }
            }
        }

        // Matches are found alert-by-alert above, but callers want them in
        // caption order regardless of which alert fired; `sequence` breaks
        // ties deterministically for two alerts that match at the same word.
        // Two alerts that found exactly the same words at the same place are
        // one mention: a list holding both spellings of a two-part name (each
        // now matches the other) buzzed and notified twice for it.
        val seen = HashSet<String>()
        return unordered
            .sortedWith(compareBy({ it.first.wordIndex }, { it.second }))
            .map { it.first }
            .filter { seen.add("${it.wordIndex} ${it.matchedText}") }
    }

    companion object {
        internal fun droppingCutOffWord(text: String): String {
            val words = splitOnKeywordWhitespace(HebrewText.separatingJoiners(HebrewText.removingDirectionMarks(text))).toMutableList()
            val last = words.lastOrNull() ?: return text
            if (!(last.endsWith("...") || last.endsWith("…"))) return text
            while (words.isNotEmpty() && HebrewText.normalize(words.last()).isEmpty()) words.removeAt(words.lastIndex)
            if (words.isNotEmpty()) words.removeAt(words.lastIndex)
            return words.joinToString(" ")
        }

        /**
         * Strips only leading and trailing punctuation/symbol characters,
         * leaving case and niqqud untouched, so `matchedText` reads as the word
         * actually looked in the caption ("savta" out of "\"savta\"" or
         * "savta,") rather than the fully normalized comparison form.
         */
        private fun trimmingEdgePunctuation(word: String): String {
            val scalars = word.codePoints().toArray()
            fun isEdge(codePoint: Int) = isKeywordPunctuation(codePoint) || isKeywordSymbol(codePoint)
            var from = 0
            var to = scalars.size
            while (from < to && isEdge(scalars[from])) from++
            while (to > from && isEdge(scalars[to - 1])) to--
            return String(scalars, from, to - from)
        }
    }
}

/**
 * Suppresses repeat alerts on the same match as a caption's partial updates
 * keep arriving for one utterance ("today" -> "today grandma" -> "today
 * grandma ate"): without this, buzzing/highlighting would fire again on every
 * single token the engine emits for the same utterance instead of once when
 * the keyword first appears.
 *
 * Counted per word rather than keyed on its position: a later pass that drops
 * a filler word earlier in the sentence moves "savta" from the third word to
 * the second, and by position that read as a new mention, a second buzz for
 * one she already heard. The second "savta" in a line is still a second
 * mention.
 */
class KeywordAlertDeduplicator {
    /** For each utterance, how many mentions of each word were reported. */
    private val reportedByUtterance = HashMap<UUID, MutableMap<UUID, Int>>()

    /** Oldest-first order of tracked utterance ids, used only to know which one to evict once the cap is hit. */
    private val trackingOrder = ArrayList<UUID>()

    fun newMatches(utteranceID: UUID, matches: List<KeywordMatch>): List<KeywordMatch> {
        val reportedCounts = reportedByUtterance[utteranceID] ?: HashMap()
        val isNewUtterance = reportedByUtterance[utteranceID] == null

        val fresh = mutableListOf<KeywordMatch>()
        val seenThisPass = HashMap<UUID, Int>()
        for (match in matches.sortedBy { it.wordIndex }) {
            val ordinal = seenThisPass[match.alertID] ?: 0
            seenThisPass[match.alertID] = ordinal + 1
            if (ordinal >= (reportedCounts[match.alertID] ?: 0)) fresh.add(match)
        }
        for ((alertID, count) in seenThisPass) {
            reportedCounts[alertID] = maxOf(reportedCounts[alertID] ?: 0, count)
        }
        reportedByUtterance[utteranceID] = reportedCounts

        if (isNewUtterance) {
            trackingOrder.add(utteranceID)
            if (trackingOrder.size > maxTrackedUtterances) {
                val oldest = trackingOrder.removeAt(0)
                reportedByUtterance.remove(oldest)
            }
        }
        return fresh
    }

    fun forget(utteranceID: UUID) {
        reportedByUtterance.remove(utteranceID)
        trackingOrder.removeAll { it == utteranceID }
    }

    fun forgetAll() {
        reportedByUtterance.clear()
        trackingOrder.clear()
    }

    companion object {
        /**
         * How many utterances' worth of "already reported" state to keep. A
         * live-captioning session can run for hours, and nothing ever tells
         * this type an utterance is done with for good (an engine's "final"
         * marker can be missed) - without a cap this map would grow for the
         * whole conversation. Bounding it to recent utterances only re-fires an
         * alert for one that scrolled far out of view, which is a cosmetic
         * edge case, not a correctness one.
         */
        const val maxTrackedUtterances = 64
    }
}

/**
 * Whether a keyword said again should get her attention again.
 *
 * Her name is the most common keyword, and at a family dinner it is said over
 * and over. A buzz, the "said: ..." pill and a screen-reader announcement
 * every time would soon be switched off altogether. So each word gets her
 * attention at most once every [cooldownSeconds]; every line it is said in is
 * still highlighted with a bell, so nothing is lost scrolling back. Shorter
 * than the 30 seconds between notifications: someone repeating her name
 * because she didn't react is exactly when the buzz helps.
 */
class KeywordAttentionPolicy(var cooldownSeconds: Double = 15.0) {
    private val lastAttentionAt = HashMap<UUID, Double>()

    /** Whether this hit should buzz and show, recording it if so. */
    fun claimAttention(alertID: UUID, timestamp: Double): Boolean {
        // A clock set back must not silence her name for as long as it was set back.
        val last = lastAttentionAt[alertID]
        if (last != null && timestamp >= last && timestamp - last < cooldownSeconds) return false
        lastAttentionAt[alertID] = timestamp
        return true
    }

    override fun equals(other: Any?): Boolean =
        other is KeywordAttentionPolicy && cooldownSeconds == other.cooldownSeconds && lastAttentionAt == other.lastAttentionAt

    override fun hashCode(): Int = cooldownSeconds.hashCode() * 31 + lastAttentionAt.hashCode()
}
