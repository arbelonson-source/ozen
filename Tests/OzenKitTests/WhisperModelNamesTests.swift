import Testing
@testable import OzenKit

@Suite("Speech-model names in the app's language")
struct WhisperModelNamesTests {
    private static let names: [(variant: String, hebrew: String, english: String)] = [
        ("tiny", "זעיר", "Tiny"),
        ("base", "בסיסי", "Base"),
        ("small_216MB", "קטן (דחוס)", "Small (compressed)"),
        ("small", "קטן", "Small"),
        ("ozen-turbo-hebrew-a3-8bit", "טורבו עברית של אוזן", "Ozen Turbo Hebrew"),
        ("ivrit-large-v3-turbo-8bit", "טורבו עברית של ivrit.ai", "Turbo Hebrew by ivrit.ai"),
        ("large-v3-v20240930_626MB", "טורבו (דחוס)", "Turbo (compressed)"),
        ("large-v3-v20240930", "טורבו", "Turbo"),
        ("medium", "בינוני", "Medium"),
        ("large-v3_947MB", "גדול v3 (דחוס)", "Large v3 (compressed)"),
        ("large-v3", "גדול v3", "Large v3"),
    ]

    @Test("every catalog model has a name in each of Hebrew and English")
    func everyModelCovered() {
        #expect(Set(Self.names.map(\.variant)) == Set(WhisperModelCatalog.options.map(\.variant)))
    }

    @Test("Hebrew names read as Hebrew when the app is in Hebrew")
    func hebrewNames() {
        Localization.$override.withValue(.hebrew) {
            for entry in Self.names {
                #expect(WhisperModelCatalog.option(for: entry.variant)?.displayName == entry.hebrew, "\(entry.variant)")
            }
        }
    }

    @Test("English names stay English when the app is in English")
    func englishNames() {
        Localization.$override.withValue(.english) {
            for entry in Self.names {
                #expect(WhisperModelCatalog.option(for: entry.variant)?.displayName == entry.english, "\(entry.variant)")
            }
        }
    }

    @Test("a saved conversation's details line names the model in the line's language")
    func sourceLineNamesModel() {
        let record = TranscriptSessionRecord(startedAt: 0, engine: .whisperKit, modelVariant: "ivrit-large-v3-turbo-8bit", inputName: nil, segments: [])
        #expect(record.sourceLine(in: .hebrew) == "\u{200F}Whisper (במכשיר) · טורבו עברית של ivrit.ai")
        #expect(record.sourceLine(in: .english) == "Whisper (on device) · Turbo Hebrew by ivrit.ai")
    }
}
