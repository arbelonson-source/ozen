import Foundation
import Testing
@testable import OzenKit

@Suite("Alert suggestions")
struct AlertSuggestionsTests {
    @Test("outside Hebrew a suggested word shows only its meaning, with no Hebrew letters")
    func labels() {
        #expect(AlertSuggestions.label(for: "סבתא", in: .hebrew) == "סבתא")
        #expect(AlertSuggestions.label(for: "סבתא", in: .english) == "Grandma")
        #expect(AlertSuggestions.label(for: "אמא", in: .russian) == "Мама")
        #expect(AlertSuggestions.label(for: "רופא", in: .french) == "Médecin")
        #expect(AlertSuggestions.label(for: "Dana", in: .english) == "Dana")
        for language in UILanguage.allCases where language != .hebrew {
            for word in AlertSuggestions.words {
                let label = AlertSuggestions.label(for: word, in: language)
                #expect(!label.isEmpty && !Self.hasHebrew(label), "\(language): \(word)")
            }
        }
    }

    @Test("a suggested word she added is named by its meaning in the list, the banner and the notification")
    func shownOnceAdded() {
        #expect(AlertSuggestions.shown("סבתא", in: .english) == "Grandma")
        #expect(AlertSuggestions.shown("סבתא", in: .hebrew) == "סבתא")
        #expect(AlertSuggestions.shown("תרופה", in: .german) == AlertSuggestions.label(for: "תרופה", in: .german))
        #expect(AlertSuggestions.shown("דנה", in: .english) == "דנה")

        let heard = KeywordMatch(alertID: UUID(), phrase: "סבתא", matchedText: "סבתוש", wordIndex: 0)
        #expect(AlertSuggestions.said(heard, in: .english) == "Grandma")
        #expect(AlertSuggestions.said(heard, in: .hebrew) == "סבתוש")
        let own = KeywordMatch(alertID: UUID(), phrase: "דנה", matchedText: "לדנה", wordIndex: 0)
        #expect(AlertSuggestions.said(own, in: .english) == "לדנה")
        for language in UILanguage.allCases where language != .hebrew {
            for word in AlertSuggestions.words {
                let match = KeywordMatch(alertID: UUID(), phrase: word, matchedText: word, wordIndex: 0)
                #expect(!Self.hasHebrew(AlertSuggestions.shown(word, in: language)), "\(language): \(word)")
                #expect(!Self.hasHebrew(AlertSuggestions.said(match, in: language)), "\(language): \(word)")
            }
        }
    }

    @Test("the note under the name form lists only the words that will buzz, and is gone when none will")
    func vibrationNote() {
        let alerts = [KeywordAlert(phrase: "סבתא"), KeywordAlert(phrase: "Dani", isEnabled: false), KeywordAlert(phrase: "Ruti")]
        #expect(AlertSuggestions.vibrationNote(for: alerts, in: .english, captionLanguage: "en") == "The phone will vibrate for: Grandma, Ruti")
        #expect(AlertSuggestions.vibrationNote(for: alerts, in: .hebrew, captionLanguage: "en") == "הטלפון ירטוט כשייאמרו: סבתא, Ruti")
        #expect(AlertSuggestions.vibrationNote(for: [KeywordAlert(phrase: "Dani", isEnabled: false)], in: .english, captionLanguage: "en") == nil)
        #expect(AlertSuggestions.vibrationNote(for: [], in: .english, captionLanguage: "en") == nil)
    }

    @Test("the note leaves out a word in other letters than the captions, which can never buzz")
    func vibrationNoteLeavesOutOtherLetters() {
        let alerts = [KeywordAlert(phrase: "Dana"), KeywordAlert(phrase: "סבתא")]
        #expect(AlertSuggestions.vibrationNote(for: alerts, in: .english, captionLanguage: "he") == "The phone will vibrate for: Grandma")
        #expect(AlertSuggestions.vibrationNote(for: [KeywordAlert(phrase: "Dana")], in: .english, captionLanguage: "he") == nil)
        #expect(AlertSuggestions.vibrationNote(for: [KeywordAlert(phrase: "Dana")], in: .english, captionLanguage: "en") == "The phone will vibrate for: Dana")
        #expect(AlertSuggestions.vibrationNote(for: [KeywordAlert(phrase: "سارة"), KeywordAlert(phrase: "סבתא")], in: .english, captionLanguage: "he") == "The phone will vibrate for: Grandma")
    }

    private static func hasHebrew(_ text: String) -> Bool {
        text.unicodeScalars.contains { (0x0590...0x05FF).contains($0.value) }
    }

    @Test("a name of several words gets a note that only all of them in a row alert")
    func severalWordsNote() {
        #expect(AlertSuggestions.severalWordsNote(for: "רחל לוי", in: .hebrew) != nil)
        #expect(AlertSuggestions.severalWordsNote(for: "  Rachel   Levi ", in: .english)?.hasPrefix("When the name has several words") == true)
        #expect(AlertSuggestions.severalWordsNote(for: "רחל", in: .hebrew) == nil)
        #expect(AlertSuggestions.severalWordsNote(for: " רחל ", in: .hebrew) == nil)
        #expect(AlertSuggestions.severalWordsNote(for: "", in: .hebrew) == nil)
        #expect(KeywordAlertMatcher(alerts: [KeywordAlert(phrase: "רחל לוי")]).matches(in: "רחל, בואי").isEmpty)
    }
}
