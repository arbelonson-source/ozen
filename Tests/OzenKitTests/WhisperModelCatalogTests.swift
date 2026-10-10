import Testing
@testable import OzenKit

@Suite("WhisperModelCatalog")
struct WhisperModelCatalogTests {
    @Test("the default and recommended variants both exist in the catalog")
    func defaultsExist() {
        #expect(WhisperModelCatalog.option(for: WhisperModelCatalog.defaultVariant) != nil)
        #expect(WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant)?.isRecommended == true)
        #expect(AppSettings.default.whisperModelVariant == WhisperModelCatalog.defaultVariant)
    }

    @Test("a fresh install gets the recommended model, not Small")
    func freshInstallGetsRecommended() {
        #expect(WhisperModelCatalog.defaultVariant == WhisperModelCatalog.recommendedVariant)
        #expect(AppSettings.default.whisperModelVariant == "ozen-turbo-hebrew-a3-8bit")
    }

    @Test("the recommended model improves on the small ones, not on its equals or betters or on strangers")
    func recommendedImproves() {
        for weaker in ["tiny", "base", "small_216MB", "small", "medium", "large-v3-v20240930_626MB", "large-v3-v20240930", "large-v3_947MB", "large-v3"] {
            #expect(WhisperModelCatalog.recommendedImproves(on: weaker), "\(weaker)")
        }
        #expect(!WhisperModelCatalog.recommendedImproves(on: WhisperModelCatalog.recommendedVariant))
        // What it was trained from: better in noise, level up close, so no
        // "this model gets many words wrong" on a phone that runs it.
        #expect(!WhisperModelCatalog.recommendedImproves(on: "ivrit-large-v3-turbo-8bit"))
        #expect(!WhisperModelCatalog.recommendedImproves(on: "no-such-model"))
    }

    @Test("no model is rated above the recommended one for Hebrew, and the only one rated level is the model it was trained from")
    func recommendedRatedHighest() throws {
        let recommended = try #require(WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant))
        for option in WhisperModelCatalog.options where option.variant != recommended.variant {
            if option.variant == "ivrit-large-v3-turbo-8bit" {
                #expect(option.hebrewQuality == recommended.hebrewQuality)
            } else {
                #expect(option.hebrewQuality < recommended.hebrewQuality, "\(option.variant)")
            }
        }
    }

    @Test("exactly one option is recommended and variants are unique")
    func oneRecommendation() {
        #expect(WhisperModelCatalog.options.filter(\.isRecommended).count == 1)
        let variants = WhisperModelCatalog.options.map(\.variant)
        #expect(Set(variants).count == variants.count)
    }

    @Test("ratings stay in the 1...5 range and no English-only model slipped in")
    func sanity() {
        for option in WhisperModelCatalog.options {
            #expect((1...5).contains(option.hebrewQuality), "\(option.variant)")
            #expect((1...5).contains(option.speed), "\(option.variant)")
            #expect(!option.variant.contains(".en"), "\(option.variant)")
            #expect(!option.variant.contains("distil"), "\(option.variant)")
            #expect(option.sizeMB > 0)
        }
    }

    @Test("folder names round-trip")
    func folderNames() {
        #expect(WhisperModelCatalog.folderName(for: "small") == "openai_whisper-small")
        #expect(WhisperModelCatalog.folderName(for: "ivrit-large-v3-turbo-8bit") == "ivrit-ai_whisper-large-v3-turbo_8bit")
        #expect(WhisperModelCatalog.variant(fromFolderName: "ivrit-ai_whisper-large-v3-turbo_8bit") == "ivrit-large-v3-turbo-8bit")
        #expect(WhisperModelCatalog.option(for: "ivrit-large-v3-turbo-8bit")?.source == .ozenRelease(tag: "model-ivrit-large-v3-turbo-8bit-1"))
        #expect(WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant)?.source == .ozenRelease(tag: "model-ozen-turbo-hebrew-a3-8bit-1"))
        #expect(WhisperModelCatalog.folderName(for: "ozen-turbo-hebrew-a3-8bit") == "ozen_whisper-large-v3-turbo-hebrew-a3_8bit")
        #expect(WhisperModelCatalog.variant(fromFolderName: "ozen_whisper-large-v3-turbo-hebrew-a3_8bit") == "ozen-turbo-hebrew-a3-8bit")
        #expect(WhisperModelCatalog.variant(fromFolderName: "openai_whisper-large-v3-v20240930_626MB") == "large-v3-v20240930_626MB")
        #expect(WhisperModelCatalog.variant(fromFolderName: "openai_whisper-unlisted-test") == "unlisted-test")
        #expect(WhisperModelCatalog.variant(fromFolderName: "someone_whisper-unlisted-test") == nil)
        #expect(WhisperModelCatalog.variant(fromFolderName: "distil-whisper_distil-large-v3") == nil)
    }

    @Test("a model compiled on the phone needs room for the packages and the compiled bundles together")
    func installRoom() {
        #expect(WhisperModelCatalog.option(for: "small")?.installMegabytes == 486)
        #expect(WhisperModelCatalog.option(for: "ivrit-large-v3-turbo-8bit")?.installMegabytes == 819 * 2)
        #expect(WhisperModelCatalog.option(for: "ozen-turbo-hebrew-a3-8bit")?.installMegabytes == 819 * 2)
    }

    @Test("an interrupted install only needs room for the part not yet on the phone")
    func remainingInstallRoom() throws {
        let ivrit = try #require(WhisperModelCatalog.option(for: "ivrit-large-v3-turbo-8bit"))
        let megabyte: Int64 = 1_000_000
        #expect(ivrit.remainingInstallMegabytes(onDiskBytes: 0) == 819 * 2)
        #expect(ivrit.remainingInstallMegabytes(onDiskBytes: 819 * megabyte) == 819)
        #expect(ivrit.remainingInstallMegabytes(onDiskBytes: 5_000 * megabyte) == 1)
    }

    @Test("the model screen lists the recommended model first and every other in the catalog's order")
    func listedOrder() {
        let listed = WhisperModelCatalog.listed.map(\.variant)
        #expect(listed.first == WhisperModelCatalog.recommendedVariant)
        #expect(Array(listed.dropFirst()) == WhisperModelCatalog.options.map(\.variant).filter { $0 != WhisperModelCatalog.recommendedVariant })
    }

    @Test("with the whole download on the phone, only the compile's room and none of the download is left")
    func wholeDownloadOnDisk() throws {
        let ivrit = try #require(WhisperModelCatalog.option(for: "ivrit-large-v3-turbo-8bit"))
        let releaseBytes: Int64 = 818_952_412
        #expect(abs(ivrit.remainingInstallMegabytes(onDiskBytes: releaseBytes) - ivrit.sizeMB) <= 1)
        #expect(ivrit.remainingDownloadMegabytes(onDiskBytes: releaseBytes) == 1)
        #expect(ivrit.remainingDownloadMegabytes(onDiskBytes: 0) == 819)
    }

    @Test("size labels switch to GB at a thousand megabytes")
    func sizeLabels() {
        #expect(WhisperModelCatalog.option(for: "small")?.sizeLabel == "486\u{00A0}MB")
        #expect(WhisperModelCatalog.option(for: "large-v3")?.sizeLabel == "3.1\u{00A0}GB")
    }
}
