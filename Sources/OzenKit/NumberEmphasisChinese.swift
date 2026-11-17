import Foundation

/// Chinese has no spaces between words, so numbers are found by scanning the
/// characters: a spelled number ("三十八", "两", "十五") or digits, then what it
/// counts ("片", "次", "点", "毫克"). The character "一" is also part of many
/// ordinary words ("一起", "一样", "统一", "万一"), so a lone "一" counts only
/// before a few units, and a spelled run that is not a real number ("万一",
/// "千万") is skipped. A missed highlight is better than a wrong one.
enum ChineseNumberEmphasis {
    static func ranges(in text: String) -> [Range<String.Index>] {
        let line = Line(text)
        var spans: [(Int, Int)] = []
        var i = 0
        while i < line.count {
            if line.starts(i, "百分之"), !line.isNumber(i - 1), let percent = line.readNumber(i + 3) {
                spans.append((i, percent.end))
                i = percent.end
            } else if line.isNumber(i) {
                guard let number = line.readNumber(i) else {
                    i = line.runEnd(i)
                    continue
                }
                if !line.starts(i - 1, "第"), let span = line.extend(number, from: i) {
                    spans.append(span)
                    i = span.1
                } else {
                    i = number.end
                }
            } else if line.starts(i, "半"), !line.isNumber(i - 1), let end = line.halfEnd(i) {
                spans.append((i, end))
                i = end
            } else {
                i += 1
            }
        }
        return spans.map { line.range($0.0, $0.1) }
    }

    static func hasListableNumber(_ text: String) -> Bool {
        ranges(in: text).contains { range in
            text[range].unicodeScalars.contains { $0.properties.numericType == .decimal || "零〇二三四五六七八九十百千万亿两半".unicodeScalars.contains($0) }
        }
    }
}

private struct Number {
    var end: Int
    var digits: Bool
    var count: Int
    var isOne: Bool
    var approx: Bool
}

private struct Line {
    let scalars: [Unicode.Scalar]
    let indices: [String.Index]
    let endIndex: String.Index
    var count: Int { scalars.count }

    init(_ text: String) {
        scalars = Array(text.unicodeScalars)
        indices = Array(text.unicodeScalars.indices)
        endIndex = text.endIndex
    }

    func range(_ start: Int, _ end: Int) -> Range<String.Index> {
        indices[start]..<(end < indices.count ? indices[end] : endIndex)
    }

    func starts(_ i: Int, _ word: String) -> Bool {
        let letters = Array(word.unicodeScalars)
        guard i >= 0, i + letters.count <= count else { return false }
        return (0..<letters.count).allSatisfy { scalars[i + $0] == letters[$0] }
    }

    func isDigit(_ i: Int) -> Bool { i >= 0 && i < count && scalars[i].properties.numericType == .decimal }
    func isSpelled(_ i: Int) -> Bool { i >= 0 && i < count && "零〇一二三四五六七八九十百千万亿两".unicodeScalars.contains(scalars[i]) }
    func isNumber(_ i: Int) -> Bool { isDigit(i) || isSpelled(i) }

    func firstMatch(_ i: Int, _ words: [String]) -> String? {
        words.first { starts(i, $0) }
    }

    func runEnd(_ i: Int) -> Int {
        var j = i
        while isSpelled(j) { j += 1 }
        return j
    }

    func readNumber(_ i: Int) -> Number? {
        if isDigit(i) {
            var j = i
            while true {
                if isDigit(j) {
                    j += 1
                } else if isDigit(j - 1), isDigit(j + 1), ".:-/".unicodeScalars.contains(scalars[j]) {
                    j += 1
                } else if isDigit(j - 1), j < count, scalars[j] == ",", isDigit(j + 1), isDigit(j + 2), isDigit(j + 3), !isDigit(j + 4) {
                    j += 1
                } else {
                    break
                }
            }
            if j < count, scalars[j] == "%" || scalars[j] == "％" { j += 1 }
            return Number(end: j, digits: true, count: j - i, isOne: false, approx: false)
        }
        guard isSpelled(i) else { return nil }
        let end = runEnd(i)
        let run = Array(scalars[i..<end])
        let check = Line.validate(run)
        guard check.valid else { return nil }
        return Number(end: end, digits: false, count: run.count, isOne: run == ["一"], approx: check.approx)
    }

