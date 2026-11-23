import Foundation

/// A correction the person taught the app: wherever a recognizer writes
/// `heard`, show `written` instead.
public struct WordFix: Codable, Hashable, Sendable {
    public var heard: String
    public var written: String

    public init(heard: String, written: String) {
        self.heard = heard
        self.written = written
    }
}

public enum WordFixes {
    public static let maximumFixes = 100
    public static let maximumLength = 40
    static let maximumPhraseWords = 4
    static let prefixLetters: Set<Character> = ["ו", "ה", "ב", "ל", "מ", "ש", "כ"]
    static let minimumPrefixedLetters = 3

    public static func normalized(_ fixes: [WordFix]) -> [WordFix] {
        var seen = Set<String>()
        var result: [WordFix] = []
        for raw in fixes {
            let heard = clip(collapsed(raw.heard))
            let written = clip(raw.written.trimmingCharacters(in: .whitespacesAndNewlines))
            let key = key(heard)
            guard !heard.isEmpty, !written.isEmpty, caseKey(heard) != caseKey(written) else { continue }
            let words = Token.split(Array(heard.unicodeScalars))
            guard words.count <= maximumPhraseWords, !words.contains(where: \.isOnlyMarks), hasCleanJoints(heard), seen.insert(key).inserted else { continue }
            result.append(WordFix(heard: heard, written: written))
            if result.count == maximumFixes { break }
        }
        return result
    }

    /// Whether `fix` can go into `list`: a valid fix short enough to keep
    /// whole, and room for it unless it replaces the one with the same
    /// heard words.
    public static func canAdd(_ fix: WordFix, to list: [WordFix]) -> Bool {
        let fits = [fix.heard, fix.written].allSatisfy { $0.trimmingCharacters(in: .whitespacesAndNewlines).count <= maximumLength }
        guard fits, let one = normalized([fix]).first else { return false }
        return list.count < maximumFixes || list.contains { key($0.heard) == key(one.heard) }
    }

    /// Why "Add fix" can't take a fix; `.missing` while a box is still
    /// empty, which needs no word.
    public enum Refusal: Sendable { case missing, tooLong, sameWord, heardWords, full }

    /// Why `fix` can't go into `list`, or nil exactly when `canAdd` takes it.
    public static func refusal(_ fix: WordFix, to list: [WordFix]) -> Refusal? {
        if canAdd(fix, to: list) { return nil }
        let heard = fix.heard.trimmingCharacters(in: .whitespacesAndNewlines)
        let written = fix.written.trimmingCharacters(in: .whitespacesAndNewlines)
        if heard.isEmpty || written.isEmpty { return .missing }
        if heard.count > maximumLength || written.count > maximumLength { return .tooLong }
        if caseKey(collapsed(heard)) == caseKey(written) { return .sameWord }
        if normalized([fix]).isEmpty { return .heardWords }
        return .full
    }

    /// Said under a grey "Add fix", so the button doesn't just look broken.
    public static func refusalNote(_ refusal: Refusal?) -> String? {
        switch refusal {
        case nil, .missing?: return nil
        case .tooLong?: return tr("עד %1 אותיות ורווחים בכל שדה.", "Up to %1 letters and spaces in each box.", args: ["\(maximumLength)"])
        case .sameWord?: return tr("״מה שנשמע״ ו״מה שייכתב״ זהים. תיקון לא יכול לשנות רק אותיות גדולות וקטנות.", "“Heard” and “Written” are the same. A fix can’t change only capital letters.")
        case .heardWords?: return tr("ב״מה שנשמע״: עד %1 מילים, רק עם רווחים ביניהן.", "In “Heard”: up to %1 words, with only spaces between them.", args: ["\(maximumPhraseWords)"])
        case .full?: return tr("הרשימה מלאה. מחקו תיקון כדי להוסיף אחר.", "The list is full. Delete a fix to add another.")
        }
    }

    /// A fix as VoiceOver reads it: "heard → written" was spoken as
    /// "heard right arrow written".
    public static func spokenLabel(_ fix: WordFix, in language: UILanguage) -> String {
        tr("נשמע %1, ייכתב %2", "Heard %1, written %2", args: ["\(fix.heard)", "\(fix.written)"], in: language)
    }

    public static func adding(_ fix: WordFix, to list: [WordFix]) -> [WordFix] {
        let moved = key(fix.heard)
        let carried = list.map { old in
            key(old.written) == moved ? WordFix(heard: old.heard, written: fix.written) : old
        }
        return normalized([fix] + carried)
    }

