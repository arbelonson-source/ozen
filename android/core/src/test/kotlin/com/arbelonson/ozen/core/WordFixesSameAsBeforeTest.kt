package com.arbelonson.ozen.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Word fixes as they were before a line still being written stopped being
 * taken apart whole at every word, kept here as they were so the faster code
 * is held to writing exactly what they wrote: the same line and the same
 * doubtful words, pass after pass of a line growing, changing and coming
 * back as shown. A deliberate change to these rules changes this copy the
 * same way.
 */
private object FixesBefore {
    private const val MAXIMUM_FIXES = 100
    private const val MAXIMUM_LENGTH = 40
    private const val MAXIMUM_WORDS = 4
    private const val PREFIX_LETTERS = "והבלמשכ"
    private const val MINIMUM_PREFIXED_LETTERS = 3
    private const val MAQAF = 0x05BE
    private const val FINAL_SIGMA = 0x03C2
    private const val SIGMA = 0x03C3
    private const val DOTTED_CAPITAL_I = 0x0130
    private const val REPEAT_MARK = 0x3005

    private class Word(val tokenStart: Int, val tokenEnd: Int, val start: Int, val end: Int, val key: String, val letters: IntArray, val at: IntArray, val replacedEnd: Int)
    private class Compiled(val words: List<String>, val joints: List<Int>, val written: String, val exactly: Boolean, val inside: Boolean)

    private fun isHebrewMark(c: Int) = c in 0x0591..0x05C7 && c != MAQAF && c != 0x05C0 && c != 0x05C3 && c != 0x05C6

    private fun isMark(c: Int) = isHebrewMark(c) || c in 0x064B..0x065F || c == 0x0670 || c == 0x0640

    private fun isLetter(c: Int) = Character.isLetterOrDigit(c)