    private static let digitLetters = Set("一二三四五六七八九两".unicodeScalars)
    private static let zeroLetters = Set("零〇".unicodeScalars)
    private static let approxPairs: Set<String> = ["一两", "两三", "二三", "三四", "四五", "五六", "六七", "七八", "八九"]
    private static let smallRanks: [Unicode.Scalar: Int] = ["十": 1, "百": 2, "千": 3]
    private static let bigRanks: [Unicode.Scalar: Int] = ["万": 4, "亿": 5]

    static func validate(_ run: [Unicode.Scalar]) -> (valid: Bool, approx: Bool) {
        if run.count == 1 { return (digitLetters.contains(run[0]) || run[0] == "十", false) }
        if run.allSatisfy({ digitLetters.contains($0) || zeroLetters.contains($0) }) {
            let pair = run.count == 2 && approxPairs.contains(String(String.UnicodeScalarView(run)))
            return (pair, pair)
        }
        var i = 0
        var segment = Int.max
        var big = Int.max
        var scaled = false
        while i < run.count {
            let c = run[i]
            if zeroLetters.contains(c) {
                guard scaled, i + 1 < run.count, digitLetters.contains(run[i + 1]) else { return (false, false) }
                i += 1
            } else if c == "十", i == 0 {
                segment = 1
                scaled = true
                i += 1
            } else if digitLetters.contains(c) {
                if c == "两", i + 1 < run.count, run[i + 1] == "十" { return (false, false) }
                if i + 1 < run.count, let rank = smallRanks[run[i + 1]] {
                    guard rank < segment else { return (false, false) }
                    segment = rank
                    scaled = true
                    i += 2
                } else if i + 1 < run.count, let rank = bigRanks[run[i + 1]] {
                    guard rank < big else { return (false, false) }
                    big = rank
                    segment = Int.max
                    scaled = true
                    i += 2
                } else {
                    guard i + 1 == run.count else { return (false, false) }
                    i += 1
                }
            } else if let rank = bigRanks[c], scaled, i > 0 {
                guard rank < big else { return (false, false) }
                big = rank
                segment = Int.max
                i += 1
            } else {
                return (false, false)
            }
        }
        return (true, false)
    }

    private static let units = [
        "毫克", "毫升", "公斤", "公里", "厘米", "千克", "千米", "小时", "钟头", "分钟", "星期", "礼拜",
        "粒", "颗", "滴", "勺", "杯", "瓶", "盒", "袋", "支", "针", "次", "遍", "片", "位", "岁", "天", "周", "年", "秒", "度",
        "元", "号", "米", "克", "斤", "升", "倍",
    ]
    private static let unitsAfterOne = [
        "毫克", "毫升", "公斤", "公里", "厘米", "千克", "千米", "小时", "钟头", "分钟", "粒", "颗", "滴", "勺", "针", "次", "秒", "米", "克", "斤", "升",
    ]
    private static let doseUnits: Set<String> = ["次", "遍", "片", "粒", "颗", "滴", "勺", "针", "毫克", "毫升"]
    private static let latinUnits = ["mcg", "mg", "ml", "kg", "cm", "cc", "g"]
    private static let monthLikeAfterGe = ["月", "星期", "礼拜", "小时", "钟头"]
    private static let decimalUnits = ["公斤", "公里", "厘米", "度", "斤", "米", "元", "克"]
    private static let everyPeriods = ["个月", "个星期", "个礼拜", "个小时", "小时", "星期", "礼拜", "天", "周", "月", "年"]
    private static let onePeriods = ["个月", "个星期", "个礼拜", "个小时", "小时", "星期", "礼拜", "天", "周", "年"]

    func extend(_ number: Number, from start: Int) -> (Int, Int)? {
        let end = number.end
        if starts(end, "分之"), let part = readNumber(end + 2), !part.approx { return (start, part.end) }
        if starts(end, "点") { return clock(number, from: start) }
        if let found = unit(at: end, number, from: start) {
            var from = start
            if found.dose { from = periodStart(before: start) }
            return (from, found.end)
        }
        if number.digits || (number.count >= 2 && !number.approx) { return (start, end) }
        return nil
    }

