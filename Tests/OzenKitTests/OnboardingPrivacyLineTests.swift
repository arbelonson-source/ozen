import Foundation
import Testing
@testable import OzenKit

struct OnboardingPrivacyLineTests {
    private static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    private static let english = "By default it all happens on the phone."
    private static let hebrew = "כברירת מחדל, הכל קורה בתוך הטלפון."
    private static let oldEnglish = "Nothing is sent to the internet"
    private static let oldHebrew = "שום דבר לא נשלח לאינטרנט"

    private func source(_ path: String) throws -> String {
        try String(contentsOf: Self.root.appendingPathComponent(path), encoding: .utf8)
    }

    @Test("both first-run screens say the same hedged line, not the blanket 'nothing is sent' claim")
    func bothAppsSayTheDefaultLine() throws {
        for path in ["App/Ozen/Views/OnboardingView.swift",
                     "android/app/src/main/kotlin/com/arbelonson/ozen/OnboardingScreen.kt"] {
            let text = try source(path)
            #expect(text.contains("tr(\"\(Self.hebrew)\", \"\(Self.english)\")"), "\(path)")
            #expect(!text.contains(Self.oldEnglish), "\(path)")
            #expect(!text.contains(Self.oldHebrew), "\(path)")
        }
    }

    @Test("the old blanket claim is gone from every translation table")
    func oldClaimGoneFromTables() throws {
        let raw = try source("Sources/OzenKit/Resources/Translations.json")
        #expect(!raw.contains(Self.oldEnglish))
    }

    @Test("the default line has a real translation in every language beyond Hebrew and English")
    func translatedEverywhere() {
        for language in UILanguage.allCases where language != .hebrew && language != .english {
            let line = TranslationTable.shared.lookup(Self.english, language: language)
            #expect(line != nil && !line!.isEmpty && line != Self.english, "\(language)")
        }
    }
}
