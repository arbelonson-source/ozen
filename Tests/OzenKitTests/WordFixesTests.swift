import Foundation
import Testing
@testable import OzenKit

@Suite("WordFixes")
struct WordFixesTests {
    private func fix(_ heard: String, _ written: String) -> WordFix { WordFix(heard: heard, written: written) }
    private let avi = [WordFix(heard: "aval", written: "Avi")]

    @Test("a refusal says why Add fix is grey, and is nil exactly when the fix can be added")
    func refusalReasons() {
        #expect(WordFixes.refusal(fix("nasa", "NASA"), to: []) == .sameWord)
        #expect(WordFixes.refusal(fix("one two three four five", "x"), to: []) == .heardWords)
        #expect(WordFixes.refusal(fix("ok, dana", "Dana"), to: []) == .heardWords)
        #expect(WordFixes.refusal(fix(String(repeating: "a", count: 41), "b"), to: []) == .tooLong)
        #expect(WordFixes.refusal(fix("dana", "  "), to: []) == .missing)
        let full = (1...WordFixes.maximumFixes).map { fix("word\($0)", "Word number \($0)") }
        #expect(WordFixes.refusal(fix("dana", "Dana Levi"), to: full) == .full)
        #expect(WordFixes.refusal(fix("word7", "Seven"), to: full) == nil)
        #expect(WordFixes.refusal(fix("dana", "Dana Levi"), to: []) == nil)
        let samples = [fix("nasa", "NASA"), fix("a b c d e", "x"), fix("ok, dana", "Dana"), fix("dana", "Dana Levi"), fix("", "x"), fix("שלום", "שלום"), fix("מאיה", "מיה")]
        for sample in samples {
            #expect(WordFixes.canAdd(sample, to: []) == (WordFixes.refusal(sample, to: []) == nil), "\(sample)")
        }
    }

    @Test("a refusal note names the rule, and an empty box needs none")
    func refusalNotes() {
        Localization.$override.withValue(.english) {
            #expect(WordFixes.refusalNote(.tooLong) == "Up to 40 letters and spaces in each box.")
            #expect(WordFixes.refusalNote(.heardWords) == "In “Heard”: up to 4 words, with only spaces between them.")
            #expect(WordFixes.refusalNote(.full) == "The list is full. Delete a fix to add another.")
            #expect(WordFixes.refusalNote(.missing) == nil)
            #expect(WordFixes.refusalNote(nil) == nil)
        }
    }

    @Test("rule 1: whole words only, ignoring case")
    func wholeWords() {
        #expect(WordFixes.apply(avi, to: "I saw aval today") == "I saw Avi today")
        #expect(WordFixes.apply(avi, to: "Aval") == "Avi")
        #expect(WordFixes.apply(avi, to: "it is available") == "it is available")
    }

    @Test("rule 2: surrounding punctuation stays")
    func punctuation() {
        #expect(WordFixes.apply(avi, to: "aval, come") == "Avi, come")
        #expect(WordFixes.apply(avi, to: "(aval)") == "(Avi)")
        #expect(WordFixes.apply(avi, to: "aval?") == "Avi?")
    }

    @Test("rule 3: Hebrew prefix letters are kept")
    func hebrewPrefix() {
        let rotem = [fix("רותם", "רותי")]
        #expect(WordFixes.apply(rotem, to: "ורותם באה") == "ורותי באה")
        #expect(WordFixes.apply(rotem, to: "לרותם") == "לרותי")
        #expect(WordFixes.apply(rotem, to: "שלרותם") == "שלרותי")
        #expect(WordFixes.apply(rotem, to: "ורותמים") == "ורותמים")
        #expect(WordFixes.apply(rotem, to: "אלרותם") == "אלרותם")
        #expect(WordFixes.apply(rotem, to: "ושלהרותם") == "ושלהרותם")
        #expect(WordFixes.apply(rotem, to: "רותם") == "רותי")
    }

    @Test("a fix on a two-letter word leaves longer words that end with it alone")
    func twoLetterWordTakesNoPrefix() {
        let lev = [fix("לב", "ליב")]
        #expect(WordFixes.apply(lev, to: "כלב") == "כלב")
        #expect(WordFixes.apply(lev, to: "שלב") == "שלב")
        #expect(WordFixes.apply(lev, to: "לב") == "ליב")
        #expect(WordFixes.apply([fix("גל", "גיל")], to: "מגל") == "מגל")
        #expect(WordFixes.apply([fix("דוד", "דור")], to: "לדוד") == "לדור")
    }

    @Test("a phrase that starts with a two-letter word still keeps a prefix")
    func twoLetterPhraseKeepsPrefix() {
        let name = [fix("דן כהן", "דן כוהן")]
        #expect(WordFixes.apply(name, to: "לדן כהן") == "לדן כוהן")
        #expect(WordFixes.apply(name, to: "דן כהן") == "דן כוהן")
    }

    @Test("a fix for a number leaves a decimal and a time alone, and a hyphenated word is one word")
    func wholeWordsKeepInnerPunctuation() {
        #expect(WordFixes.apply([fix("5", "חמש")], to: "3.5") == "3.5")
        #expect(WordFixes.apply([fix("30", "thirty")], to: "10:30") == "10:30")
        #expect(WordFixes.apply([fix("5", "חמש")], to: "יש 5.") == "יש חמש.")
        #expect(WordFixes.apply([fix("5", "חמש")], to: "ב5") == "ב5")
        let mail = [fix("e-mail", "email")]
        #expect(WordFixes.normalized(mail) == mail)
        #expect(WordFixes.apply(mail, to: "my E-mail.") == "my email.")
        #expect(WordFixes.apply([fix("mail", "post")], to: "my e-mail") == "my e-mail")
    }

