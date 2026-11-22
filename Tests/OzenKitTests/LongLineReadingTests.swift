import Foundation
import Testing
@testable import OzenKit

/// The steps that read the line being written at every word now read only
/// what they need of it. These hold each one to the answers of the way it
/// read the whole line before, byte for byte, on every line in the shared
/// cases and on lines made to trip them up.
enum LineCorpus {
    private static let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures/logic")

    /// Every text in the shared cases of both phones, in many languages.
    static let shared: [String] = {
        var found = Set<String>()
        func collect(_ value: Any) {
            switch value {
            case let text as String where text.count <= 400: found.insert(text)
            case let list as [Any]: list.forEach(collect)
            case let table as [String: Any]: table.values.forEach(collect)
            default: break
            }
        }
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file), let json = try? JSONSerialization.jsonObject(with: data) {
                collect(json)
            }
        }
        return found.sorted()
    }()

    /// Spaces of every kind, marks after spaces and joiners, letters that
    /// join a space, the Greek final sigma, the Turkish dotted I, emoji,
    /// accents written as one character and as two, niqqud, maqaf and
    /// Arabic marks.
    static let tricky: [String] = [
        "", " ", "  \t\n ", "שלום", "שלום  עולם", "שלום\u{00A0}עולם", "שלום\u{2009}עולם\u{3000}מה", "a\r\nb", "a \u{0301}b",
        "a -\u{0301}b", "a\u{0301} b", "\u{0301}", " \u{05B8}שלום", "תל-אביב", "תל\u{05BE}אביב", "בן־ציון", "a/b", "a\u{2014}b",
        "שָׁלוֹם עוֹלָם", "مَرْحَبًا بِكُمْ", "أحمد إبراهيم آمنة", "ΟΔΟΣ ΚΑΙ ΣΟΦΙΑ", "Σ σ ς", "İstanbul İ", "café cafe\u{0301} CAFÉ",
        "สวัสดี ครับ ทำ", "a \u{0E33}b", "\u{0D4E} x", "x \u{FF9E}y", "👍🏽 נהדר 👨‍👩‍👧", "תודה. תודה. תודה.", "תודה רבה, תודה רבה",
        "Thank you. Thank you.", " ... ", "...", "toda…", "\u{200F}תודה", "תו\u{200F}דה", "שירה! שירה", "(מוזיקה)", "[צחוק]",
        "한국어 문장", "\u{1100}\u{1161} 가", "北京 市", "e\u{0301}\u{0327} e\u{0327}\u{0301}", "\u{FB2A}לום", "x\u{FE0F} y",
        "don’t dont don't", "‘שלום’ ג'ורג' ג׳ורג׳", "״שלום״ \"שלום\"", "1,000 10:30 ₪50", "Dr. Cohen, Avi Cohen",
    ]

    static var all: [String] { shared + tricky }

    static func fixture(_ name: String) -> URL { folder.appendingPathComponent(name) }

    /// The passes an engine sends while `text` is being said: its words so
    /// far, one more each time.
    static func passes(of text: String) -> [String] {
        let words = text.split(separator: " ", omittingEmptySubsequences: false)
        return (1...max(words.count, 1)).map { words.prefix($0).joined(separator: " ") }
    }
}

