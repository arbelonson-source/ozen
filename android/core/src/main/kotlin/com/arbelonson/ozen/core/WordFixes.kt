package com.arbelonson.ozen.core

data class WordFix(val heard: String, val written: String)

object WordFixes {
    const val MAXIMUM_FIXES = 100
    const val MAXIMUM_LENGTH = 40
    private const val MAXIMUM_WORDS = 4
    private const val PREFIX_LETTERS = "והבלמשכ"

    private class Word(val start: Int, val end: Int, val key: String, val bases: List<Pair<Int, Int>>)
    private class Compiled(val words: List<String>, val written: String)

    private fun isMark(c: Int) = c in 0x0591..0x05C7 && c != 0x05BE && c != 0x05C0 && c != 0x05C3 && c != 0x05C6

    private fun isCombining(c: Int) = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isLetter(c: Int) = Character.isLetterOrDigit(c)
    private fun isJoiner(c: Int) = c == '\''.code || c == '’'.code || c == '׳'.code || c == '״'.code

    private fun keyOf(text: String) =
        text.filterNot { isMark(it.code) }.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun words(text: String): List<Word> {
        val out = ArrayList<Word>()
        fun inWord(at: Int): Boolean {
            val c = text.codePointAt(at)
            if (isLetter(c) || isMark(c) || isCombining(c)) return true
            if (!isJoiner(c) || at == 0) return false
            val next = at + Character.charCount(c)
            return next < text.length && isLetter(text.codePointBefore(at)) && isLetter(text.codePointAt(next))
        }
        var i = 0
        while (i < text.length) {
            if (!inWord(i)) { i += Character.charCount(text.codePointAt(i)); continue }
            var j = i
            while (j < text.length && inWord(j)) j += Character.charCount(text.codePointAt(j))
            val bases = ArrayList<Pair<Int, Int>>()
            var k = i
            while (k < j) {
                val c = text.codePointAt(k)
                if (!isMark(c)) bases.add(Character.toLowerCase(c) to k)
                k += Character.charCount(c)
            }
            val key = StringBuilder()
            for ((c, _) in bases) key.appendCodePoint(c)
            out.add(Word(i, j, key.toString(), bases))
            i = j
        }
        return out
    }

    private fun prefixEnd(word: Word, heard: String): Int? {
        if (word.key == heard) return word.start
        for (n in 1..3) {
            if (word.bases.size <= n) return null
            if (word.bases[n - 1].first.toChar() !in PREFIX_LETTERS) return null
            if (word.key.substring(n) == heard) return word.bases[n].second
        }
        return null
    }

    /** Doubtful entries are the normalized words [UncertainWords.ranges] looks for, one per word the line shows. */
    private fun shownWords(text: String) = text.split(Regex("\\s+")).map(UncertainWords::normalize).filter { it.isNotEmpty() }

    private fun fixedEntry(fixes: List<WordFix>, entry: String): List<String> {
        val sure = entry.startsWith(UncertainWords.sureCopy)
        val word = if (sure) entry.substring(UncertainWords.sureCopy.length) else entry
        val fixed = apply(fixes, word)
        if (fixed == word) return listOf(entry)
        return shownWords(fixed).map { if (sure) UncertainWords.sureCopy + it else it }
    }

    /** A Hebrew prefix letter kept before a word in another script, or before a number, is joined to it with a maqaf (U+05BE). */
    private fun needsMaqaf(written: String): Boolean {
        if (written.isEmpty()) return false
        val start = written.codePointAt(0)
        return isLetter(start) && start !in 0x0590..0x05FF
    }

    private fun isHan(word: String) = word.isNotEmpty() && word.codePoints().allMatch { isHan(it) }

    private fun isHan(c: Int) = Character.isIdeographic(c) && c !in 0x3000..0x303F

    private fun hasHan(text: String, word: Word): Boolean {
        var at = word.start
        while (at < word.end) {
            val c = text.codePointAt(at)
            if (isHan(c)) return true
            at += Character.charCount(c)
        }
        return false
    }

    private fun replacingHan(fixes: List<Compiled>, text: String, word: Word): String? {
        if (fixes.isEmpty() || !hasHan(text, word)) return null
        val out = StringBuilder()
        var changed = false
        var at = word.start
        while (at < word.end) {
            val hit = fixes.firstOrNull { text.startsWith(it.words[0], at) && at + it.words[0].length <= word.end }
            if (hit != null) {
                out.append(hit.written)
                at += hit.words[0].length
                changed = true
            } else {
                val c = text.codePointAt(at)
                out.appendCodePoint(c)
                at += Character.charCount(c)
            }
        }
        return if (changed) out.toString() else null
    }

    fun wordsOf(text: String): List<String> = words(text).map { text.substring(it.start, it.end) }

    /** Whether word [first] and the next one in [text] are one plain space apart, so a phrase can cover both. */
    fun joinedBySpace(text: String, first: Int): Boolean {
        val all = words(text)
        return first + 1 < all.size && text.substring(all[first].end, all[first + 1].start) == " "
    }

    fun tapped(selection: IntRange?, index: Int, joined: (Int) -> Boolean = { true }): IntRange? = when {
        selection == null -> index..index
        index in selection -> if (selection.first == selection.last) null else if (index == selection.first) (index + 1)..selection.last else selection.first..(index - 1)
        index == selection.first - 1 && selection.count() < MAXIMUM_WORDS && joined(index) -> index..selection.last
        index == selection.last + 1 && selection.count() < MAXIMUM_WORDS && joined(selection.last) -> selection.first..index
        else -> index..index
    }