    private fun isCore(c: Int) = isLetter(c) || Character.isAlphabetic(c) || isMark(c) || when (Character.getType(c).toByte()) {
        Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun caseFolded(c: Int) = if (c == DOTTED_CAPITAL_I) c else Character.toLowerCase(c).let { if (it == FINAL_SIGMA) SIGMA else it }

    private fun caseKeyOf(text: String): String {
        val core = words(text, bare = true, atMaqaf = false).joinToString(" ") { word ->
            val out = StringBuilder()
            var k = word.start
            while (k < word.end) {
                val c = text.codePointAt(k)
                if (!isHebrewMark(c)) out.appendCodePoint(caseFolded(c))
                k += Character.charCount(c)
            }
            out.toString()
        }
        return if (core.isBlank()) text.filterNot { isHebrewMark(it.code) }.trim().lowercase().replace(FINAL_SIGMA.toChar(), SIGMA.toChar()) else core
    }

    private fun folded(c: Int) = when (val lower = Character.toLowerCase(c)) {
        FINAL_SIGMA -> SIGMA
        0x2018, 0x2019, 0x02BC, 0x05F3 -> 0x27
        0x201C, 0x201D, 0x05F4 -> 0x22
        0x0623, 0x0625, 0x0622 -> 0x0627
        else -> lower
    }

    private fun isSpace(c: Int) = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun keyOf(text: String): String {
        val core = words(text, bare = true, atMaqaf = false).joinToString(" ") { it.key }
        return if (core.isBlank()) text.filterNot { isHebrewMark(it.code) }.trim().map { folded(it.code).toChar() }.joinToString("") else core
    }

    private val closingQuote = mapOf(0x2018 to 0x2019, 0x27 to 0x27)

    private fun words(text: String, bare: Boolean = false, atMaqaf: Boolean = true): List<Word> {
        fun isBreak(c: Int) = isSpace(c) || (atMaqaf && c == MAQAF)
        val out = ArrayList<Word>()
        val open = HashSet<Int>()
        var i = 0
        while (i < text.length) {
            val head = text.codePointAt(i)
            if (isBreak(head)) { i += Character.charCount(head); continue }
            var j = i
            var start = -1
            var end = -1
            while (j < text.length) {
                val c = text.codePointAt(j)
                if (isBreak(c)) break
                val next = j + Character.charCount(c)
                if (isCore(c)) {
                    if (start < 0) start = j
                    end = next
                }
                j = next
            }
            val marksOnly = start < 0
            if (marksOnly) { start = j; end = j }
            var count = 0
            var k = start
            while (k < end) {
                val c = text.codePointAt(k)
                if (!isMark(c)) count++
                k += Character.charCount(c)
            }
            val letters = IntArray(count)
            val places = IntArray(count)
            count = 0
            k = start
            while (k < end) {
                val c = text.codePointAt(k)
                if (!isMark(c)) {
                    letters[count] = folded(c)
                    places[count] = k
                    count++
                }
                k += Character.charCount(c)
            }
            var at = i
            while (at < start) {
                val c = text.codePointAt(at)
                if (marksOnly) open.remove(c) else closingQuote[c]?.let { open.add(it) }
                at += Character.charCount(c)
            }
            val after = if (end < j) text.codePointAt(end) else -1
            val geresh = after >= 0 && folded(after) == 0x27 && letters.lastOrNull() in 0x05D0..0x05EA
            val past = if (after >= 0) end + Character.charCount(after) else end
            val doubled = past < j && folded(text.codePointAt(past)) == 0x27
            val replacedEnd = if (geresh && (after !in open || doubled)) past else end
            at = replacedEnd
            while (at < j) {
                val c = text.codePointAt(at)
                open.remove(c)
                at += Character.charCount(c)
            }
            if (bare || start < end) out.add(Word(i, j, start, end, String(letters, 0, letters.size), letters, places, replacedEnd))
            i = j
        }
        return out
    }

    private fun joint(text: String, word: Word, next: Word): Int? {
        if (word.replacedEnd != word.tokenEnd || next.start != next.tokenStart || next.tokenStart != word.tokenEnd + 1) return null
        return text[word.tokenEnd].code.takeIf { it == ' '.code || it == MAQAF }
    }

    private fun isDash(c: Int) = c == '-'.code || c == MAQAF || c == 0x2010 || c == 0x2011

    private fun isPunctuation(c: Int) = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun readsAlready(text: String, from: Int, written: String, exactly: Boolean, keep: Int, end: Int, toWordEnd: Boolean = true): Int? {
        fun skips(c: Int) = if (exactly) isHebrewMark(c) else isMark(c)
        fun same(a: Int, b: Int) = if (exactly) caseFolded(a) == caseFolded(b) else folded(a) == folded(b)
        var at = from
        fun skipMarks() {
            while (at < text.length && skips(text.codePointAt(at))) at += Character.charCount(text.codePointAt(at))
        }
        var next = 0
        while (next < written.length) {
            val c = written.codePointAt(next)
            next += Character.charCount(c)
            if (skips(c)) continue
            skipMarks()
            val shown = if (at < text.length) text.codePointAt(at) else -1
            if (shown >= 0 && ((isDash(c) && isDash(shown)) || same(c, shown))) {
                at += Character.charCount(shown)
            } else if (exactly || !isPunctuation(c) || at in keep + 1 until end) {
                return null
            }
        }
        skipMarks()
        return if (!toWordEnd || at >= text.length || !isCore(text.codePointAt(at))) at else null
    }

    private fun readingStarts(word: Word): List<Int> {
        val out = arrayListOf(word.start)
        for (n in 1..3) {
            if (word.letters.size <= n || word.letters[n - 1].toChar() !in PREFIX_LETTERS) break
            out.add(word.at[n])
        }
        return out
    }

    private fun writtenAlready(text: String, all: List<Word>, index: Int, keep: Int, fix: Compiled): Boolean {
        val end = all[index + fix.words.size - 1].end
        return (maxOf(0, index - fix.written.length)..index).any { at ->
            readingStarts(all[at]).any { from ->
                val stop = if (from <= keep) readsAlready(text, from, fix.written, fix.exactly, keep, end, toWordEnd = !fix.inside) else null
                stop != null && stop >= end && (!fix.inside || endsReading(text, stop))
            }
        }
    }

    private fun endsReading(text: String, stop: Int): Boolean {
        if (stop >= text.length || !isCore(text.codePointAt(stop))) return true
        val next = text.codePointAt(stop)
        return isJapanese(next) && !(stop > 0 && isKatakana(next) && isKatakana(text.codePointBefore(stop)))
    }

    private fun writtenAlreadyInside(text: String, at: Int, fix: Compiled): Boolean {
        val end = at + fix.words[0].length
        val lowest = maxOf(0, at - fix.written.length)
        val starts = generateSequence(at) { if (it > lowest) it - Character.charCount(text.codePointBefore(it)) else null }
        return starts.any { from -> (readsAlready(text, from, fix.written, fix.exactly, at, end, toWordEnd = false) ?: -1) >= end }
    }

    private fun prefixEnd(word: Word, heard: String, lone: Boolean): Int? {
        if (word.key == heard) return word.start
        if (heard.isEmpty() || heard.codePointAt(0) !in 0x05D0..0x05EA) return null
        if (lone && heard.codePointCount(0, heard.length) < MINIMUM_PREFIXED_LETTERS) return null
        val everyday = HebrewText.everydayWordsNotNames[heard].orEmpty()
        for (n in 1..3) {
            if (word.letters.size <= n) return null
            if (word.letters[n - 1].toChar() !in PREFIX_LETTERS) return null
            if (word.key.substring(n) != heard) continue
            return if ((0 until n).any { word.key.substring(it) in everyday }) null else word.at[n]
        }
        return null
    }

    private fun shownWords(text: String) = text.split(Regex("\\s+")).map(UncertainWords::normalize).filter { it.isNotEmpty() }

    private fun fixedEntry(fixes: List<WordFix>, entry: String): List<String> {
        val sure = entry.startsWith(UncertainWords.sureCopy)
        val word = if (sure) entry.substring(UncertainWords.sureCopy.length) else entry
        val fixed = apply(fixes, word)
        if (fixed == word) return listOf(entry)
        return shownWords(fixed).map { if (sure) UncertainWords.sureCopy + it else it }
    }

    private fun needsMaqaf(written: String): Boolean {
        if (written.isEmpty()) return false
        val start = written.codePointAt(0)
        return isLetter(start) && start !in 0x0590..0x05FF
    }

    private fun isHan(c: Int) = c in 0x3005..0x3007 || Character.isIdeographic(c) && c !in 0x3000..0x303F

    private fun isKatakanaLetter(c: Int) = c in 0x30A1..0x30FA || c == 0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF6F || c in 0xFF71..0xFF9D

    private fun isKatakana(c: Int) = isKatakanaLetter(c) || c in 0x30FC..0x30FE || c == 0xFF70 || c in 0xFF9E..0xFF9F || c in 0x3099..0x309C

    private fun isHiragana(c: Int) = c in 0x3041..0x3096 || c in 0x309D..0x309F

    private fun isJapanese(c: Int) = isHan(c) || isKatakana(c) || isHiragana(c)

    private fun readsInsideWords(heard: String) = heard.isNotEmpty() && heard.codePointAt(0) != REPEAT_MARK &&
        heard.codePoints().allMatch { isJapanese(it) || it == 0x30FB || it == 0xFF65 } &&
        heard.codePoints().anyMatch { (isHan(it) && it != REPEAT_MARK) || isKatakanaLetter(it) }

    private fun hasJapanese(text: String, word: Word): Boolean {
        var at = word.start
        while (at < word.end) {
            val c = text.codePointAt(at)
            if (isJapanese(c)) return true
            at += Character.charCount(c)
        }
        return false
    }

    private fun standsAlone(text: String, at: Int, heard: String): Boolean {
        val end = at + heard.length
        if (isKatakana(heard.codePointAt(0)) && at > 0 && isKatakana(text.codePointBefore(at))) return false
        return !(isKatakana(heard.codePointBefore(heard.length)) && end < text.length && isKatakana(text.codePointAt(end)))
    }

    private fun replacingInside(fixes: List<Compiled>, text: String, word: Word): String? {
        if (fixes.isEmpty() || !hasJapanese(text, word)) return null
        val out = StringBuilder()
        var changed = false
        var at = word.start
        while (at < word.end) {
            val hit = fixes.firstOrNull { text.startsWith(it.words[0], at) && at + it.words[0].length <= word.end && standsAlone(text, at, it.words[0]) }
            if (hit != null) {
                val kept = writtenAlreadyInside(text, at, hit)
                out.append(if (kept) hit.words[0] else hit.written)
                at += hit.words[0].length
                changed = changed || !kept
            } else {
                val c = text.codePointAt(at)
                out.appendCodePoint(c)
                at += Character.charCount(c)
            }
        }
        return if (changed) out.toString() else null
    }

    fun normalized(fixes: List<WordFix>): List<WordFix> {
        val seen = HashSet<String>()
        val out = ArrayList<WordFix>()
        for (fix in fixes) {
            val heard = fix.heard.trim().replace(Regex("\\s+"), " ").take(MAXIMUM_LENGTH).trim()
            val written = fix.written.trim().take(MAXIMUM_LENGTH).trim()
            if (heard.isEmpty() || written.isEmpty() || caseKeyOf(heard) == caseKeyOf(written)) continue
            val parts = words(heard)
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS || parts.any { it.key.isEmpty() }) continue
            if (parts.zipWithNext().any { (a, b) -> joint(heard, a, b) == null }) continue
            if (!seen.add(keyOf(heard))) continue
            out.add(WordFix(heard, written))
            if (out.size == MAXIMUM_FIXES) break
        }
        return out
    }

