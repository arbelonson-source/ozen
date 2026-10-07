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

    private static func hasHebrew(_ text: String) -> Bool {
        text.unicodeScalars.contains { (0x0590...0x05FF).contains($0.value) }
    }
}
