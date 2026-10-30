package com.arbelonson.ozen.core

data class WordFix(val heard: String, val written: String)

object WordFixes {
    const val MAXIMUM_FIXES = 100
    const val MAXIMUM_LENGTH = 40
    private const val MAXIMUM_WORDS = 4
    private const val PREFIX_LETTERS = "והבלמשכ"

    private class Word(val start: Int, val end: Int, val key: String, val bases: List<Pair<Char, Int>>)
    private class Compiled(val words: List<String>, val written: String)

    private fun isMark(c: Char) = c in '֑'..'ׇ'
    private fun isJoiner(c: Char) = c == '\'' || c == '’' || c == '׳' || c == '״'

    private fun keyOf(text: String) =
        text.filterNot { isMark(it) }.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun words(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            fun inWord(at: Int) = text[at].isLetterOrDigit() || isMark(text[at]) ||
                (isJoiner(text[at]) && at > 0 && at + 1 < text.length && text[at - 1].isLetterOrDigit() && text[at + 1].isLetterOrDigit())
            if (!inWord(i)) { i++; continue }
            var j = i
            while (j < text.length && inWord(j)) j++
            val bases = ArrayList<Pair<Char, Int>>()
            for (k in i until j) if (!isMark(text[k])) bases.add(text[k].lowercaseChar() to k)
            out.add(Word(i, j, bases.joinToString("") { it.first.toString() }, bases))
            i = j
        }
        return out
    }

    private fun prefixEnd(word: Word, heard: String): Int? {
        if (word.key == heard) return word.start
        for (n in 1..3) {
            if (word.bases.size <= n) return null
            if (word.bases[n - 1].first !in PREFIX_LETTERS) return null
            if (word.key.substring(n) == heard) return word.bases[n].second
        }
        return null
    }

    fun wordsOf(text: String): List<String> = words(text).map { text.substring(it.start, it.end) }

    fun tapped(selection: IntRange?, index: Int): IntRange? = when {
        selection == null -> index..index
        index in selection -> if (selection.first == selection.last) null else if (index == selection.first) (index + 1)..selection.last else selection.first..(index - 1)
        index == selection.first - 1 && selection.count() < MAXIMUM_WORDS -> index..selection.last
        index == selection.last + 1 && selection.count() < MAXIMUM_WORDS -> selection.first..index
        else -> index..index
    }

    fun normalized(fixes: List<WordFix>): List<WordFix> {
        val seen = HashSet<String>()
        val out = ArrayList<WordFix>()
        for (fix in fixes) {
            val heard = fix.heard.trim().replace(Regex("\\s+"), " ").take(MAXIMUM_LENGTH).trim()
            val written = fix.written.trim().take(MAXIMUM_LENGTH).trim()
            if (heard.isEmpty() || keyOf(heard) == keyOf(written)) continue
            if (!seen.add(keyOf(heard))) continue
            out.add(WordFix(heard, written))
            if (out.size == MAXIMUM_FIXES) break
        }
        return out
    }

    fun apply(fixes: List<WordFix>, text: String): String {
        if (fixes.isEmpty() || text.isEmpty()) return text
        val compiled = fixes.mapNotNull { fix ->
            val parts = words(fix.heard).map { it.key }
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS) null else Compiled(parts, fix.written)
        }
        if (compiled.isEmpty()) return text
        val all = words(text)
        val sb = StringBuilder()
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
            if (best == null) { index++; continue }
            val last = all[index + best.words.size - 1]
            sb.append(text, copied, all[index].start).append(text, all[index].start, keep).append(best.written)
            copied = last.end
            index += best.words.size
        }
        return sb.append(text, copied, text.length).toString()
    }
}