    /// Heard words are joined by one space or one maqaf, with no
    /// punctuation on either side of it; a name's ending geresh is part of
    /// its word, so "George Bush" spelt in Hebrew is two joined words.
    private static func hasCleanJoints(_ heard: String) -> Bool {
        let words = Token.split(Array(heard.unicodeScalars))
        for (index, word) in words.enumerated() {
            if index > 0, word.coreStart != 0 { return false }
            if index < words.count - 1, word.replacedEnd != word.scalars.count || !plainJoints.contains(word.gap) { return false }
        }
        return true
    }

    private static let plainJoints: [[Unicode.Scalar]] = [[" "], ["\u{05BE}"]]

    /// Whether two words tapped side by side can be one heard phrase: no
    /// punctuation between them, a name's ending geresh aside, as
    /// `hasCleanJoints` takes them. `left` may be the line up to and
    /// including the left word, so a quote opened before it is seen closing.
    public static func canJoin(_ left: String, _ right: String) -> Bool {
        guard let start = right.unicodeScalars.first, Token.isCore(start),
              let end = Token.split(Array(left.unicodeScalars)).last, end.gap.isEmpty else { return false }
        return end.coreStart < end.replacedEnd && end.replacedEnd == end.scalars.count
    }

    /// The words to tap in Fix a word, each as the line writes it: a name's
    /// ending geresh with it, the punctuation around it left out, as on
    /// Android. Split at spaces and a maqaf, as fixes match words; a run of
    /// punctuation alone is no word.
    public static func wordsOf(_ text: String) -> [String] {
        Token.split(Array(text.unicodeScalars)).filter(\.hasWord).map { String(String.UnicodeScalarView($0.scalars[$0.coreStart..<$0.replacedEnd])) }
    }

    /// Whether word `first` of `wordsOf(text)` and the next are one plain
    /// space apart with no punctuation on either side, so one heard phrase
    /// can cover both, as on Android. The tiles leave the punctuation out,
    /// so this looks at the line itself.
    public static func joinedBySpace(_ text: String, _ first: Int) -> Bool {
        let scalars = Array(text.unicodeScalars)
        let tokens = Token.split(scalars)
        let words = tokens.indices.filter { tokens[$0].hasWord }
        guard words.indices.contains(first), words.indices.contains(first + 1) else { return false }
        let left = words[first], right = words[first + 1]
        guard right == left + 1, tokens[left].gap == [" "] else { return false }
        let end = tokens[...left].reduce(0) { $0 + $1.scalars.count + $1.gap.count } - tokens[left].gap.count
        return canJoin(String(String.UnicodeScalarView(scalars[..<end])), String(String.UnicodeScalarView(tokens[right].scalars)))
    }

    /// `text` with `fixes` written in. The line being written comes again
    /// at every word with all of its words so far, so a line that none of
    /// the fixes' first heard words are on is given back as it is, found
    /// by reading its letters in place (see `mayChange`) rather than taking
    /// it apart into words.
    public static func apply(_ fixes: [WordFix], to text: String) -> String {
        guard !fixes.isEmpty, !text.isEmpty else { return text }
        let prepared = prepare(fixes)
        guard prepared.contains(where: { mayChange(text, $0) }) else { return text }
        return rewrite(fixes, text, prepared: prepared).text
    }

    /// `apply` taking every line apart, for tests that hold the two to the
    /// same answers.
    static func applyReadingEveryWord(_ fixes: [WordFix], to text: String) -> String {
        rewrite(fixes, text).text
    }

    /// Whether `fix` could change `text`. A word `match` takes for it has
    /// the fix's first heard word at its end, as `Token.key` reads it:
    /// folded, without the marks the key leaves out. So does a Chinese or
    /// Japanese word `replacingInside` finds it in, its characters being
    /// their own folded form. A line on which that run of folded letters
    /// is nowhere is one `rewrite` gives back unchanged.
    ///
    /// Words are compared as strings, which treats a letter written with
    /// its accent as one character and as two alike. The letters are read
    /// one at a time here, so this is only trusted when both the heard word
    /// and the line are made of letters that have one way of being written
    /// (`isPlainLetter`); anything else is taken the long way.
    private static func mayChange(_ text: String, _ fix: Prepared) -> Bool {
        let heard = Array(fix.words[0].unicodeScalars)
        guard !heard.isEmpty, heard.allSatisfy(isPlainLetter), text.unicodeScalars.allSatisfy(isPlainLetter) else { return true }
        let line = text.unicodeScalars
        var from = line.startIndex
        while from < line.endIndex {
            if !Token.isMark(line[from]), reads(line, from: from, heard) { return true }
            from = line.index(after: from)
        }
        return false
    }

