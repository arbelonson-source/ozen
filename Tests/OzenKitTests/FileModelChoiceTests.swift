import Testing
@testable import OzenKit

@Suite("Which downloaded model turns a recording into text")
struct FileModelChoiceTests {
    private let ozen = "ozen-turbo-hebrew-a3-8bit"
    private let ivrit = "ivrit-large-v3-turbo-8bit"
    private let turbo = "large-v3-v20240930_626MB"
    private let turboFull = "large-v3-v20240930"
    private let slowLarge = "large-v3_947MB"
    private let slowest = "large-v3"
    private let small = "small"
    private let smallCompressed = "small_216MB"
    private let base = "base"

    private func pick(_ language: UILanguage, _ installed: [String]) -> String? {
        FileModelChoice.pick(for: language, installedVariants: Set(installed))?.variant
    }

    @Test("Hebrew takes the most accurate model that is on the phone")
    func mostAccurate() {
        #expect(pick(.hebrew, [base, turbo, ozen, small]) == ozen)
        #expect(pick(.hebrew, [base, turbo, small]) == turbo)
        #expect(pick(.hebrew, [base, small]) == small)
    }

    @Test("a tie is settled by speed, then by the catalog's order")
    func ties() {
        #expect(pick(.hebrew, [ivrit, ozen]) == ozen)
        #expect(pick(.hebrew, [ivrit, turbo]) == ivrit)
        #expect(pick(.hebrew, [turboFull, turbo]) == turbo)
        #expect(pick(.hebrew, [small, smallCompressed]) == smallCompressed)
        func option(_ variant: String, quality: Int, speed: Int) -> WhisperModelOption {
            WhisperModelOption(variant: variant, displayName: variant, sizeMB: 1, hebrewQuality: quality, speed: speed, note: "", isRecommended: false)
        }
        let slowFirst = [option("a", quality: 4, speed: 2), option("b", quality: 4, speed: 4), option("c", quality: 4, speed: 4), option("d", quality: 5, speed: 1)]
        #expect(FileModelChoice.ranked(slowFirst).map(\.variant) == ["b", "c", "a", "d"])
    }

    @Test("a model too slow for a phone is only used when nothing else is there")
    func slowLast() {
        #expect(pick(.hebrew, [slowLarge, small]) == small)
        #expect(pick(.hebrew, [slowLarge, slowest]) == slowLarge)
        #expect(pick(.hebrew, [slowest]) == slowest)
    }

    @Test("another language never gets a model trained for Hebrew alone")
    func hebrewOnly() {
        #expect(pick(.english, [ozen, ivrit, turbo, small]) == turbo)
        #expect(pick(.english, [ozen, ivrit]) == nil)
        #expect(FileModelChoice.installed(for: .english, variants: [ozen, ivrit, small]).map(\.variant) == [small])
        #expect(FileModelChoice.installed(for: .hebrew, variants: [ozen, ivrit, small]).map(\.variant) == [ozen, ivrit, small])
    }

    @Test("with nothing on the phone the normal download is offered")
    func nothingInstalled() {
        #expect(pick(.hebrew, []) == nil)
        #expect(pick(.hebrew, ["something-else"]) == nil)
        #expect(FileModelChoice.variant(for: .hebrew, installedVariants: []) == WhisperModelCatalog.recommendedVariant(for: .hebrew))
        #expect(FileModelChoice.variant(for: .english, installedVariants: [ozen]) == WhisperModelCatalog.recommendedVariant(for: .english))
        #expect(FileModelChoice.variant(for: .hebrew, installedVariants: [small]) == small)
        #expect(FileModelChoice.toDownload(for: .hebrew).variant == ozen)
        #expect(FileModelChoice.toDownload(for: .french).variant == WhisperModelCatalog.generalVariant)
    }

    @Test("the list to switch between holds only what is downloaded, best first")
    func switchList() {
        let listed = FileModelChoice.installed(for: .hebrew, variants: [base, small, ozen, turbo, "other"])
        #expect(listed.map(\.variant) == [ozen, turbo, small, base])
    }
}