    private class Span(val first: Int, val last: Int, val replacement: String)
    private class Rewrite(val text: String, val words: List<Word>, val spans: List<Span>)

    fun apply(fixes: List<WordFix>, text: String): String {
        if (fixes.isEmpty() || text.isEmpty()) return text
        val compiled = compile(fixes)
        if (compiled.none { mayFix(text, it) }) return text
        return rewrite(fixes, text, compiled).text
    }

    private fun mayFix(text: String, fix: Compiled): Boolean {
        val heard = fix.words[0]
        if (heard.isEmpty() || text.contains(heard)) return true
        var from = 0
        while (from < text.length) {
            val c = text.codePointAt(from)
            if (!isMark(c) && readsFolded(text, from, heard)) return true
            from += Character.charCount(c)
        }
        return false
    }

    private fun readsFolded(text: String, from: Int, heard: String): Boolean {
        var at = from
        var next = 0
        while (next < heard.length) {
            while (at < text.length && isMark(text.codePointAt(at))) at += Character.charCount(text.codePointAt(at))
            if (at >= text.length) return false
            val shown = text.codePointAt(at)
            val wanted = heard.codePointAt(next)
            if (folded(shown) != wanted) return false
            at += Character.charCount(shown)
            next += Character.charCount(wanted)
        }
        return true
    }