    private static func reads(_ line: String.UnicodeScalarView, from start: String.UnicodeScalarView.Index, _ heard: [Unicode.Scalar]) -> Bool {
        var at = start
        for wanted in heard {
            while at < line.endIndex, Token.isMark(line[at]) { at = line.index(after: at) }
            guard at < line.endIndex, plainFolded(line[at]) == wanted else { return false }
            at = line.index(after: at)
        }
        return true
    }

    /// Basic Latin and the punctuation below the accented letters, Hebrew,
    /// Arabic without the marks a key keeps, and the dashes, quotes and
    /// spaces of General Punctuation: none has another way of being written
    /// that compares equal, and each folds to one letter of its own.
    private static func isPlainLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x00...0xBF, 0x0590...0x05FF, 0x2002...0x205F: return true
        case 0x0610...0x061A, 0x06D6...0x06ED: return false
        case 0x0600...0x06FF: return true
        default: return false
        }
    }

    private static func plainFolded(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        foldedScalar((0x41...0x5A).contains(scalar.value) ? Unicode.Scalar(scalar.value + 0x20)! : scalar)
    }

    /// The text after `fixes`, its words as they were heard, and which of
    /// those words each fix replaced and with what.
    private struct Rewrite {
        let text: String
        let tokens: [Token]
        let spans: [(first: Int, last: Int, replacement: String)]
    }

    private static func prepare(_ fixes: [WordFix]) -> [Prepared] {
        normalized(fixes).compactMap { fix -> Prepared? in
            let words = Token.split(Array(fix.heard.unicodeScalars))
            guard (1...maximumPhraseWords).contains(words.count) else { return nil }
            let keys = words.map { key(String(String.UnicodeScalarView($0.scalars))) }
            return Prepared(words: keys, joints: words.dropLast().map(\.gap), written: fix.written, exactly: key(fix.heard) == key(fix.written), inside: keys.count == 1 && readsInsideWords(keys[0]))
        }.sorted { $0.words.count > $1.words.count }
    }

    private static func rewrite(_ fixes: [WordFix], _ text: String, prepared ready: [Prepared]? = nil) -> Rewrite {
        guard !fixes.isEmpty, !text.isEmpty else { return Rewrite(text: text, tokens: [], spans: []) }
        let prepared = ready ?? prepare(fixes)
        let inside = prepared.filter(\.inside)
            .map { InsideFix(heard: Array($0.words[0].unicodeScalars), written: $0.written, exactly: $0.exactly) }
            .sorted { $0.heard.count > $1.heard.count }
        guard !prepared.isEmpty else { return Rewrite(text: text, tokens: [], spans: []) }
        let all = Array(text.unicodeScalars)
        let tokens = Token.split(all)
        var starts: [Int] = []
        var start = 0
        for token in tokens {
            starts.append(start)
            start += token.scalars.count + token.gap.count
        }
        var spans: [(first: Int, last: Int, replacement: String)] = []
        var out = String.UnicodeScalarView()
        var index = 0
        var cursor = 0
        while index < tokens.count {
            if let hit = firstMatch(prepared, tokens, at: index) {
                if writtenAlready(all, tokens, starts, at: index, hit) {
                    index += hit.count
                    continue
                }
                out.append(contentsOf: tokens[cursor..<index].isEmpty ? [] : slice(tokens, cursor, index))
                let first = tokens[index]
                let last = tokens[index + hit.count - 1]
                out.append(contentsOf: first.scalars[..<first.coreStart])
                let prefix = first.scalars[first.coreStart..<hit.prefixEnd]
                var replacement = String.UnicodeScalarView(prefix)
                if !prefix.isEmpty, needsMaqaf(hit.written) { replacement.append("\u{05BE}") }
                replacement.append(contentsOf: hit.written.unicodeScalars)
                out.append(contentsOf: replacement)
                spans.append((index, index + hit.count - 1, String(replacement)))
                out.append(contentsOf: last.scalars[last.replacedEnd...])
                out.append(contentsOf: last.gap)
                index += hit.count
                cursor = index
            } else if let fixed = replacingInside(inside, in: tokens[index], of: all, at: starts[index]) {
                out.append(contentsOf: slice(tokens, cursor, index))
                out.append(contentsOf: fixed)
                spans.append((index, index, String(String.UnicodeScalarView(fixed))))
                out.append(contentsOf: tokens[index].gap)
                index += 1
                cursor = index
            } else {
                index += 1
            }
        }
        out.append(contentsOf: cursor < tokens.count ? slice(tokens, cursor, tokens.count) : [])
        return Rewrite(text: String(out), tokens: tokens, spans: spans)
    }

    /// The words the model was unsure of in `text`, after `fixes` rewrote
    /// it, in the form `UncertainWords.ranges` looks for: one normalized
    /// entry per word the line now shows, so the doubt stays on a word fixed
    /// to capitals or after a prefix and a maqaf. A doubtful word inside a
    /// rewritten stretch (the "york" of "New York" written as "NYC") hands
    /// its doubt to the words written there, once; each entry is matched to
    /// its own word of `text`, so a repeated word is not marked in place of
    /// another. A sure copy keeps its mark.
    public static func applyToUncertain(_ fixes: [WordFix], text: String, _ uncertain: [String]) -> [String] {
        guard !fixes.isEmpty, !uncertain.isEmpty else { return uncertain }
        let rewritten = rewrite(fixes, text)
        var taken = Set<Int>()
        var written: [Int: (start: Int, isSure: Bool)] = [:]
        var out: [String] = []
        for entry in uncertain {
            // A sure copy takes its own heard copy too, so the doubtful one
            // after it lands on the right word.
            let isSure = entry.hasPrefix(UncertainWords.sureCopy)
            let word = isSure ? String(entry.dropFirst(UncertainWords.sureCopy.count)) : entry
            let key = Token.split(Array(word.unicodeScalars)).map(\.key).joined(separator: " ")
            guard let index = rewritten.tokens.indices.first(where: { !taken.contains($0) && rewritten.tokens[$0].key == key }) else {
                out += fixedEntry(fixes, entry)
                continue
            }
            taken.insert(index)
            guard let span = rewritten.spans.firstIndex(where: { $0.first <= index && index <= $0.last }) else {
                out.append(entry)
                continue
            }
            let words = shownWords(rewritten.spans[span].replacement)
            if let before = written[span] {
                // Any doubt about a word of the phrase marks what replaced it.
                guard before.isSure, !isSure else { continue }
                out.replaceSubrange(before.start..<(before.start + words.count), with: words)
                written[span] = (before.start, false)
            } else {
                written[span] = (out.count, isSure)
                out += isSure ? words.map { UncertainWords.sureCopy + $0 } : words
            }
        }
        return out
    }

    private static func fixedEntry(_ fixes: [WordFix], _ entry: String) -> [String] {
        let isSure = entry.hasPrefix(UncertainWords.sureCopy)
        let word = isSure ? String(entry.dropFirst(UncertainWords.sureCopy.count)) : entry
        let fixed = apply(fixes, to: word)
        guard fixed != word else { return [entry] }
        return shownWords(fixed).map { isSure ? UncertainWords.sureCopy + $0 : $0 }
    }

    private static func shownWords(_ text: String) -> [String] {
        text.split(whereSeparator: \.isWhitespace).map { UncertainWords.normalize(String($0)) }.filter { !$0.isEmpty }
    }

    /// A Hebrew prefix letter kept before a word in another script, or
    /// before a number, is joined to it with a maqaf (U+05BE).
    private static func needsMaqaf(_ written: String) -> Bool {
        guard let start = written.unicodeScalars.first, Token.isCore(start) else { return false }
        return !(0x0590...0x05FF).contains(start.value)
    }

    /// `exactly` when the fix only respells its own words in ways matching
    /// looks past: an alef given its hamza, "don’t" written "don't";
    /// `inside` when its one heard word is looked for inside words too
    /// (`readsInsideWords`).
    private struct Prepared {
        let words: [String]
        let joints: [[Unicode.Scalar]]
        let written: String
        let exactly: Bool
        let inside: Bool
    }

    /// Whether the line already reads `hit`'s written form around the heard
    /// words at `index`: from where their letters start, or from a word
    /// before them, through the end of the last heard word. A fix that adds
    /// words after the heard one ("dana" written "Dana Cohen") or before it
    /// ("cohen" written "Avi Cohen", "Dr. Cohen") would otherwise write them
    /// twice on a line that has them. Each space or maqaf the reading
    /// crosses takes a character of the written form, so it starts no more
    /// words back than that. A Chinese or Japanese fix's reading may stop
    /// inside the next word (see `endsReading`).
    private static func writtenAlready(_ text: [Unicode.Scalar], _ tokens: [Token], _ starts: [Int], at index: Int, _ hit: Hit) -> Bool {
        let keep = starts[index] + hit.prefixEnd
        let last = index + hit.count - 1
        let end = starts[last] + tokens[last].coreEnd
        for word in max(0, index - hit.written.unicodeScalars.count)...index where tokens[word].coreStart < tokens[word].coreEnd {
            for from in tokens[word].readingStarts.map({ starts[word] + $0 }) where from <= keep {
                if let stop = readsAlready(text, from: from, hit.written, exactly: hit.exactly, heard: keep..<end, toWordEnd: !hit.inside), stop >= end,
                   !hit.inside || endsReading(text, at: stop) { return true }
            }
        }
        return false
    }

    /// Whether a reading of a Chinese or Japanese fix's written form that
    /// stops at `stop` ends there as a word: at a word's end, or before a
    /// Chinese or Japanese character the line runs on into with no space
    /// ("トム ハンクス" on "トム ハンクスが来た", "王 医生" on "王 医生来了"),
    /// though not inside a katakana run ("トム ハンクスソン" is another name).
    private static func endsReading(_ text: [Unicode.Scalar], at stop: Int) -> Bool {
        guard stop < text.count, Token.isCore(text[stop]) else { return true }
        return isJapanese(text[stop]) && !(stop > 0 && isKatakana(text[stop]) && isKatakana(text[stop - 1]))
    }

    /// Where `text` from `start` stops reading `written`, when it reads all
    /// of it there, case, niqqud and a maqaf against a hyphen aside, up to
    /// the end of a word (anywhere, unless `toWordEnd`); nil when it does
    /// not. The line may also differ from `written` the ways heard words
    /// may (an apostrophe, an alef's hamza, Arabic vowel marks), and may
    /// lack a punctuation mark of `written` ("Dr Cohen" for "Dr. Cohen")
    /// anywhere but between the letters of the `heard` words: there the
    /// mark is a change the fix makes ("dont" written "don't"). A fix that
    /// only respells (`exactly`) takes none of these differences: they are
    /// what it is for.
    private static func readsAlready(_ text: [Unicode.Scalar], from start: Int, _ written: String, exactly: Bool, heard: Range<Int>, toWordEnd: Bool = true) -> Int? {
        func skips(_ scalar: Unicode.Scalar) -> Bool {
            exactly ? Token.isHebrewNiqqud(scalar) : Token.isMark(scalar)
        }
        func same(_ one: Unicode.Scalar, _ other: Unicode.Scalar) -> Bool {
            if exactly { return WordFixes.caseFolded(String(one)) == WordFixes.caseFolded(String(other)) }
            return WordFixes.folded(String(one)) == WordFixes.folded(String(other))
        }
        var at = start
        func skipMarks() {
            while at < text.count, skips(text[at]) { at += 1 }
        }
        for scalar in written.unicodeScalars where !skips(scalar) {
            skipMarks()
            if at < text.count, (isDash(scalar) && isDash(text[at])) || same(scalar, text[at]) {
                at += 1
            } else if exactly || !isPunctuation(scalar) || (at > heard.lowerBound && at < heard.upperBound) {
                return nil
            }
        }
        skipMarks()
        return (!toWordEnd || at == text.count || !Token.isCore(text[at])) ? at : nil
    }

    private static func isDash(_ scalar: Unicode.Scalar) -> Bool {
        [0x2D, 0x05BE, 0x2010, 0x2011].contains(scalar.value)
    }

    private static func isPunctuation(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation, .initialPunctuation, .finalPunctuation, .otherPunctuation:
            return true
        default:
            return false
        }
    }

    private struct Hit {
        let count: Int
        let prefixEnd: Int
        let written: String
        let exactly: Bool
        let inside: Bool
    }

    /// A kanji as a run of them reads on: an ideograph, 〆 and 〇 among
    /// them, or the repeat mark 々, which writes the one before it again
    /// (佐々木, 時々).
    private static func isHan(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x3005...0x3007: return true
        case 0x3000...0x303F: return false
        default: return scalar.properties.isIdeographic
        }
    }

    private static let repeatMark: UInt32 = 0x3005

    private static func isKatakanaLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x30A1...0x30FA, 0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF6F, 0xFF71...0xFF9D: return true
        default: return false
        }
    }

    /// Katakana as a run of it reads on: its letters, small ones too, the
    /// long-vowel mark ー, its repeat marks and the voiced-sound marks.
    private static func isKatakana(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x30FC...0x30FE, 0xFF70, 0xFF9E...0xFF9F, 0x3099...0x309C: return true
        default: return isKatakanaLetter(scalar)
        }
    }

    private static func isHiragana(_ scalar: Unicode.Scalar) -> Bool {
        (0x3041...0x3096).contains(scalar.value) || (0x309D...0x309F).contains(scalar.value)
    }

    private static func isJapanese(_ scalar: Unicode.Scalar) -> Bool {
        isHan(scalar) || isKatakana(scalar) || isHiragana(scalar)
    }

    /// Whether a one-word `heard` word is looked for inside words too, as
    /// Chinese and Japanese are written without spaces: Han characters and
    /// kana, with a middle dot between names (ジョン・スミス), holding a Han
    /// character or a katakana letter. One all in hiragana is not: hiragana
    /// runs on through particles and verb endings (さくらが, 高かった), so
    /// where it starts or stops is no word's edge. The repeat mark 々 writes
    /// the character before it again, so it is not the Han character a
    /// heard word must hold, and one starting with it (々, 々木) is not read
    /// inside words either.
    private static func readsInsideWords(_ heard: String) -> Bool {
        let scalars = heard.unicodeScalars
        guard let first = scalars.first, first.value != repeatMark else { return false }
        return scalars.allSatisfy { isJapanese($0) || $0.value == 0x30FB || $0.value == 0xFF65 }
            && scalars.contains { (isHan($0) && $0.value != repeatMark) || isKatakanaLetter($0) }
    }

    /// Whether `heard` at `start` in `line` takes in the whole katakana run
    /// at each of its ends that is katakana: トム in トムさん or トム・クルーズ,
    /// not in トムソン or アトム.
    private static func standsAlone(_ line: [Unicode.Scalar], at start: Int, _ heard: [Unicode.Scalar]) -> Bool {
        let end = start + heard.count
        if let first = heard.first, isKatakana(first), start > 0, isKatakana(line[start - 1]) { return false }
        if let last = heard.last, isKatakana(last), end < line.count, isKatakana(line[end]) { return false }
        return true
    }

    private struct InsideFix {
        let heard: [Unicode.Scalar]
        let written: String
        let exactly: Bool
    }

    /// Whether `line` already reads `fix`'s written form around its heard
    /// characters at `start` inside a Chinese or Japanese word: from there
    /// or from a character before them, through their end. Those words run
    /// on without spaces, so the reading may start and stop inside one
    /// ("京" written "北京" on "北京市"), and a fix that adds characters
    /// before or after the heard ones would otherwise write them twice.
    private static func writtenAlreadyInside(_ line: [Unicode.Scalar], at start: Int, _ fix: InsideFix) -> Bool {
        let end = start + fix.heard.count
        return (max(0, start - fix.written.unicodeScalars.count)...start).contains { from in
            (readsAlready(line, from: from, fix.written, exactly: fix.exactly, heard: start..<end, toWordEnd: false) ?? -1) >= end
        }
    }

    /// `token` with the heard characters of `fixes` replaced wherever it
    /// holds them, katakana kept whole (`standsAlone`), or nil when none
    /// is; `start` is where the token starts in `line`.
    private static func replacingInside(_ fixes: [InsideFix], in token: Token, of line: [Unicode.Scalar], at start: Int) -> [Unicode.Scalar]? {
        guard !fixes.isEmpty, token.scalars.contains(where: isJapanese) else { return nil }
        var result: [Unicode.Scalar] = []
        var changed = false
        var position = 0
        let all = token.scalars
        while position < all.count {
            if let fix = fixes.first(where: { all[position...].starts(with: $0.heard) && standsAlone(line, at: start + position, $0.heard) }) {
                let kept = writtenAlreadyInside(line, at: start + position, fix)
                result.append(contentsOf: kept ? fix.heard : Array(fix.written.unicodeScalars))
                position += fix.heard.count
                changed = changed || !kept
            } else {
                result.append(all[position])
                position += 1
            }
        }
        return changed ? result : nil
    }

    private static func slice(_ tokens: [Token], _ from: Int, _ to: Int) -> [Unicode.Scalar] {
        tokens[from..<to].flatMap { $0.scalars + $0.gap }
    }

    private static func firstMatch(_ list: [Prepared], _ tokens: [Token], at index: Int) -> Hit? {
        var best: Hit?
        for fix in list {
            if let best, best.count > fix.words.count { break }
            guard let hit = match(fix, tokens, at: index) else { continue }
            if let best, best.prefixEnd <= hit.prefixEnd { continue }
            best = hit
        }
        return best
    }

    private static func match(_ fix: Prepared, _ tokens: [Token], at index: Int) -> Hit? {
        let count = fix.words.count
        guard index + count <= tokens.count else { return nil }
        for offset in 0..<count {
            let token = tokens[index + offset]
            if offset > 0, tokens[index + offset - 1].gap != fix.joints[offset - 1] { return nil }
            if offset > 0, token.coreStart != 0 { return nil }
            if offset < count - 1, token.replacedEnd != token.scalars.count { return nil }
        }
        for offset in 1..<max(count, 1) where tokens[index + offset].key != fix.words[offset] { return nil }
        let first = tokens[index]
        if first.key == fix.words[0] { return Hit(count: count, prefixEnd: first.coreStart, written: fix.written, exactly: fix.exactly, inside: fix.inside) }
        guard let heard = fix.words[0].unicodeScalars.first, (0x5D0...0x5EA).contains(heard.value) else { return nil }
        // A lone two-letter word takes no prefix: it ends too many other words.
        guard count > 1 || fix.words[0].unicodeScalars.count >= minimumPrefixedLetters else { return nil }
        let everyday = HebrewText.everydayWordsNotNames[fix.words[0]] ?? []
        for length in 1...3 where first.key.count > length {
            let head = first.key.prefix(length)
            guard head.allSatisfy(prefixLetters.contains), String(first.key.dropFirst(length)) == fix.words[0] else { continue }
            // A short name hides in everyday words ("full" holds Lea), also
            // behind a prefix of their own ("when full").
            if (0..<length).contains(where: { everyday.contains(String(first.key.dropFirst($0))) }) { return nil }
            return Hit(count: count, prefixEnd: first.endOfLetters(length), written: fix.written, exactly: fix.exactly, inside: fix.inside)
        }
        return nil
    }

    private static func collapsed(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func clip(_ text: String) -> String {
        String(text.prefix(maximumLength)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Words split at spaces only, so two words and the same two joined by
    /// a maqaf stay different fixes.
    static func key(_ text: String) -> String {
        let core = Token.split(Array(text.unicodeScalars), atMaqaf: false).map(\.key).joined(separator: " ")
        return core.isEmpty ? folded(HebrewText.stripNiqqud(text)) : core
    }

    /// Only capital letters and the Greek final sigma, no more: the old
    /// comparison, which decides whether a fix changes anything at all.
    static func caseKey(_ text: String) -> String {
        let core = Token.split(Array(text.unicodeScalars), atMaqaf: false).map(\.caseKey).joined(separator: " ")
        return core.isEmpty ? caseFolded(HebrewText.stripNiqqud(text)) : core
    }

    static func caseFolded(_ text: String) -> String {
        String(String.UnicodeScalarView(text.lowercased().unicodeScalars.map { $0.value == 0x03C2 ? sigma : $0 }))
    }

    /// Lowercase, with the Greek final sigma made a plain sigma, so a Greek fix
    /// typed in capitals finds the word ending in a final sigma, and a dotted
    /// capital I a plain i, as on Android. A curly, modifier-letter or Hebrew
    /// geresh apostrophe is a straight one, a curly quote or a gershayim a
    /// straight quote (the phone's keyboard types one, the engine writes the
    /// other), and an alef with hamza or madda is a plain alef. The alef
    /// maqsura and ta marbuta stay as they are: a fix replaces text, and
    /// folding them would let a name rewrite an everyday word.
    static func folded(_ text: String) -> String {
        let undotted = String(String.UnicodeScalarView(text.unicodeScalars.map { $0.value == 0x0130 ? "i" : $0 }))
        return String(String.UnicodeScalarView(undotted.lowercased().unicodeScalars.map { foldedScalar($0) }))
    }

    private static func foldedScalar(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        switch scalar.value {
        case 0x03C2: return sigma
        case 0x2018, 0x2019, 0x02BC, 0x05F3: return "'"
        case 0x201C, 0x201D, 0x05F4: return "\""
        case 0x0623, 0x0625, 0x0622: return "\u{0627}"
        default: return scalar
        }
    }

    private static let sigma = Unicode.Scalar(UInt32(0x03C3))!

    struct Token {
        let scalars: [Unicode.Scalar]
        let gap: [Unicode.Scalar]
        let coreStart: Int
        let coreEnd: Int
        /// `coreEnd`, or one past a geresh (or an apostrophe standing in for
        /// one) right after the last Hebrew letter: it belongs to the word, as
        /// at the end of George spelt in Hebrew, so a fix replaces it too,
        /// while the key still looks past it. The closing quote of a quoted
        /// word (‘שלום’) is no geresh, and stays.
        let replacedEnd: Int

        var key: String {
            let base = scalars[coreStart..<coreEnd].filter { !Token.isMark($0) }
            return WordFixes.folded(String(String.UnicodeScalarView(base)))
        }

        /// A word of vowel marks or tatweel alone: no fix can be heard as it.
        var isOnlyMarks: Bool { coreStart < coreEnd && key.isEmpty }

        /// A letter, digit or mark in it, not punctuation alone.
        var hasWord: Bool { coreStart < coreEnd }

        var caseKey: String {
            let base = scalars[coreStart..<coreEnd].filter { !Token.isHebrewNiqqud($0) }
            return WordFixes.caseFolded(String(String.UnicodeScalarView(base)))
        }

        /// Where a written form may start in this word: at its first letter,
        /// or past Hebrew prefix letters, as a heard word may take them.
        var readingStarts: [Int] {
            let letters = key
            var out = [coreStart]
            for length in 1...3 where letters.count > length && letters.prefix(length).allSatisfy(WordFixes.prefixLetters.contains) {
                out.append(endOfLetters(length))
            }
            return out
        }

        func endOfLetters(_ count: Int) -> Int {
            var seen = 0
            var position = coreStart
            while position < coreEnd {
                if !Token.isMark(scalars[position]) {
                    if seen == count { break }
                    seen += 1
                }
                position += 1
            }
            return position
        }

        static func isHebrewNiqqud(_ scalar: Unicode.Scalar) -> Bool {
            (0x0591...0x05C7).contains(scalar.value) && scalar.properties.generalCategory == .nonspacingMark
        }

        static func isMark(_ scalar: Unicode.Scalar) -> Bool {
            isHebrewNiqqud(scalar)
                || (0x064B...0x065F).contains(scalar.value) || scalar.value == 0x0670 || scalar.value == 0x0640
        }

        static func isCore(_ scalar: Unicode.Scalar) -> Bool {
            scalar.properties.isAlphabetic || scalar.properties.numericType != nil || isMark(scalar)
        }

        /// The closing mark each opening single quote waits for.
        private static let closingQuote: [Unicode.Scalar: Unicode.Scalar] = ["\u{2018}": "\u{2019}", "'": "'"]

        /// `stop`, or one past a geresh right after the last Hebrew letter.
        /// A mark `open` waits for there is the closing quote instead
        /// (‘שלום’), unless another apostrophe-like mark follows it to close
        /// the quote ('ג'ורג''); the Hebrew geresh is never a quote.
        private static func endOfGeresh(_ word: [Unicode.Scalar], _ start: Int, _ stop: Int, _ open: Set<Unicode.Scalar>) -> Int {
            guard stop < word.count, WordFixes.foldedScalar(word[stop]) == "'",
                  let letter = word[start..<stop].last(where: { !isMark($0) }), (0x05D0...0x05EA).contains(letter.value) else { return stop }
            let doubled = stop + 1 < word.count && WordFixes.foldedScalar(word[stop + 1]) == "'"
            return open.contains(word[stop]) && !doubled ? stop : stop + 1
        }

        /// Words between spaces and, unless `atMaqaf` is false, a maqaf, as
        /// on Android: two words joined by a maqaf each take their own fixes.
        /// A single quote before a word's first letter stays open until the
        /// matching mark after the last letter of that word or a later one;
        /// a word of marks alone (‘’, or a quote standing by itself) can
        /// close it but opens none.
        static func split(_ all: [Unicode.Scalar], atMaqaf: Bool = true) -> [Token] {
            func isBreak(_ scalar: Unicode.Scalar) -> Bool {
                scalar.properties.isWhitespace || (atMaqaf && scalar.value == 0x05BE)
            }
            var tokens: [Token] = []
            var open = Set<Unicode.Scalar>()
            var position = 0
            while position < all.count {
                var end = position
                while end < all.count, !isBreak(all[end]) { end += 1 }
                var gapEnd = end
                while gapEnd < all.count, isBreak(all[gapEnd]) { gapEnd += 1 }
                let word = Array(all[position..<end])
                let start = word.firstIndex(where: isCore) ?? word.count
                let stop = max(start, (word.lastIndex(where: isCore).map { $0 + 1 }) ?? word.count)
                if start == word.count {
                    for scalar in word { open.remove(scalar) }
                } else {
                    for scalar in word[..<start] { if let closing = closingQuote[scalar] { open.insert(closing) } }
                }
                let replaced = endOfGeresh(word, start, stop, open)
                for scalar in word[replaced...] { open.remove(scalar) }
                tokens.append(Token(scalars: word, gap: Array(all[end..<gapEnd]), coreStart: start, coreEnd: stop, replacedEnd: replaced))
                position = gapEnd
            }
            return tokens
        }
    }
}

/// What became of a word fixed in History, so the sheet can say why a fix
/// was not saved. A fix that waits for room on a full phone is saved: it is
/// written once there is room.
public enum WordFixResult: Sendable, Equatable {
    case saved
    /// The line is gone, is being heard again, or no longer has the word.
    case lineChanged
    /// The conversation's file is there but couldn't be read just now.
    case unreadable

    /// Said in the sheet and to VoiceOver when the fix was not saved; nil
    /// when it was.
    public var note: String? {
        switch self {
        case .saved: return nil
        case .lineChanged: return tr("השורה השתנתה או נעלמה, ולכן התיקון לא נשמר.", "The line changed or is gone, so the fix was not saved.")
        case .unreadable: return tr("לא היה אפשר לקרוא את השיחה כרגע, ולכן התיקון לא נשמר. אפשר לנסות שוב עוד רגע.", "The conversation couldn’t be read just now, so the fix was not saved. Try again in a moment.")
        }
    }
}
