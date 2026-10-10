import Foundation
import Testing
@testable import OzenKit

@Suite("iPhone's own words follow the app's language")
struct SystemLanguageTests {
    @Test("every language Ozen offers names a localization the app ships, so iOS can switch to it")
    func shippedLocalizations() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        for file in ["App/Ozen/InfoPlist.xcstrings", "App/Shared/Localizable.xcstrings"] {
            let data = try Data(contentsOf: root.appendingPathComponent(file))
            let catalog = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            let strings = try #require(catalog["strings"] as? [String: [String: Any]])
            var shipped = Set([catalog["sourceLanguage"] as? String ?? ""])
            for entry in strings.values {
                shipped.formUnion((entry["localizations"] as? [String: Any] ?? [:]).keys)
            }
            for language in AppLanguage.allCases where language != .system {
                let code = try #require(language.bundleLocalization, "\(language)")
                #expect(shipped.contains(code), "\(file): \(language) -> \(code)")
            }
        }
        #expect(AppLanguage.system.bundleLocalization == nil)
    }

    @Test("the language is handed over in the one key iOS itself reads for an app's language")
    func appleKey() {
        #expect(SystemLanguage.key == "AppleLanguages")
    }

    @Test("the note that Ozen wrote the phone's language keeps its saved name, so an update still knows what to take back")
    func writtenKeyKeepsItsName() {
        #expect(SystemLanguage.writtenKey == "OzenWroteAppleLanguages")
    }

    @Test("a chosen language is handed to iOS; 'like the phone' takes back only what Ozen wrote")
    func writesAndTakesBack() throws {
        let suite = "ozen-system-language-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let stored = { defaults.persistentDomain(forName: suite)?[SystemLanguage.key] as? [String] }

        SystemLanguage.apply(.english, to: defaults)
        #expect(stored() == ["en"])
        SystemLanguage.apply(.hebrew, to: defaults)
        #expect(stored() == ["he"])
        SystemLanguage.apply(.system, to: defaults)
        #expect(stored() == nil)

        defaults.set(["fr"], forKey: SystemLanguage.key)
        SystemLanguage.apply(.system, to: defaults)
        #expect(stored() == ["fr"])
    }
}
