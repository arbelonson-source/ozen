package com.arbelonson.ozen.core

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class NumberWordProfile(entry: JsonObject) {
    private val foldsArabic = entry["letterFolding"]?.jsonPrimitive?.content == "arabic"
    val numbers = strings(entry, "numbers", foldsArabic)
    val scales = strings(entry, "scales", foldsArabic)
    val connectors = strings(entry, "connectors", foldsArabic)
    val fractions = strings(entry, "fractions", foldsArabic)
    val fractionLinks = strings(entry, "fractionLinks", foldsArabic)
    val clockLinks = strings(entry, "clockLinks", foldsArabic)
    val numberClockLinks = strings(entry, "numberClockLinks", foldsArabic)
    val numberClockArticles = strings(entry, "numberClockArticles", foldsArabic)
    val clockUnits = strings(entry, "clockUnits", foldsArabic)
    val clockHourOnes = strings(entry, "clockHourOnes", foldsArabic)
    val clockMinuteOnes = strings(entry, "clockMinuteOnes", foldsArabic)
    val clockOneCues = strings(entry, "clockOneCues", foldsArabic)
    val clockUnitLinks = strings(entry, "clockUnitLinks", foldsArabic)
    val ambiguous = strings(entry, "ambiguous", foldsArabic)
    val notACountBefore = strings(entry, "notACountBefore", foldsArabic)
    val countsAfter = strings(entry, "countsAfter", foldsArabic)
    val plainAlone = strings(entry, "plainAlone", foldsArabic)
    val onceWords = strings(entry, "onceWords", foldsArabic)
    val repeatLinks = strings(entry, "repeatLinks", foldsArabic)
    val repeatUnits = strings(entry, "repeatUnits", foldsArabic)
    val periods = strings(entry, "periods", foldsArabic)
    val idioms = strings(entry, "idioms", foldsArabic)
    val storyOpeners = strings(entry, "storyOpeners", foldsArabic)
    val storyKeeps = strings(entry, "storyKeeps", foldsArabic)
    val units = strings(entry, "units", foldsArabic)
    val adverbPeriods = strings(entry, "adverbPeriods", foldsArabic)
    val clockNouns = strings(entry, "clockNouns", foldsArabic)
    val clockOrdinals = strings(entry, "clockOrdinals", foldsArabic)
    val plainWords = strings(entry, "plainWords", foldsArabic)
    val oneFollowsUnit = entry["oneFollowsUnit"]?.jsonPrimitive?.content == "true"
    val clockStopsBeforeCapitalized = entry["clockStopsBeforeCapitalized"]?.jsonPrimitive?.content == "true"
    private val attachedPrefixes = strings(entry, "attachedPrefixes", foldsArabic).toList()
    private val known = numbers + fractions + units + periods + adverbPeriods + clockNouns + clockOrdinals +
        onceWords + ambiguous + notACountBefore + countsAfter + plainAlone

    /**
     * The form of a word as the lists have it: lower case and, for Arabic, without vowel marks,
     * with the letter spellings folded together and without the one-letter prefixes ("and", "at",
     * "for", "the") written onto the word, unless the word is itself in a list.
     */
    fun normalize(core: String): String {
        val word = Normalizer.normalize(core.lowercase(Locale.ROOT).replace('’', '\''), Normalizer.Form.NFC)
        return if (foldsArabic) stripPrefixes(foldArabicLetters(word), 0) else word
    }

    /** The hash of each word a number starts at in this language ([numbers], [onceWords], [clockOrdinals], [fractions]), sorted. */
    private val startHashes: IntArray = (numbers + onceWords + clockOrdinals + fractions).map { it.hashCode() }.distinct().sorted().toIntArray()

    /**
     * False when the word of [text] from [from] until [to], once [normalize]d,
     * is none of the words a number starts at, told without making that form.
     * A word whose every unit is a character by itself ([Graphemes.standsAlone])
     * normalizes unit by unit: each made lower case, a right single quote an
     * apostrophe, and nothing composed. A word with any other unit or with
     * one of [OTHERWISE_NORMALIZED], and a language whose letters are
     * folded, all say true, to be asked the long way.
     */
    fun mayStartANumber(text: String, from: Int, to: Int): Boolean {
        if (foldsArabic) return true
        var hash = 0
        for (index in from until to) {
            val unit = text[index]
            if (!Graphemes.standsAlone(unit) || unit in OTHERWISE_NORMALIZED) return true
            val lower = Character.toLowerCase(unit)
            hash = 31 * hash + (if (lower == '’') '\'' else lower).code
        }
        return startHashes.binarySearch(hash) >= 0
    }

    private fun stripPrefixes(word: String, depth: Int): String {
        if (word in known || word in plainWords || depth == 3) return word
        for (prefix in attachedPrefixes) {
            if (!word.startsWith(prefix) || word.length - prefix.length < 3) continue
            val rest = stripPrefixes(word.substring(prefix.length), depth + 1)
            if (rest in known) return rest
        }
        return word
    }

    companion object {
        /**
         * The characters that stand alone but do not normalize as above: the
         * dotted capital I, two units in lower case, and two spaces made
         * other spaces.
         */
        internal const val OTHERWISE_NORMALIZED = "\u0130\u2000\u2001"

        private val byLanguage: Map<String, NumberWordProfile> by lazy { load() }

        /**
         * Whether a word is a counting word in any language with a list. The articles that are
         * also the word for one ("un", "um", "ein") don't count: said over and over they are a
         * stutter, not a digit.
         */
        fun isCountingWordInAnyLanguage(word: String): Boolean = byLanguage.values.any { profile ->
            val form = profile.normalize(word)
            form in profile.numbers && form !in articlesSaidAsOne
        }

        private val articlesSaidAsOne = setOf("um", "uma", "un", "una", "une", "ein", "eine", "einen", "einem", "einer", "eines")

        fun profile(languageCode: String): NumberWordProfile? {
            val language = UILanguage.match(languageCode) ?: return null
            return byLanguage[language.rawValue]?.takeIf { it.numbers.isNotEmpty() }
        }

        private fun load(): Map<String, NumberWordProfile> {
            val text = NumberWordProfile::class.java.getResourceAsStream("/NumberWords.json")
                ?.use { it.readBytes().decodeToString() }
                ?: return emptyMap()
            return runCatching {
                Json.parseToJsonElement(text).jsonObject.mapValues { (_, entry) -> NumberWordProfile(entry.jsonObject) }
            }.getOrDefault(emptyMap())
        }

        private fun strings(entry: JsonObject, key: String, fold: Boolean): Set<String> =
            entry[key]?.jsonArray?.map { it.jsonPrimitive.content.let { word -> if (fold) foldArabicLetters(word) else word } }
                ?.toSet().orEmpty()

        internal fun foldArabicLetters(word: String): String = buildString {
            for (letter in word) {
                when (letter.code) {
                    in 0x064B..0x065F, 0x0670, 0x0640 -> {}
                    0x0623, 0x0625, 0x0622, 0x0671 -> append('\u0627')
                    0x0649 -> append('\u064A')
                    0x0629 -> append('\u0647')
                    else -> append(letter)
                }
            }
        }
    }
}