    fun applyToUncertain(fixes: List<WordFix>, text: String, uncertain: List<String>): List<String> {
        if (uncertain.isEmpty()) return uncertain
        val rewritten = rewrite(fixes, text)
        val taken = HashSet<Int>()
        val written = HashMap<Span, Pair<Int, Boolean>>()
        val out = ArrayList<String>()
        for (entry in uncertain) {
            val sure = entry.startsWith(UncertainWords.sureCopy)
            val key = keyOf(if (sure) entry.substring(UncertainWords.sureCopy.length) else entry)
            val index = rewritten.words.indices.firstOrNull { it !in taken && rewritten.words[it].key == key }
            if (index == null) { out.addAll(fixedEntry(fixes, entry)); continue }
            taken.add(index)
            val span = rewritten.spans.firstOrNull { index in it.first..it.last }
            if (span == null) { out.add(entry); continue }
            val words = shownWords(span.replacement)
            val before = written[span]
            if (before == null) {
                written[span] = out.size to sure
                out.addAll(if (sure) words.map { UncertainWords.sureCopy + it } else words)
            } else if (before.second && !sure) {
                for ((at, word) in words.withIndex()) out[before.first + at] = word
                written[span] = before.first to false
            }
        }
        return out
    }

    private class CompiledFixes(val fixes: List<WordFix>, val compiled: List<Compiled>)

    @Volatile
    private var lastCompiled: CompiledFixes? = null