@Suite("Reading only what a word changes")
struct LongLineReadingTests {
    @Test("the quiet-room check reads the same words, one at a time, as normalizing the whole line")
    func normalizedWordsOneAtATime() {
        for text in LineCorpus.all {
            let whole = WhisperResultFilter.normalize(text).split(separator: " ").map(String.init)
            var lazy = WhisperResultFilter.NormalizedWords(text)
            var read: [String] = []
            while lazy.has(read.count) { read.append(lazy[read.count]) }
            #expect(read.map { Array($0.unicodeScalars) } == whole.map { Array($0.unicodeScalars) }, "\(text.debugDescription)")
            var firstOnly = WhisperResultFilter.NormalizedWords(text)
            #expect(firstOnly.has(0) == !whole.isEmpty, "\(text.debugDescription)")
            if let first = whole.first { #expect(firstOnly[0] == first) }
        }
    }

    @Test("a word fix the line can't take gives the line back exactly as reading every word does")
    func wordFixesOnEveryPass() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: LineCorpus.fixture("word-fixes.json"))) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        let lists = cases.map { ($0["fixes"] as! [[String]]).map { WordFix(heard: $0[0], written: $0[1]) } }
            + [[WordFix(heard: "café", written: "Café"), WordFix(heard: "cafe\u{0301}", written: "kafe")],
               [WordFix(heard: "ﬁle", written: "file")], [WordFix(heard: "Σοφια", written: "Sofia")], [WordFix(heard: "İzmir", written: "Izmir")],
               [WordFix(heard: "가", written: "ga")], [WordFix(heard: "\u{1100}\u{1161}", written: "ga")], [WordFix(heard: "שָלום", written: "שלום!")],
               [WordFix(heard: "!!", written: "!")], [WordFix(heard: "ﺷﻠﻮﻡ", written: "x")], [WordFix(heard: "אחמד", written: "أحمد")]]
        let lines = LineCorpus.tricky + LineCorpus.shared.enumerated().filter { $0.offset % 200 == 0 }.map(\.element)
        var compared = 0
        for (index, fixes) in lists.enumerated() {
            let own = cases.indices.contains(index) ? LineCorpus.passes(of: cases[index]["text"] as! String) : []
            for line in own + lines {
                let quick = WordFixes.apply(fixes, to: line)
                let slow = WordFixes.applyReadingEveryWord(fixes, to: line)
                #expect(quick.utf8.elementsEqual(slow.utf8), "\(fixes) on \(line.debugDescription): \(quick.debugDescription) against \(slow.debugDescription)")
                compared += 1
            }
        }
        #expect(compared > 2_000)
    }

    @Test("the stabilizer's held words come out exactly as when every pass was split anew")
    func liveAgreementOnEveryPass() {
        var sequences = LineCorpus.all.map(LineCorpus.passes)
        var generator = SeededWords(seed: 7)
        for _ in 0..<300 { sequences.append(generator.flickeringLine()) }
        for passes in sequences {
            var agreement = LiveAgreement()
            var reference = ReferenceAgreement()
            for pass in passes {
                let shown = agreement.settle(pass)
                let expected = reference.settle(pass)
                #expect(shown.utf8.elementsEqual(expected.utf8), "\(passes.map(\.debugDescription)) at \(pass.debugDescription)")
            }
        }
    }
    private struct KeywordTable: Decodable {
        struct Case: Decodable {
            let alerts: [Alert]
            let text: String
            let language: String?
        }

        struct Alert: Decodable {
            let phrase: String
            let isEnabled: Bool?
        }

        let cases: [Case]
    }

    private static func same(_ found: [KeywordMatch], _ expected: [KeywordMatch]) -> Bool {
        found.count == expected.count && zip(found, expected).allSatisfy {
            $0.alertID == $1.alertID && $0.wordIndex == $1.wordIndex && $0.phrase == $1.phrase && $0.matchedText.utf8.elementsEqual($1.matchedText.utf8)
        }
    }

    /// Each pass as the engine sends it while the line grows, cut off
    /// mid-word, ending in dots alone, with a direction mark, and shifted
    /// by a word said before it.
    private static func keywordPasses(of text: String) -> [String] {
        let grown = LineCorpus.passes(of: text)
        let cut = grown.map { String($0.dropLast(min(2, $0.count / 2))) + "..." }
        return grown + cut + grown.map { $0 + " …" } + grown.map { $0 + " , ..." } + grown.map { "\u{200F}" + $0 } + grown.map { "אז " + $0 } + grown.map { $0 + "-" }
    }

    @Test("the keyword check that keeps the words it read finds exactly what reading every word again finds")
    func keywordReaderOnEveryPass() throws {
        let table = try JSONDecoder().decode(KeywordTable.self, from: Data(contentsOf: LineCorpus.fixture("keyword-matching.json")))
        let reader = KeywordAlertReader()
        var compared = 0
        for item in table.cases {
            let matcher = KeywordAlertMatcher(alerts: item.alerts.map { KeywordAlert(phrase: $0.phrase, isEnabled: $0.isEnabled ?? true) }, captionLanguage: item.language ?? "")
            for line in Self.keywordPasses(of: item.text) {
                #expect(Self.same(reader.matches(inLiveText: line, with: matcher), matcher.matches(inLiveText: line)), "live \(item.alerts.map(\.phrase)) on \(line.debugDescription)")
                #expect(Self.same(reader.matches(in: line, with: matcher), matcher.matches(in: line)), "\(item.alerts.map(\.phrase)) on \(line.debugDescription)")
                compared += 2
            }
        }
        let saved = ["סבתא", "דנה", "בן ציון", "שירלי", "Anne", "Ali", "老师", "Sofia", "José"].map { KeywordAlert(phrase: $0) }
        let matchers = ["he", "en", "fr", "zh", "ar", ""].map { KeywordAlertMatcher(alerts: saved, captionLanguage: $0) }
        for line in LineCorpus.tricky + LineCorpus.shared.enumerated().filter({ $0.offset % 80 == 0 }).map(\.element) {
            for matcher in matchers {
                #expect(Self.same(reader.matches(inLiveText: line, with: matcher), matcher.matches(inLiveText: line)), "live on \(line.debugDescription)")
                #expect(Self.same(reader.matches(in: line, with: matcher), matcher.matches(in: line)), "on \(line.debugDescription)")
                compared += 2
            }
        }
        #expect(compared > 5_000)
    }

    @Test("an alert changed, switched off or read in another language is read anew by the keyword check that keeps words")
    func keywordReaderFollowsTheAlerts() {
        let reader = KeywordAlertReader()
        let line = "סבתא ודנה הלכו לבית של שירלי"
        let dana = KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "דנה")], captionLanguage: "he")
        #expect(reader.matches(inLiveText: line, with: dana).map(\.wordIndex) == [1])
        let savta = KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "סבתא")], captionLanguage: "he")
        #expect(reader.matches(inLiveText: line, with: savta).map(\.wordIndex) == [0])
        let off = KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "סבתא", isEnabled: false)], captionLanguage: "he")
        #expect(reader.matches(inLiveText: line, with: off).isEmpty)
        for language in ["fr", "en", "fr"] {
            let anne = KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "Anne")], captionLanguage: language)
            #expect(Self.same(reader.matches(in: "bonjour l'Anne", with: anne), anne.matches(in: "bonjour l'Anne")), "\(language)")
        }
        let french = KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "Anne")], captionLanguage: "fr")
        #expect(!reader.matches(in: "bonjour l'Anne", with: french).isEmpty)
    }
}

