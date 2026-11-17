package com.arbelonson.ozen.core

/**
 * Chinese has no spaces between words, so numbers are found by scanning the characters: a spelled
 * number or digits, then what it counts ("片", "次", "点", "毫克"). The character "一" is also part
 * of many ordinary words ("一起", "一样", "统一", "万一"), so a lone "一" counts only before a few
 * units, and a spelled run that is not a real number ("万一", "千万") is skipped. A missed
 * highlight is better than a wrong one.
 */
internal object ChineseNumberEmphasis {
    fun ranges(text: String): List<OpenEndRange<Int>> {
        val line = ChineseLine(text)
        val spans = mutableListOf<OpenEndRange<Int>>()
        var i = 0
        while (i < line.size) {
            val percent = if (line.starts(i, "百分之") && !line.isNumber(i - 1)) line.readNumber(i + 3) else null
            if (percent != null) {
                spans.add(i until percent.end)
                i = percent.end
            } else if (line.isNumber(i)) {
                val number = line.readNumber(i)
                if (number == null) {
                    i = line.runEnd(i)
                    continue
                }
                val span = if (line.starts(i - 1, "第")) null else line.extend(number, i)
                if (span != null) {
                    spans.add(span.first until span.second)
                    i = span.second
                } else {
                    i = number.end
                }
            } else if (line.starts(i, "半") && !line.isNumber(i - 1)) {
                val end = line.halfEnd(i)
                if (end != null) {
                    spans.add(i until end)
                    i = end
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return spans
    }

    fun hasListableNumber(text: String): Boolean = ranges(text).any { range ->
        text.substring(range.start, range.endExclusive).any { it.isDigit() || it in "零〇二三四五六七八九十百千万亿" }
    }
}

private class ChineseNumber(val end: Int, val digits: Boolean, val count: Int, val isOne: Boolean, val approx: Boolean)

private val spelledLetters = "零〇一二三四五六七八九十百千万亿两"
private val digitLetters = "一二三四五六七八九两"
private val zeroLetters = "零〇"
private val approxPairs = setOf("一两", "两三", "二三", "三四", "四五", "五六", "六七", "七八", "八九")
private val smallRanks = mapOf('十' to 1, '百' to 2, '千' to 3)
private val bigRanks = mapOf('万' to 4, '亿' to 5)
private val chineseUnits = listOf(
    "毫克", "毫升", "公斤", "公里", "厘米", "千克", "千米", "小时", "钟头", "分钟", "星期", "礼拜",
    "粒", "颗", "滴", "勺", "杯", "瓶", "盒", "袋", "支", "针", "次", "遍", "片", "位", "岁", "天", "周", "年", "秒", "度",
    "元", "号", "米", "克", "斤", "升", "倍",
)
private val unitsAfterOne = listOf(
    "毫克", "毫升", "公斤", "公里", "厘米", "千克", "千米", "小时", "钟头", "分钟", "粒", "颗", "滴", "勺", "针", "次", "秒", "米", "克", "斤", "升",
)
private val doseUnits = setOf("次", "遍", "片", "粒", "颗", "滴", "勺", "针", "毫克", "毫升")
private val latinUnits = listOf("mcg", "mg", "ml", "kg", "cm", "cc", "g")
private val monthLikeAfterGe = listOf("月", "星期", "礼拜", "小时", "钟头")
private val decimalUnits = listOf("公斤", "公里", "厘米", "度", "斤", "米", "元", "克")
private val everyPeriods = listOf("个月", "个星期", "个礼拜", "个小时", "小时", "星期", "礼拜", "天", "周", "月", "年")
private val onePeriods = listOf("个月", "个星期", "个礼拜", "个小时", "小时", "星期", "礼拜", "天", "周", "年")

private fun validateChineseRun(run: String): Pair<Boolean, Boolean> {
    if (run.length == 1) return Pair(run[0] in digitLetters || run[0] == '十', false)
    if (run.all { it in digitLetters || it in zeroLetters }) {
        val pair = run.length == 2 && run in approxPairs
        return Pair(pair, pair)
    }
    var i = 0
    var segment = Int.MAX_VALUE
    var big = Int.MAX_VALUE
    var scaled = false
    while (i < run.length) {
        val c = run[i]
        if (c in zeroLetters) {
            if (!(scaled && i + 1 < run.length && run[i + 1] in digitLetters)) return Pair(false, false)
            i += 1
        } else if (c == '十' && i == 0) {
            segment = 1
            scaled = true
            i += 1
        } else if (c in digitLetters) {
            if (c == '两' && i + 1 < run.length && run[i + 1] == '十') return Pair(false, false)
            val small = if (i + 1 < run.length) smallRanks[run[i + 1]] else null
            val large = if (i + 1 < run.length) bigRanks[run[i + 1]] else null
            if (small != null) {
                if (small >= segment) return Pair(false, false)
                segment = small
                scaled = true
                i += 2
            } else if (large != null) {
                if (large >= big) return Pair(false, false)
                big = large
                segment = Int.MAX_VALUE
                scaled = true
                i += 2
            } else {
                if (i + 1 != run.length) return Pair(false, false)
                i += 1
            }
        } else if (bigRanks[c] != null && scaled && i > 0) {
            val rank = bigRanks.getValue(c)
            if (rank >= big) return Pair(false, false)
            big = rank
            segment = Int.MAX_VALUE
            i += 1
        } else {
            return Pair(false, false)
        }
    }
    return Pair(true, false)
}

private class ChineseLine(private val text: String) {
    val size: Int get() = text.length

    fun starts(i: Int, word: String): Boolean = i >= 0 && i + word.length <= text.length && text.startsWith(word, i)

    fun isDigit(i: Int): Boolean = i >= 0 && i < text.length && text[i].isDigit()
    fun isSpelled(i: Int): Boolean = i >= 0 && i < text.length && text[i] in spelledLetters
    fun isNumber(i: Int): Boolean = isDigit(i) || isSpelled(i)

    fun firstMatch(i: Int, words: List<String>): String? = words.firstOrNull { starts(i, it) }

    fun runEnd(i: Int): Int {
        var j = i
        while (isSpelled(j)) j++
        return j
    }

    fun readNumber(i: Int): ChineseNumber? {
        if (isDigit(i)) {
            var j = i
            while (true) {
                if (isDigit(j)) {
                    j++
                } else if (isDigit(j - 1) && isDigit(j + 1) && text[j] in ".:-/") {
                    j++
                } else if (isDigit(j - 1) && j < text.length && text[j] == ',' && isDigit(j + 1) && isDigit(j + 2) && isDigit(j + 3) && !isDigit(j + 4)) {
                    j++
                } else {
                    break
                }
            }
            if (j < text.length && (text[j] == '%' || text[j] == '％')) j++
            return ChineseNumber(j, true, j - i, false, false)
        }
        if (!isSpelled(i)) return null
        val end = runEnd(i)
        val run = text.substring(i, end)
        val (valid, approx) = validateChineseRun(run)
        if (!valid) return null
        return ChineseNumber(end, false, run.length, run == "一", approx)
    }

    fun extend(number: ChineseNumber, start: Int): Pair<Int, Int>? {
        val end = number.end
        if (starts(end, "分之")) {
            val part = readNumber(end + 2)
            if (part != null && !part.approx) return Pair(start, part.end)
        }
        if (starts(end, "点")) return clock(number, start)
        val found = unit(end, number, start)
        if (found != null) {
            return Pair(if (found.second) periodStart(start) else start, found.first)
        }
        if (number.digits || (number.count >= 2 && !number.approx)) return Pair(start, end)
        return null
    }

    private fun periodStart(start: Int): Int {
        for (period in everyPeriods) {
            if (starts(start - period.length, period) && starts(start - period.length - 1, "每")) return start - period.length - 1
        }
        for (period in onePeriods) {
            if (starts(start - period.length, period) && starts(start - period.length - 1, "一") && !isNumber(start - period.length - 2)) {
                return start - period.length - 1
            }
        }
        return start
    }

    private fun isLatinLetter(i: Int): Boolean = i >= 0 && i < text.length && text[i] in 'A'..'Z' || i >= 0 && i < text.length && text[i] in 'a'..'z'

    private fun latinUnit(i: Int): Int? {
        for (word in latinUnits) {
            if (i < 0 || i + word.length > text.length) continue
            if (text.regionMatches(i, word, 0, word.length, ignoreCase = true) && !isLatinLetter(i + word.length)) return i + word.length
        }
        return null
    }

    private fun unit(e: Int, number: ChineseNumber, start: Int): Pair<Int, Boolean>? {
        if (number.digits) {
            val k = if (e < text.length && text[e] == ' ') e + 1 else e
            latinUnit(k)?.let { return Pair(it, true) }
        }
        if (starts(e, "个")) {
            if (starts(e + 1, "半")) {
                val word = firstMatch(e + 2, listOf("小时", "钟头", "月", "星期", "礼拜"))
                if (word != null) return Pair(e + 2 + word.length, false)
            }
            val word = firstMatch(e + 1, monthLikeAfterGe)
            if (word != null) return Pair(e + 1 + word.length, false)
            return if (number.isOne) null else Pair(e + 1, false)
        }
        if (starts(e, "块")) {
            if (starts(e + 1, "钱")) return Pair(e + 2, false)
            return if (number.isOne || starts(e + 1, "儿")) null else Pair(e + 1, false)
        }
        if (number.isOne && starts(e, "片") && starts(e + 1, "药")) return Pair(e + 1, true)
        val word = firstMatch(e, if (number.isOne) unitsAfterOne else chineseUnits) ?: return null
        if (word == "次") {
            if (starts(e + 1, "性")) return null
            if (number.isOne && start > 0 && text[start - 1] in "上下这那前后每") return null
        }
        if (word == "年" && starts(e + 1, "级")) return null
        var end = e + word.length
        if (word == "度" && (isSpelled(end) || isDigit(end)) && !isNumber(end + 1) && firstMatch(end + 1, listOf("个", "次", "天", "点")) == null) {
            val tenth = readNumber(end)
            if (tenth != null && tenth.count == 1) end = tenth.end
        }
        return Pair(end, word in doseUnits)
    }

    private fun clock(number: ChineseNumber, start: Int): Pair<Int, Int>? {
        val p = number.end + 1
        if (!number.digits && start > 0 && text[start - 1] in "这那") return null
        val tenth = readNumber(p)
        if (tenth != null && tenth.count == 1 && (!tenth.isOne || tenth.digits)) {
            val word = firstMatch(tenth.end, decimalUnits)
            if (word != null) return Pair(start, tenth.end + word.length)
        }
        var end = p
        if (starts(p, "钟") || starts(p, "整") || starts(p, "半")) {
            end = p + 1
        } else {
            val minute = readNumber(p)
            if (minute != null && !minute.approx) {
                if (starts(minute.end, "刻")) {
                    end = minute.end + 1
                } else if (starts(minute.end, "分") && !starts(minute.end, "分之")) {
                    end = minute.end + 1
                } else if (minute.digits || minute.count >= 2) {
                    end = minute.end
                }
            }
        }
        if (number.isOne && end == p) return null
        return Pair(start, end)
    }

    fun halfEnd(i: Int): Int? =
        firstMatch(i + 1, listOf("个小时", "小时", "个钟头", "钟头", "分钟", "个月", "年", "公斤", "斤"))?.let { i + 1 + it.length }
}