    private fun compile(fixes: List<WordFix>): List<Compiled> {
        lastCompiled?.let { if (it.fixes == fixes) return it.compiled }
        val compiled = normalized(fixes).mapNotNull { fix ->
            val parts = words(fix.heard)
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS) null
            else Compiled(
                parts.map { it.key }, parts.zipWithNext { a, b -> joint(fix.heard, a, b) ?: ' '.code }, fix.written,
                keyOf(fix.heard) == keyOf(fix.written), parts.size == 1 && readsInsideWords(parts[0].key),
            )
        }
        lastCompiled = CompiledFixes(fixes.toList(), compiled)
        return compiled
    }

    private fun rewrite(fixes: List<WordFix>, text: String, compiledFixes: List<Compiled>? = null): Rewrite {
        if (fixes.isEmpty() || text.isEmpty()) return Rewrite(text, words(text), emptyList())
        val compiled = compiledFixes ?: compile(fixes)
        if (compiled.isEmpty()) return Rewrite(text, words(text), emptyList())
        val inside = compiled.filter { it.inside }.sortedByDescending { it.words[0].length }
        val all = words(text)
        val sb = StringBuilder(text.length + 16)
        val spans = ArrayList<Span>()
        var copied = 0
        var index = 0
        while (index < all.size) {
            var best: Compiled? = null
            var keep = 0
            for (c in compiled) {
                if (best != null && c.words.size < best.words.size) continue
                if (index + c.words.size > all.size) continue
                val first = prefixEnd(all[index], c.words[0], lone = c.words.size == 1) ?: continue
                if (best != null && c.words.size == best.words.size && first >= keep) continue
                var ok = true
                for (k in 1 until c.words.size) {
                    if (all[index + k].key != c.words[k] || joint(text, all[index + k - 1], all[index + k]) != c.joints[k - 1]) { ok = false; break }
                }
                if (ok) { best = c; keep = first }
            }
            if (best == null) {
                val fixed = replacingInside(inside, text, all[index])
                if (fixed != null) {
                    sb.append(text, copied, all[index].start).append(fixed)
                    copied = all[index].end
                    spans.add(Span(index, index, fixed))
                }
                index++
                continue
            }
            if (writtenAlready(text, all, index, keep, best)) {
                index += best.words.size
                continue
            }
            val last = all[index + best.words.size - 1]
            val prefix = text.substring(all[index].start, keep)
            val replacement = prefix + (if (prefix.isNotEmpty() && needsMaqaf(best.written)) "\u05BE" else "") + best.written
            sb.append(text, copied, all[index].start).append(replacement)
            spans.add(Span(index, index + best.words.size - 1, replacement))
            copied = last.replacedEnd
            index += best.words.size
        }
        return Rewrite(sb.append(text, copied, text.length).toString(), all, spans)
    }
}

class WordFixesSameAsBeforeTest {
    private class Case(val fixes: List<WordFix>, val text: String)