/// `LiveAgreement.settle` as it was before it reused the words of the pass
/// before, kept to hold the new one to the same answers.
private struct ReferenceAgreement {
    private var lastWords: [String] = []
    private var held: [String] = []

    mutating func settle(_ text: String) -> String {
        let words = text.split(whereSeparator: \.isWhitespace).map(String.init)
        defer { lastWords = words }
        guard !words.isEmpty else { return text }
        var shown = words
        var substituted = false
        if words.count >= held.count {
            for index in held.indices where words[index] != held[index]
                && lastWords.indices.contains(index) && lastWords[index] == words[index] {
                held[index] = words[index]
            }
            let changed = held.indices.filter { words[$0] != held[$0] }
            if !changed.isEmpty, changed.count <= LiveAgreement.maximumHeldChanges, changed.count * 3 <= held.count {
                for index in changed { shown[index] = held[index] }
                substituted = true
            } else if !changed.isEmpty {
                held = Array(held.prefix(changed[0]))
            }
        } else {
            held = Array(zip(held, words).prefix { $0 == $1 }.map(\.0))
        }
        let agreed = zip(lastWords, words).prefix { $0 == $1 }.count
        if agreed > held.count {
            held = Array(shown.prefix(held.count)) + words[held.count..<agreed]
        }
        return substituted ? shown.joined(separator: " ") : text
    }
}

/// Lines that grow word by word while some passes change a word or two,
/// drop the end or come with other spaces, from a fixed seed.
private struct SeededWords {
    private var state: UInt64
    private let vocabulary = ["take", "two", "three", "pills", "after", "the", "meal", "תודה", "שלום", "café", "cafe\u{0301}", "Σοφια", "a", "an"]

    init(seed: UInt64) { state = seed }

    private mutating func next(_ bound: Int) -> Int {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Int((state >> 33) % UInt64(bound))
    }

    mutating func flickeringLine() -> [String] {
        var words: [String] = []
        var passes: [String] = []
        for _ in 0..<(4 + next(20)) {
            words.append(vocabulary[next(vocabulary.count)])
            var pass = words
            switch next(6) {
            case 0: pass[next(pass.count)] = vocabulary[next(vocabulary.count)]
            case 1: pass.removeLast(min(pass.count - 1, next(3)))
            case 2: let index = next(pass.count); pass[index] = pass[index].uppercased()
            default: break
            }
            passes.append(pass.joined(separator: [" ", "  ", "\t", "\u{00A0}"][next(4)]))
            if next(5) == 0 { words = pass }
        }
        return passes
    }
}