    fun normalized(fixes: List<WordFix>): List<WordFix> {
        val seen = HashSet<String>()
        val out = ArrayList<WordFix>()
        for (fix in fixes) {
            val heard = fix.heard.trim().replace(Regex("\\s+"), " ").take(MAXIMUM_LENGTH).trim()
            val written = fix.written.trim().take(MAXIMUM_LENGTH).trim()
            if (heard.isEmpty() || written.isEmpty() || keyOf(heard) == keyOf(written)) continue
            val parts = words(heard)
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS) continue
            if (parts.zipWithNext().any { (a, b) -> heard.substring(a.end, b.start) != " " }) continue
            if (!seen.add(keyOf(heard))) continue
            out.add(WordFix(heard, written))
            if (out.size == MAXIMUM_FIXES) break
        }
        return out
    }

    /** Whether [fix] can go into [fixes]: a valid fix, and room for it unless it replaces the one with the same heard words. */
    fun canAdd(fix: WordFix, fixes: List<WordFix>): Boolean {
        val one = normalized(listOf(fix)).firstOrNull() ?: return false
        return fixes.size < MAXIMUM_FIXES || fixes.any { keyOf(it.heard) == keyOf(one.heard) }
    }

    /** The arrow between heard and written words, pointing the way [heard] is read: left for Hebrew or Arabic. */
    fun arrow(heard: String): String {
        var at = 0
        while (at < heard.length) {
            val c = heard.codePointAt(at)
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return "\u2190"
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return "\u2192"
                else -> at += Character.charCount(c)
            }
        }
        return "\u2192"
    }

    /** Puts [fix] first; a fix whose written form is the word being corrected now writes the new form. */
    fun adding(fix: WordFix, to: List<WordFix>): List<WordFix> {
        val moved = keyOf(fix.heard)
        val carried = to.map { old -> if (keyOf(old.written) == moved) WordFix(old.heard, fix.written) else old }
        return normalized(listOf(fix) + carried)
    }

    private class Span(val first: Int, val last: Int, val replacement: String)
    private class Rewrite(val text: String, val words: List<Word>, val spans: List<Span>)

    fun apply(fixes: List<WordFix>, text: String): String = rewrite(fixes, text).text

    /**
     * The words the model was unsure of in [text], after [fixes] rewrote it.
     * A word inside a rewritten stretch (the "york" of "New York" written as
     * "NYC") is replaced by the words written there, once, so the doubt stays
     * on the new words; each entry is matched to its own word of [text], so
     * a repeated word is not marked in place of another.
     */
    fun applyToUncertain(fixes: List<WordFix>, text: String, uncertain: List<String>): List<String> {
        if (uncertain.isEmpty()) return uncertain
        val rewritten = rewrite(fixes, text)
        val taken = HashSet<Int>()
        val written = HashSet<Span>()
        val out = ArrayList<String>()
        for (entry in uncertain) {
            val key = keyOf(entry)
            val index = rewritten.words.indices.firstOrNull { it !in taken && rewritten.words[it].key == key }
            if (index == null) { out.addAll(fixedEntry(fixes, entry)); continue }
            taken.add(index)
            val span = rewritten.spans.firstOrNull { index in it.first..it.last }
            if (span == null) { out.add(entry); continue }
            if (written.add(span)) out.addAll(shownWords(span.replacement))
        }
        return out
    }

    private fun rewrite(fixes: List<WordFix>, text: String): Rewrite {
        if (fixes.isEmpty() || text.isEmpty()) return Rewrite(text, words(text), emptyList())
        val compiled = normalized(fixes).mapNotNull { fix ->
            val parts = words(fix.heard).map { it.key }
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS) null else Compiled(parts, fix.written)
        }
        if (compiled.isEmpty()) return Rewrite(text, words(text), emptyList())
        val han = compiled.filter { it.words.size == 1 && isHan(it.words[0]) }.sortedByDescending { it.words[0].length }
        val all = words(text)
        val sb = StringBuilder()
        val spans = ArrayList<Span>()
        var copied = 0
        var index = 0
        while (index < all.size) {
            var best: Compiled? = null
            var keep = 0
            for (c in compiled) {
                if (best != null && c.words.size <= best.words.size) continue
                if (index + c.words.size > all.size) continue
                val first = prefixEnd(all[index], c.words[0]) ?: continue
                var ok = true
                for (k in 1 until c.words.size) {
                    if (all[index + k].key != c.words[k] || all[index + k].start != all[index + k - 1].end + 1 ||
                        text[all[index + k - 1].end] != ' '
                    ) { ok = false; break }
                }
                if (ok) { best = c; keep = first }
            }
            if (best == null) {
                val inside = replacingHan(han, text, all[index])
                if (inside != null) {
                    sb.append(text, copied, all[index].start).append(inside)
                    copied = all[index].end
                    spans.add(Span(index, index, inside))
                }
                index++
                continue
            }
            val last = all[index + best.words.size - 1]
            val prefix = text.substring(all[index].start, keep)
            val replacement = prefix + (if (prefix.isNotEmpty() && needsMaqaf(best.written)) "\u05BE" else "") + best.written
            sb.append(text, copied, all[index].start).append(replacement)
            spans.add(Span(index, index + best.words.size - 1, replacement))
            copied = last.end
            index += best.words.size
        }
        return Rewrite(sb.append(text, copied, text.length).toString(), all, spans)
    }
}