    private fun sharedCases(): List<Case> {
        val text = File(System.getProperty("ozen.fixtures"), "logic/word-fixes.json").readText(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { element ->
            val item = element.jsonObject
            val fixes = item.getValue("fixes").jsonArray.map { pair -> pair.jsonArray.let { WordFix(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) } }
            Case(fixes, item.getValue("text").jsonPrimitive.content)
        }
    }

    private class Comparison {
        val failures = ArrayList<String>()
        var passes = 0
        var changed = 0

        /** [pass] fixed both ways, then the line as shown fixed again, as the screen does with the fixes made on that line. */
        fun compare(fixes: List<WordFix>, pass: String, again: List<WordFix> = fixes): String {
            val shown = same(fixes, pass)
            same(again, shown)
            return shown
        }

        private fun same(fixes: List<WordFix>, pass: String): String {
            passes++
            val before = FixesBefore.apply(fixes, pass)
            val now = WordFixes.apply(fixes, pass)
            if (before != pass) changed++
            if (before != now) failures += "$fixes on \"$pass\": before \"$before\", now \"$now\""
            val doubtful = pass.split(' ').map { UncertainWords.normalize(it) }.filter { it.isNotEmpty() }
                .mapIndexed { index, word -> if (index % 3 == 1) UncertainWords.sureCopy + word else word } + "גם"
            val doubtBefore = FixesBefore.applyToUncertain(fixes, pass, doubtful)
            val doubtNow = WordFixes.applyToUncertain(fixes, pass, doubtful)
            if (doubtBefore != doubtNow) failures += "doubt $fixes on \"$pass\" $doubtful: before $doubtBefore, now $doubtNow"
            return now
        }
    }

    /** Every way a live line reaches the fixes: a letter at a time, a word at a time, cut off mid-word, and with its end said again. */
    private fun passesOf(text: String): List<String> {
        val words = text.split(" ")
        val grown = (1..words.size).map { words.take(it).joinToString(" ") }
        return text.indices.map { text.substring(0, it + 1) } + grown + grown.map { "$it הטל..." } +
            grown.map { "$it ${words.last()}" } + words.drop(1).joinToString(" ") + "${words.first()} $text" + text
    }

    @Test
    fun `every pass of every shared line is fixed as before`() {
        val comparison = Comparison()
        for (case in sharedCases()) {
            for (pass in passesOf(case.text)) comparison.compare(case.fixes, pass)
        }
        assertEquals(emptyList(), comparison.failures.take(20), comparison.failures.take(20).joinToString("\n"))
        assertTrue(comparison.passes > 10_000 && comparison.changed > 1_500, "${comparison.passes} passes, ${comparison.changed} changed")
    }

    private val decorations: List<(String) -> String> = listOf(
        { it }, { it }, { it }, { "$it," }, { "$it." }, { "$it?" }, { "$it…" }, { "'$it'" }, { "‘$it’" }, { "\"$it\"" }, { "($it)" },
        { "$it׳" }, { "$it'" }, { "${it}'s" }, { "\u200F$it" }, { it.uppercase() }, { "$it־" }, { "-$it" },
        { word -> word.map { if (it in 'א'..'ת') "$it\u05B8" else "$it" }.joinToString("") },
        { word -> word.map { if (it in 'ء'..'ي') "$it\u064E" else "$it" }.joinToString("") },
    )

    private val extraWords = listOf(
        "אמר", "לבוא", "שלום", "ג׳ורג׳", "ג'ורג'", "צה״ל", "תל־אביב", "ו", "ב", "של", "הוא", "היא", "את", "על",
        "قال", "في", "على", "أحمد", "احمد", "مُحمّد", "ـ", "the", "and", "don't", "dont", "don’t", "Dr.", "e-mail", "3.5", "10:30",
        "İstanbul", "istanbul", "ΟΔΟΣ", "οδος", "Маша", "маша", "我住在北京市", "東京都", "北京", "京", "，", "。", "...", "-", "—",
    )

    @Test
    fun `generated lines in many languages are fixed as before, word by word`() {
        val cases = sharedCases()
        val vocabulary = (cases.flatMap { case -> case.text.split(" ") + case.fixes.flatMap { listOf(it.heard, it.written) } } + extraWords)
            .filter { it.isNotEmpty() }.distinct()
        val random = Random(2026)
        val comparison = Comparison()
        repeat(LINES) {
            val chosen = List(1 + random.nextInt(3)) { cases.random(random) }
            val fixes = chosen.flatMap { it.fixes }
            val handFixes = if (random.nextInt(4) == 0) fixes else cases.random(random).fixes + fixes
            val local = chosen.flatMap { case -> case.text.split(" ") + case.fixes.flatMap { listOf(it.heard, it.written) + it.heard.split(" ") } }
            fun word() = if (random.nextBoolean()) local.random(random) else vocabulary.random(random)
            val other = List(3 + random.nextInt(10)) { word() }
            val words = ArrayList<String>()
            repeat(3 + random.nextInt(24)) { step ->
                words.add(decorations.random(random)(word()))
                if (words.size > 2 && random.nextInt(8) == 0) words[random.nextInt(words.size - 1)] = word()
                val joiner = if (random.nextInt(10) == 0) "־" else " "
                val pass = words.joinToString(joiner)
                comparison.compare(fixes, pass, handFixes)
                if (random.nextInt(5) == 0) comparison.compare(fixes, pass.dropLast(1 + random.nextInt(minOf(4, pass.length))) + "...")
                if (random.nextInt(6) == 0) comparison.compare(fixes, other.take(1 + step % other.size).joinToString(" "))
            }
        }
        assertEquals(emptyList(), comparison.failures.take(20), comparison.failures.take(20).joinToString("\n"))
        assertTrue(comparison.passes > 20_000 && comparison.changed > 2_000, "${comparison.passes} passes, ${comparison.changed} changed")
    }

    private companion object {
        const val LINES = 1_500
    }
}
