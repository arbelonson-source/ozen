import Foundation
import Testing
@testable import OzenKit

@Suite("Sizes are written in Ozen's language")
struct ByteSizeTests {
    @Test("kilobytes, megabytes and gigabytes, decimal, as the model list writes them")
    func text() {
        Localization.$override.withValue(.english) { englishText() }
    }

    private func englishText() {
        #expect(ByteSize.text(0) == "0\u{00A0}KB")
        #expect(ByteSize.text(400) == "1\u{00A0}KB")
        #expect(ByteSize.text(640_000) == "640\u{00A0}KB")
        #expect(ByteSize.text(999_000) == "999\u{00A0}KB")
        #expect(ByteSize.text(999_001) == "1\u{00A0}MB")
        #expect(ByteSize.text(1_000_000) == "1\u{00A0}MB")
        #expect(ByteSize.text(819_200_000) == "819\u{00A0}MB")
        #expect(ByteSize.text(999_400_000) == "999\u{00A0}MB")
        #expect(ByteSize.text(999_600_000) == "1.0\u{00A0}GB")
        #expect(ByteSize.text(1_619_000_000) == "1.6\u{00A0}GB")
        for option in WhisperModelCatalog.options {
            #expect(ByteSize.text(Int64(option.sizeMB) * StorageSpaceGate.bytesPerMegabyte) == option.sizeLabel, "\(option.variant)")
        }
    }

    @Test("French, Russian and Arabic units, and a decimal comma where the language writes one")
    func eachLanguagesUnits() {
        let cases: [(UILanguage, [String])] = [
            (.french, ["640\u{00A0}ko", "819\u{00A0}Mo", "1,6\u{00A0}Go"]),
            (.german, ["640\u{00A0}KB", "819\u{00A0}MB", "1,6\u{00A0}GB"]),
            (.russian, ["640\u{00A0}КБ", "819\u{00A0}МБ", "1,6\u{00A0}ГБ"]),
            (.ukrainian, ["640\u{00A0}КБ", "819\u{00A0}МБ", "1,6\u{00A0}ГБ"]),
            (.arabic, ["640\u{00A0}كيلوبايت", "819\u{00A0}ميغابايت", "1.6\u{00A0}غيغابايت"]),
            (.hebrew, ["640\u{00A0}KB", "819\u{00A0}MB", "1.6\u{00A0}GB"]),
        ]
        for (language, expected) in cases {
            Localization.$override.withValue(language) {
                #expect([640_000, 819_200_000, 1_619_000_000].map { ByteSize.text($0) } == expected, "\(language)")
            }
        }
        Localization.$override.withValue(.spanish) {
            #expect(ByteSize.roundedUp(megabytes: 1_301) == "1,4\u{00A0}GB")
            #expect(ByteSize.roundedUp(megabytes: 999) == "999\u{00A0}MB")
        }
        Localization.$override.withValue(.portuguese) {
            #expect(ByteSize.megabytes(450) == "450\u{00A0}MB")
        }
    }

    @Test("the model list's sizes follow the language too")
    func modelSizesFollowTheLanguage() {
        Localization.$override.withValue(.french) {
            for option in WhisperModelCatalog.options {
                #expect(ByteSize.text(Int64(option.sizeMB) * StorageSpaceGate.bytesPerMegabyte) == option.sizeLabel, "\(option.variant)")
                #expect(option.sizeLabel.hasSuffix("Mo") || option.sizeLabel.hasSuffix("Go"), "\(option.sizeLabel)")
            }
        }
    }

    @Test("the unit is the one each translation already writes, as in “about 120 MB of mobile data an hour”")
    func unitsMatchTheTranslations() {
        for language in UILanguage.allCases where language != .hebrew {
            Localization.$override.withValue(language) {
                let sentence = tr("", "Away from Wi‑Fi it uses about 120 MB of mobile data an hour.")
                let size = ByteSize.megabytes(120).replacingOccurrences(of: "\u{00A0}", with: " ")
                #expect(sentence.contains(size), "\(language): \(sentence)")
            }
        }
    }

    @Test("a size never wraps between its number and its unit, at any text size")
    func numberAndUnitStayTogether() {
        for language in UILanguage.allCases {
            Localization.$override.withValue(language) {
                let sizes = [0, 640_000, 819_200_000, 1_619_000_000].map { ByteSize.text($0) } + WhisperModelCatalog.options.map(\.sizeLabel) + [ByteSize.megabytes(450), ByteSize.roundedUp(megabytes: 1_301)]
                for size in sizes {
                    #expect(!size.contains(" "), "\(language): \(size)")
                    #expect(size.contains("\u{00A0}"), "\(language): \(size)")
                }
            }
        }
    }

    @Test("no size goes through iOS's formatter, which writes its units in the phone's language rather than Ozen's")
    func noPhoneLanguageFormatter() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var checked = 0
        for folder in ["App", "Sources"] {
            let files = FileManager.default.enumerator(at: root.appendingPathComponent(folder), includingPropertiesForKeys: nil)
            while let file = files?.nextObject() as? URL {
                guard file.pathExtension == "swift" else { continue }
                let text = try String(contentsOf: file, encoding: .utf8)
                #expect(!text.contains("ByteCountFormatter"), "\(file.lastPathComponent)")
                checked += 1
            }
        }
        #expect(checked > 100)
    }
}