    private func periodStart(before start: Int) -> Int {
        for period in Line.everyPeriods {
            let length = period.unicodeScalars.count
            if starts(start - length, period), starts(start - length - 1, "每") { return start - length - 1 }
        }
        for period in Line.onePeriods {
            let length = period.unicodeScalars.count
            if starts(start - length, period), starts(start - length - 1, "一"), !isNumber(start - length - 2) { return start - length - 1 }
        }
        return start
    }

    private func isLatinLetter(_ i: Int) -> Bool {
        guard i >= 0, i < count else { return false }
        let value = scalars[i].value
        return (65...90).contains(value) || (97...122).contains(value)
    }

    private func latinUnit(at i: Int) -> Int? {
        for word in Line.latinUnits {
            let letters = Array(word.unicodeScalars)
            guard i + letters.count <= count else { continue }
            let same = (0..<letters.count).allSatisfy { Unicode.Scalar(scalars[i + $0].value | 0x20) == letters[$0] }
            if same, !isLatinLetter(i + letters.count) { return i + letters.count }
        }
        return nil
    }

    private func unit(at e: Int, _ number: Number, from start: Int) -> (end: Int, dose: Bool)? {
        if number.digits {
            let k = e < count && scalars[e] == " " ? e + 1 : e
            if let end = latinUnit(at: k) { return (end, true) }
        }
        if starts(e, "个") {
            if starts(e + 1, "半"), let word = firstMatch(e + 2, ["小时", "钟头", "月", "星期", "礼拜"]) {
                return (e + 2 + word.unicodeScalars.count, false)
            }
            if let word = firstMatch(e + 1, Line.monthLikeAfterGe) { return (e + 1 + word.unicodeScalars.count, false) }
            return number.isOne ? nil : (e + 1, false)
        }
        if starts(e, "块") {
            if starts(e + 1, "钱") { return (e + 2, false) }
            return number.isOne || starts(e + 1, "儿") ? nil : (e + 1, false)
        }
        if number.isOne, starts(e, "片"), starts(e + 1, "药") { return (e + 1, true) }
        guard let word = firstMatch(e, number.isOne ? Line.unitsAfterOne : Line.units) else { return nil }
        let length = word.unicodeScalars.count
        if word == "次" {
            if starts(e + 1, "性") { return nil }
            if number.isOne, let before = start > 0 ? scalars[start - 1] : nil, "上下这那前后每".unicodeScalars.contains(before) { return nil }
        }
        if word == "年", starts(e + 1, "级") { return nil }
        var end = e + length
        if word == "度", isSpelled(end) || isDigit(end), !isNumber(end + 1), firstMatch(end + 1, ["个", "次", "天", "点"]) == nil,
           let tenth = readNumber(end), tenth.count == 1 {
            end = tenth.end
        }
        return (end, Line.doseUnits.contains(word))
    }

    private func clock(_ number: Number, from start: Int) -> (Int, Int)? {
        let p = number.end + 1
        if !number.digits, start > 0, "这那".unicodeScalars.contains(scalars[start - 1]) { return nil }
        if let tenth = readNumber(p), tenth.count == 1, !tenth.isOne || tenth.digits, let word = firstMatch(tenth.end, Line.decimalUnits) {
            return (start, tenth.end + word.unicodeScalars.count)
        }
        var end = p
        if starts(p, "钟") || starts(p, "整") || starts(p, "半") {
            end = p + 1
        } else if let minute = readNumber(p), !minute.approx {
            if starts(minute.end, "刻") {
                end = minute.end + 1
            } else if starts(minute.end, "分"), !starts(minute.end, "分之") {
                end = minute.end + 1
            } else if minute.digits || minute.count >= 2 {
                end = minute.end
            }
        }
        if number.isOne, end == p { return nil }
        return (start, end)
    }

    func halfEnd(_ i: Int) -> Int? {
        firstMatch(i + 1, ["个小时", "小时", "个钟头", "钟头", "分钟", "个月", "年", "公斤", "斤"]).map { i + 1 + $0.unicodeScalars.count }
    }
}
