package com.arbelonson.ozen.core

data class WordFix(val heard: String, val written: String)

object WordFixes {
    const val MAXIMUM_FIXES = 100
    const val MAXIMUM_LENGTH = 40
    private const val MAXIMUM_WORDS = 4
    private const val PREFIX_LETTERS = "והבלמשכ"
    private const val MINIMUM_PREFIXED_LETTERS = 3
    private const val MAQAF = 0x05BE
    private const val FINAL_SIGMA = 0x03C2
    private const val SIGMA = 0x03C3
    private const val DOTTED_CAPITAL_I = 0x0130
    private const val REPEAT_MARK = 0x3005

    /**
     * A run of text between spaces (or a maqaf): [start] to [end] is its part from the first letter or digit to the last.
     * [replacedEnd] is [end], or one past a geresh (or an apostrophe standing in for one) right after the last Hebrew letter:
     * it belongs to the word, as at the end of George spelt in Hebrew, so a fix replaces it too, while [key] still looks past it.
     * The closing quote of a quoted word (‘שלום’) is no geresh, and stays.
     * [letters] are the folded characters [key] is made of, without the marks it looks past, and [at] where each one is in the
     * line: two plain arrays rather than a boxed pair per character, since a line still being written is taken apart at every word.
     * [openAfter] is which single quotes are still open past it, as [openQuote] bits, so the next words can be read from there.
     */
    private class Word(
        val tokenStart: Int,
        val tokenEnd: Int,
        val start: Int,
        val end: Int,
        val key: String,
        val letters: IntArray,
        val at: IntArray,
        val replacedEnd: Int,
        val openAfter: Int,
    )
    /**
     * [exactly] when the fix only respells its own words in ways matching looks past: an alef given its hamza, "don’t" written "don't".
     * [inside] when its one heard word is looked for inside words too ([readsInsideWords]).
     */
    private class Compiled(val words: List<String>, val joints: List<Int>, val written: String, val exactly: Boolean, val inside: Boolean)

    private fun isHebrewMark(c: Int) = c in 0x0591..0x05C7 && c != MAQAF && c != 0x05C0 && c != 0x05C3 && c != 0x05C6

    private fun isMark(c: Int) = isHebrewMark(c) || c in 0x064B..0x065F || c == 0x0670 || c == 0x0640

    private fun isLetter(c: Int) = Character.isLetterOrDigit(c)