    @Test("a Greek fix finds its word in capitals or small letters, final sigma and all")
    func greekFinalSigmaMatchesCapitals() {
        #expect(WordFixes.apply([fix("ΛΟΓΟΣ", "Logos")], to: "ο λογος μας") == "ο Logos μας")
        #expect(WordFixes.apply([fix("λογος", "Logos")], to: "Ο ΛΟΓΟΣ") == "Ο Logos")
    }

    @Test("a Hebrew prefix kept before Latin letters or a number takes a maqaf")
    func hebrewPrefixBeforeLatinTakesMaqaf() {
        let phone = [fix("אייפון", "iPhone")]
        #expect(WordFixes.apply(phone, to: "קניתי באייפון") == "קניתי ב\u{05BE}iPhone")
        #expect(WordFixes.apply(phone, to: "ושבאייפון") == "ושב\u{05BE}iPhone")
        #expect(WordFixes.apply(phone, to: "אייפון חדש") == "iPhone חדש")
        #expect(WordFixes.apply([fix("שבע", "7")], to: "בשבע") == "ב\u{05BE}7")
        #expect(WordFixes.apply([fix("רותם", "רותי")], to: "ורותם") == "ורותי")
    }

    @Test("rule 4: niqqud is ignored when matching, written is verbatim")
    func niqqud() {
        let rotem = [fix("רותם", "רוּתִי")]
        #expect(WordFixes.apply(rotem, to: "רוֹתֵם") == "רוּתִי")
        #expect(WordFixes.apply([fix("רוֹתֵם", "X")], to: "רותם") == "X")
        #expect(WordFixes.apply(rotem, to: "לְרוֹתֵם") == "לְרוּתִי")
    }

    @Test("rule 5: a phrase matches across single spaces")
    func phrases() {
        let bat = [fix("bat sheva", "Batsheva")]
        #expect(WordFixes.apply(bat, to: "I met Bat Sheva.") == "I met Batsheva.")
        #expect(WordFixes.apply(bat, to: "bat  sheva") == "bat  sheva")
        #expect(WordFixes.apply(bat, to: "bat, sheva") == "bat, sheva")
        #expect(WordFixes.apply([fix("a b c d", "Z")], to: "x a b c d y") == "x Z y")
    }

    @Test("rule 6: longer phrase wins and there is no chaining")
    func longerWins() {
        let list = [fix("bat", "B"), fix("bat sheva", "Batsheva")]
        #expect(WordFixes.apply(list, to: "bat sheva") == "Batsheva")
        #expect(WordFixes.apply(list, to: "bat") == "B")
        let chain = [fix("a", "b"), fix("b", "c")]
        #expect(WordFixes.apply(chain, to: "a") == "b")
        #expect(WordFixes.apply(chain, to: "a b") == "b c")
    }

    @Test("rule 7: normalized trims, drops, clips, dedupes and caps")
    func normalizing() {
        let result = WordFixes.normalized([
            fix("  aval ", " Avi "), fix("", "x"), fix("y", ""), fix("Same", "same"),
            fix("AVAL", "Other"), fix("רוֹתם", "ר"), fix("רותם", "ז"),
        ])
        #expect(result == [fix("aval", "Avi"), fix("רוֹתם", "ר")])
        let long = String(repeating: "a", count: 60)
        let clipped = WordFixes.normalized([fix(long, String(repeating: "b", count: 60))])
        #expect(clipped.first?.heard.count == 40)
        #expect(clipped.first?.written.count == 40)
        let many = (0..<150).map { fix("w\($0)", "v\($0)") }
        let capped = WordFixes.normalized(many)
        #expect(capped.count == 100)
        #expect(capped.first == fix("w0", "v0"))
    }

    @Test("rule 8: no fixes leaves the text unchanged")
    func empty() {
        #expect(WordFixes.apply([], to: "  hello,  world ") == "  hello,  world ")
        #expect(WordFixes.apply(avi, to: "") == "")
    }

    @Test("a phrase longer than four words is refused, so no saved fix is silently ignored")
    func longPhrasesRefused() {
        #expect(WordFixes.normalized([fix("a b c d e", "X")]).isEmpty)
        #expect(WordFixes.normalized([fix("a b c d", "X")]) == [fix("a b c d", "X")])
    }

    @Test("a choice of words never reaches across punctuation")
    func joinAcrossPunctuation() {
        #expect(WordFixes.canJoin("bat", "sheva"))
        #expect(!WordFixes.canJoin("bat,", "sheva"))
        #expect(!WordFixes.canJoin("bat", "(sheva"))
    }

    @Test("VoiceOver hears a fix as what was heard and what is written, not as an arrow")
    func spokenLabel() {
        let one = fix("aval", "Avi")
        #expect(WordFixes.spokenLabel(one, in: .english) == "Heard aval, written Avi")
        #expect(WordFixes.spokenLabel(one, in: .hebrew) == "נשמע aval, ייכתב Avi")
        for language in UILanguage.allCases {
            #expect(!WordFixes.spokenLabel(one, in: language).contains("→"), "\(language)")
        }
    }
}
