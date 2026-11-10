import Testing
@testable import OzenKit

@Suite("Decimal numbers are written in Ozen's language")
struct NumberTextTests {
    @Test("a decimal comma in French, German, Russian, Ukrainian, Spanish and Portuguese, a point elsewhere")
    func decimalMark() {
        let comma: Set<UILanguage> = [.french, .german, .russian, .ukrainian, .spanish, .portuguese]
        for language in UILanguage.allCases {
            #expect(NumberText.decimal(0.55, places: 2, in: language) == (comma.contains(language) ? "0,55" : "0.55"), "\(language)")
            #expect(NumberText.decimal(12.3, places: 1, in: language) == (comma.contains(language) ? "12,3" : "12.3"), "\(language)")
        }
    }

    @Test("without a language named, the app's language decides")
    func appLanguage() {
        Localization.$override.withValue(.german) { #expect(NumberText.decimal(1.24, places: 1) == "1,2") }
        Localization.$override.withValue(.english) { #expect(NumberText.decimal(1.26, places: 1) == "1.3") }
    }
}