    private fun isCore(c: Int) = isLetter(c) || Character.isAlphabetic(c) || isMark(c) || when (Character.getType(c).toByte()) {
        Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** A dotted capital I keeps its dot, as the iPhone's lowercase keeps it, so a fix that only adds the dot is a fix on both phones. */
    private fun caseFolded(c: Int) = if (c == DOTTED_CAPITAL_I) c else Character.toLowerCase(c).let { if (it == FINAL_SIGMA) SIGMA else it }

    /** The old comparison, only capital letters and the Greek final sigma: it decides whether a fix changes anything at all. */
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

    /**
     * Lowercase, with the Greek final sigma made a plain sigma, so a Greek fix typed in capitals finds the word ending in a final sigma,
     * and a dotted capital I a plain i. A curly, modifier-letter or Hebrew geresh apostrophe is a straight one, a curly quote or a
     * gershayim a straight quote (the phone's keyboard types one, the engine writes the other), and an alef with hamza or madda is a
     * plain alef. The alef maqsura and ta marbuta stay as they are: a fix replaces text, and folding them would let a name rewrite an
     * everyday word.
     */
    private fun folded(c: Int) = when (val lower = Character.toLowerCase(c)) {
        FINAL_SIGMA -> SIGMA
        0x2018, 0x2019, 0x02BC, 0x05F3 -> 0x27
        0x201C, 0x201D, 0x05F4 -> 0x22
        0x0623, 0x0625, 0x0622 -> 0x0627
        else -> lower
    }

    private fun isSpace(c: Int) = Character.isWhitespace(c) || Character.isSpaceChar(c)

    /** The iPhone's key: words split at spaces only, so two words and the same two joined by a maqaf stay different keys. */
    private fun keyOf(text: String): String {
        val core = words(text, bare = true, atMaqaf = false).joinToString(" ") { it.key }
        return if (core.isBlank()) text.filterNot { isHebrewMark(it.code) }.trim().map { folded(it.code).toChar() }.joinToString("") else core
    }

    /**
     * The bit of a quote left open that [c] closes: an apostrophe closes one
     * opened by an apostrophe, a right single quote one opened by a left
     * single quote. Two bits in place of a set of the closing marks, so no
     * mark is boxed.
     */
    private fun openQuote(c: Int) = when (c) {
        0x27 -> 1
        0x2019 -> 2
        else -> 0
    }

    /** The [openQuote] bit [c] sets when it opens a quote before a word's first letter. */
    private fun opensQuote(c: Int) = when (c) {
        0x27 -> 1
        0x2018 -> 2
        else -> 0
    }

    /**
     * The words of [text], split only at spaces and, when [atMaqaf], at a
     * maqaf: "3.5", "e-mail" and "10:30" are one word each, as on the iPhone.
     * A run with no letter or digit is a word only when [bare] asks for it.
     * A single quote before a word's first letter stays open until the
     * matching mark after the last letter of that word or a later one. That
     * mark right after a Hebrew letter is the closing quote (‘שלום’), not a
     * geresh, unless another apostrophe-like mark follows it to close the
     * quote ('ג'ורג''); the Hebrew geresh is never a quote.
     */
    private fun words(text: String, bare: Boolean = false, atMaqaf: Boolean = true): List<Word> =
        ArrayList<Word>().also { readWords(text, bare, atMaqaf, it, from = 0, quotes = 0) }

    /** [words] of [text] from [from] on, with [quotes] still open there (see [Word.openAfter]), added to [out]. */
    private fun readWords(text: String, bare: Boolean, atMaqaf: Boolean, out: ArrayList<Word>, from: Int, quotes: Int) {
        fun isBreak(c: Int) = isSpace(c) || (atMaqaf && c == MAQAF)
        var open = quotes
        var i = from
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
                open = if (marksOnly) open and openQuote(c).inv() else open or opensQuote(c)
                at += Character.charCount(c)
            }
            val after = if (end < j) text.codePointAt(end) else -1
            val geresh = after >= 0 && folded(after) == 0x27 && letters.lastOrNull() in 0x05D0..0x05EA
            val past = if (after >= 0) end + Character.charCount(after) else end
            val doubled = past < j && folded(text.codePointAt(past)) == 0x27
            val replacedEnd = if (geresh && ((open and openQuote(after)) == 0 || doubled)) past else end
            at = replacedEnd
            while (at < j) {
                val c = text.codePointAt(at)
                open = open and openQuote(c).inv()
                at += Character.charCount(c)
            }
            if (bare || start < end) out.add(Word(i, j, start, end, String(letters, 0, letters.size), letters, places, replacedEnd, open))
            i = j
        }
    }

    private class LineWords(val text: String, val words: List<Word>)

    /** The two lines [lineWords] read last, newest first, read and replaced whole like [lastCompiled]. */
    private class RecentLines(val newest: LineWords, val older: LineWords?)

    @Volatile
    private var recentLines: RecentLines? = null

    /** How many of [line]'s first words [text] has as they are: each word, and the space after it, before the first change. */
    private fun keptWords(line: LineWords?, text: String): Int {
        if (line == null) return 0
        val shown = line.text
        val limit = minOf(shown.length, text.length)
        var same = 0
        while (same < limit && shown[same] == text[same]) same++
        var kept = 0
        while (kept < line.words.size && line.words[kept].tokenEnd < same) kept++
        return kept
    }

    /**
     * [words] of [text], reusing the words of the line read just before
     * when [text] begins the same way. A line still being written comes
     * again at every word with all of its words so far, and when a fix's
     * heard word is on it, taking all of it apart again cost about 35 KB
     * per word on a line of a hundred Hebrew words. Each word follows only
     * from the text up to the space after it and the quotes left open
     * before it, so a reused word is the word reading the line again
     * finds. Two lines are kept: the line as heard and the line as shown
     * with the fixes made on it come in turn. A line read again as it is,
     * as its doubtful words are right after it, gets its words back as they
     * were read, so the other line stays kept.
     */
    private fun lineWords(text: String): List<Word> {
        val recent = recentLines
        if (recent != null) {
            if (recent.newest.text == text) return recent.newest.words
            val older = recent.older
            if (older != null && older.text == text) {
                recentLines = RecentLines(older, recent.newest)
                return older.words
            }
        }
        val newestKept = keptWords(recent?.newest, text)
        val olderKept = keptWords(recent?.older, text)
        val line = if (olderKept > newestKept) recent?.older else recent?.newest
        val kept = maxOf(newestKept, olderKept)
        val out = ArrayList<Word>(kept + 8)
        for (index in 0 until kept) out.add(line!!.words[index])
        val last = if (kept > 0) line!!.words[kept - 1] else null
        readWords(text, bare = false, atMaqaf = true, out, from = last?.tokenEnd ?: 0, quotes = last?.openAfter ?: 0)
        recentLines = RecentLines(LineWords(text, out), recent?.newest)
        return out
    }

    /**
     * The one space or maqaf between [word] and [next], with no punctuation on either side of it, or null. A name's ending geresh
     * is part of [word] (its [Word.replacedEnd]), so "George Bush" spelt in Hebrew is two joined words.
     */
    private fun joint(text: String, word: Word, next: Word): Int? {
        if (word.replacedEnd != word.tokenEnd || next.start != next.tokenStart || next.tokenStart != word.tokenEnd + 1) return null
        return text[word.tokenEnd].code.takeIf { it == ' '.code || it == MAQAF }
    }

    /** Whether [next] follows [word] after one plain space, with no punctuation on either side of that space. */
    private fun joined(text: String, word: Word, next: Word) = joint(text, word, next) == ' '.code

    private fun isDash(c: Int) = c == '-'.code || c == MAQAF || c == 0x2010 || c == 0x2011

    private fun isPunctuation(c: Int) = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * Where [text] from [from] stops reading [written], when it reads all of
     * it there, case, niqqud and a maqaf against a hyphen aside, up to the
     * end of a word (anywhere, unless [toWordEnd]); null when it does not.
     * The line may also differ from [written] the ways heard words may (an
     * apostrophe, an alef's hamza, Arabic vowel marks), and may lack a
     * punctuation mark of [written] ("Dr Cohen" for "Dr. Cohen") anywhere
     * but between the letters of the heard words, [keep] to [end]: there the
     * mark is a change the fix makes ("dont" written "don't"). A fix that
     * only respells ([exactly]) takes none of these differences: they are
     * what it is for.
     */
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

    /** Where a written form may start in [word]: at its first letter, or past Hebrew prefix letters, as a heard word may take them. */
    private fun readingStarts(word: Word): List<Int> {
        val out = arrayListOf(word.start)
        for (n in 1..3) {
            if (word.letters.size <= n || word.letters[n - 1].toChar() !in PREFIX_LETTERS) break
            out.add(word.at[n])
        }
        return out
    }

    /**
     * Whether the line already reads [fix]'s written form around the heard
     * words at [index], whose letters start at [keep]: from there, or from a
     * word before them, through the end of the last heard word. A fix that
     * adds words after the heard one ("dana" written "Dana Cohen") or before
     * it ("cohen" written "Avi Cohen", "Dr. Cohen") would otherwise write
     * them twice on a line that has them. Each space or maqaf the reading
     * crosses takes a character of the written form, so it starts no more
     * words back than that. A Chinese or Japanese fix's reading may stop
     * inside the next word (see [endsReading]).
     */
    private fun writtenAlready(text: String, all: List<Word>, index: Int, keep: Int, fix: Compiled): Boolean {
        val end = all[index + fix.words.size - 1].end
        return (maxOf(0, index - fix.written.length)..index).any { at ->
            readingStarts(all[at]).any { from ->
                val stop = if (from <= keep) readsAlready(text, from, fix.written, fix.exactly, keep, end, toWordEnd = !fix.inside) else null
                stop != null && stop >= end && (!fix.inside || endsReading(text, stop))
            }
        }
    }

    /**
     * Whether a reading of a Chinese or Japanese fix's written form that
     * stops at [stop] ends there as a word: at a word's end, or before a
     * Chinese or Japanese character the line runs on into with no space
     * ("トム ハンクス" on "トム ハンクスが来た", "王 医生" on "王 医生来了"),
     * though not inside a katakana run ("トム ハンクスソン" is another name).
     */
    private fun endsReading(text: String, stop: Int): Boolean {
        if (stop >= text.length || !isCore(text.codePointAt(stop))) return true
        val next = text.codePointAt(stop)
        return isJapanese(next) && !(stop > 0 && isKatakana(next) && isKatakana(text.codePointBefore(stop)))
    }

    /**
     * Whether [text] already reads [fix]'s written form around its heard
     * characters at [at] inside a Chinese or Japanese word: from there or
     * from a character before them, through their end. Those words run on
     * without spaces, so the reading may start and stop inside one ("京"
     * written "北京" on "北京市"), and a fix that adds characters before or
     * after the heard ones would otherwise write them twice.
     */
    private fun writtenAlreadyInside(text: String, at: Int, fix: Compiled): Boolean {
        val end = at + fix.words[0].length
        val lowest = maxOf(0, at - fix.written.length)
        val starts = generateSequence(at) { if (it > lowest) it - Character.charCount(text.codePointBefore(it)) else null }
        return starts.any { from -> (readsAlready(text, from, fix.written, fix.exactly, at, end, toWordEnd = false) ?: -1) >= end }
    }

    /**
     * Where the heard word starts inside [word], or null when it is not
     * there. Hebrew prefix letters may come before a Hebrew heard word; a
     * [lone] heard word of two letters takes none, because it ends too many
     * other words.
     */
    private fun prefixEnd(word: Word, heard: String, lone: Boolean): Int? {
        if (word.key == heard) return word.start
        if (heard.isEmpty() || heard.codePointAt(0) !in 0x05D0..0x05EA) return null
        if (lone && heard.codePointCount(0, heard.length) < MINIMUM_PREFIXED_LETTERS) return null
        val everyday = HebrewText.everydayWordsNotNames[heard].orEmpty()
        for (n in 1..3) {
            if (word.letters.size <= n) return null
            if (word.letters[n - 1].toChar() !in PREFIX_LETTERS) return null
            if (word.key.length - n != heard.length || !word.key.regionMatches(n, heard, 0, heard.length)) continue
            // A short name hides in everyday words ("full" holds Lea), also behind a prefix of their own ("when full").
            return if ((0 until n).any { word.key.substring(it) in everyday }) null else word.at[n]
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

    /** A kanji as a run of them reads on: an ideograph, 〆 and 〇 among them, or the repeat mark 々, which writes the one before it again (佐々木, 時々). */
    private fun isHan(c: Int) = c in 0x3005..0x3007 || Character.isIdeographic(c) && c !in 0x3000..0x303F

    private fun isKatakanaLetter(c: Int) = c in 0x30A1..0x30FA || c == 0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF6F || c in 0xFF71..0xFF9D

    /** Katakana as a run of it reads on: its letters, small ones too, the long-vowel mark ー, its repeat marks and the voiced-sound marks. */
    private fun isKatakana(c: Int) = isKatakanaLetter(c) || c in 0x30FC..0x30FE || c == 0xFF70 || c in 0xFF9E..0xFF9F || c in 0x3099..0x309C

    private fun isHiragana(c: Int) = c in 0x3041..0x3096 || c in 0x309D..0x309F

    private fun isJapanese(c: Int) = isHan(c) || isKatakana(c) || isHiragana(c)

    /**
     * Whether a one-word [heard] word is looked for inside words too, as
     * Chinese and Japanese are written without spaces: Han characters and
     * kana, with a middle dot between names (ジョン・スミス), holding a Han
     * character or a katakana letter. One all in hiragana is not: hiragana
     * runs on through particles and verb endings (さくらが, 高かった), so
     * where it starts or stops is no word's edge. The repeat mark 々 writes
     * the character before it again, so it is not the Han character a
     * heard word must hold, and one starting with it (々, 々木) is not read
     * inside words either.
     */
    private fun readsInsideWords(heard: String): Boolean {
        if (heard.isEmpty() || heard.codePointAt(0) == REPEAT_MARK) return false
        var anchored = false
        var at = 0
        while (at < heard.length) {
            val c = heard.codePointAt(at)
            if (!isJapanese(c) && c != 0x30FB && c != 0xFF65) return false
            anchored = anchored || (isHan(c) && c != REPEAT_MARK) || isKatakanaLetter(c)
            at += Character.charCount(c)
        }
        return anchored
    }

    private fun hasJapanese(text: String, word: Word): Boolean {
        var at = word.start
        while (at < word.end) {
            val c = text.codePointAt(at)
            if (isJapanese(c)) return true
            at += Character.charCount(c)
        }
        return false
    }

    /**
     * Whether [heard] at [at] in [text] takes in the whole katakana run at
     * each of its ends that is katakana: トム in トムさん or トム・クルーズ,
     * not in トムソン or アトム.
     */
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

    /** The words to tap in the fix-word dialog, each as the line writes it: a name's ending geresh with it, the punctuation around it left out. */
    fun wordsOf(text: String): List<String> = words(text).map { text.substring(it.start, it.replacedEnd) }

    /** Whether word [first] and the next one in [text] are one plain space apart, so a phrase can cover both. */
    fun joinedBySpace(text: String, first: Int): Boolean {
        val all = words(text)
        return first + 1 < all.size && joined(text, all[first], all[first + 1])
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

    /** Whether [fix] can go into [fixes]: a valid fix, and room for it unless it replaces the one with the same heard words. */
    fun canAdd(fix: WordFix, fixes: List<WordFix>): Boolean {
        if (listOf(fix.heard, fix.written).any { it.trim().length > MAXIMUM_LENGTH }) return false
        val one = normalized(listOf(fix)).firstOrNull() ?: return false
        return fixes.size < MAXIMUM_FIXES || fixes.any { keyOf(it.heard) == keyOf(one.heard) }
    }

    /** Why "Add fix" can't take a fix; [Missing] while a box is still empty, which needs no word. */
    enum class Refusal { Missing, TooLong, SameWord, HeardWords, Full }

    /** Why [fix] can't go into [fixes], or null exactly when [canAdd] takes it. */
    fun refusal(fix: WordFix, fixes: List<WordFix>): Refusal? {
        if (canAdd(fix, fixes)) return null
        val heard = fix.heard.trim()
        val written = fix.written.trim()
        return when {
            heard.isEmpty() || written.isEmpty() -> Refusal.Missing
            heard.length > MAXIMUM_LENGTH || written.length > MAXIMUM_LENGTH -> Refusal.TooLong
            caseKeyOf(heard.replace(Regex("\\s+"), " ")) == caseKeyOf(written) -> Refusal.SameWord
            normalized(listOf(fix)).isEmpty() -> Refusal.HeardWords
            else -> Refusal.Full
        }
    }

    /** Said under a grey "Add fix", so the button doesn't just look broken. */
    fun refusalNote(refusal: Refusal?): String? = when (refusal) {
        null, Refusal.Missing -> null
        Refusal.TooLong -> tr("עד %1 אותיות ורווחים בכל שדה.", "Up to %1 letters and spaces in each box.", listOf("$MAXIMUM_LENGTH"))
        Refusal.SameWord -> tr("״מה שנשמע״ ו״מה שייכתב״ זהים. תיקון לא יכול לשנות רק אותיות גדולות וקטנות.", "“Heard” and “Written” are the same. A fix can’t change only capital letters.")
        Refusal.HeardWords -> tr("ב״מה שנשמע״: עד %1 מילים, רק עם רווחים ביניהן.", "In “Heard”: up to %1 words, with only spaces between them.", listOf("$MAXIMUM_WORDS"))
        Refusal.Full -> tr("הרשימה מלאה. מחקו תיקון כדי להוסיף אחר.", "The list is full. Delete a fix to add another.")
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

    /**
     * [text] with [fixes] written in. A line still being written comes again
     * at every word with all of its words so far, so a line none of the
     * fixes' heard words are on is given back as it is, found by reading
     * its letters without taking it apart into words.
     */
    fun apply(fixes: List<WordFix>, text: String): String {
        if (fixes.isEmpty() || text.isEmpty()) return text
        val compiled = compile(fixes)
        if (compiled.compiled.none { mayFix(text, it) }) return text
        return rewrite(fixes, text, compiled).text
    }

    /**
     * Whether [fix] could change [text]: its first heard word is on the
     * line, folded as a word's [Word.key] folds it and with the marks the
     * key leaves out skipped, or a Chinese or Japanese one is there as
     * written. Every word [prefixEnd] takes for it ends in it, so a line
     * without it is one [rewrite] gives back unchanged.
     */
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

    /**
     * The words the model was unsure of in [text], after [fixes] rewrote it.
     * A word inside a rewritten stretch (the "york" of "New York" written as
     * "NYC") is replaced by the words written there, once, so the doubt stays
     * on the new words; each entry is matched to its own word of [text], so
     * a repeated word is not marked in place of another.
     */
    fun applyToUncertain(fixes: List<WordFix>, text: String, uncertain: List<String>): List<String> {
        if (uncertain.isEmpty()) return uncertain
        val rewritten = rewrite(fixes, text, writing = false)
        val taken = HashSet<Int>()
        val written = HashMap<Span, Pair<Int, Boolean>>()
        val out = ArrayList<String>()
        for (entry in uncertain) {
            // A sure copy takes its own heard copy too, so the doubtful one
            // after it lands on the right word.
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
                // Any doubt about a word of the phrase marks what replaced it.
                for ((at, word) in words.withIndex()) out[before.first + at] = word
                written[span] = before.first to false
            }
        }
        return out
    }

    /** [inside] are the one-word Chinese or Japanese fixes, longest first, looked for inside a word too (see [readsInsideWords]). */
    private class CompiledFixes(val fixes: List<WordFix>, val compiled: List<Compiled>, val inside: List<Compiled>)

    /**
     * The fixes compiled last, kept for the next word: the same list comes
     * with every word of every line, and compiling it again cost about 5 KB
     * per word for a single fix. Read and replaced whole, so a thread that
     * reads it sees one list and what it compiled to.
     */
    @Volatile
    private var lastCompiled: CompiledFixes? = null

    private fun compile(fixes: List<WordFix>): CompiledFixes {
        lastCompiled?.let { if (it.fixes == fixes) return it }
        val compiled = normalized(fixes).mapNotNull { fix ->
            val parts = words(fix.heard)
            if (parts.isEmpty() || parts.size > MAXIMUM_WORDS) null
            else Compiled(
                parts.map { it.key }, parts.zipWithNext { a, b -> joint(fix.heard, a, b) ?: ' '.code }, fix.written,
                keyOf(fix.heard) == keyOf(fix.written), parts.size == 1 && readsInsideWords(parts[0].key),
            )
        }
        val inside = compiled.filter { it.inside }.sortedByDescending { it.words[0].length }
        return CompiledFixes(fixes.toList(), compiled, inside).also { lastCompiled = it }
    }

    /**
     * [text]'s words and where [fixes] rewrite them, with the line they
     * make when [writing]. The doubtful words need only the words and the
     * spans, and writing the line out again for them cost about 3 KB per
     * word on a line of a hundred Hebrew words; without [writing] the
     * [Rewrite.text] is [text] as it came.
     */
    private fun rewrite(fixes: List<WordFix>, text: String, compiledFixes: CompiledFixes? = null, writing: Boolean = true): Rewrite {
        if (fixes.isEmpty() || text.isEmpty()) return Rewrite(text, lineWords(text), emptyList())
        val prepared = compiledFixes ?: compile(fixes)
        val compiled = prepared.compiled
        if (compiled.isEmpty()) return Rewrite(text, lineWords(text), emptyList())
        val inside = prepared.inside
        val all = lineWords(text)
        val sb = if (writing) StringBuilder(text.length + 16) else null
        val spans = ArrayList<Span>()
        var copied = 0
        var index = 0
        while (index < all.size) {
            var best: Compiled? = null
            var keep = 0
            for (n in compiled.indices) {
                val c = compiled[n]
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
                    sb?.append(text, copied, all[index].start)?.append(fixed)
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
            sb?.append(text, copied, all[index].start)?.append(replacement)
            spans.add(Span(index, index + best.words.size - 1, replacement))
            copied = last.replacedEnd
            index += best.words.size
        }
        return Rewrite(sb?.append(text, copied, text.length)?.toString() ?: text, all, spans)
    }
}

/**
 * What became of a word fixed in History, so the dialog can say why a fix
 * was not saved. A fix that waits for room on a full phone is [Saved]: it
 * is written once there is room.
 */
enum class WordFixResult {
    Saved,

    /** The line is gone, is being heard again, or no longer has the word. */
    LineChanged,

    /** The conversation's file is there but couldn't be read just now. */
    Unreadable,
    ;

    /** Said in the dialog when the fix was not saved; null when it was. */
    val note: String?
        get() = when (this) {
            Saved -> null
            LineChanged -> tr("השורה השתנתה או נעלמה, ולכן התיקון לא נשמר.", "The line changed or is gone, so the fix was not saved.")
            Unreadable -> tr("לא היה אפשר לקרוא את השיחה כרגע, ולכן התיקון לא נשמר. אפשר לנסות שוב עוד רגע.", "The conversation couldn’t be read just now, so the fix was not saved. Try again in a moment.")
        }
}
