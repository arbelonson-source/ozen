import Foundation
import Testing
@testable import OzenKit

@Suite("Damaged model check")
struct DamagedModelCheckTests {
    private let weights = "weights/weight.bin"

    private func hex(_ url: URL) throws -> String {
        try Data(contentsOf: url).map { String(format: "%02x", $0) }.joined()
    }

    private func hex(_ text: String) -> String {
        text.utf8.map { String(format: "%02x", $0) }.joined()
    }

    private func makeModel(weights content: String) throws -> (folder: URL, partial: URL, expected: [ExpectedModelFile]) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-damaged-\(UUID().uuidString)", isDirectory: true)
        let folder = root.appendingPathComponent("model", isDirectory: true)
        let partial = root.appendingPathComponent("partial", isDirectory: true)
        let bundle = folder.appendingPathComponent("AudioEncoder.mlmodelc", isDirectory: true)
        try FileManager.default.createDirectory(at: bundle.appendingPathComponent("weights"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: partial, withIntermediateDirectories: true)
        try Data("meta".utf8).write(to: bundle.appendingPathComponent("coremldata.bin"))
        try Data(content.utf8).write(to: bundle.appendingPathComponent(weights))
        try ModelFolderInspector.markComplete(folder)
        let expected = [
            ExpectedModelFile(path: "AudioEncoder.mlmodelc/coremldata.bin", size: 4, sha256: hex("meta")),
            ExpectedModelFile(path: "AudioEncoder.mlmodelc/" + weights, size: 13, sha256: hex("good weights!")),
        ]
        return (folder, partial, expected)
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    @Test func goodModelIsKept() throws {
        let model = try makeModel(weights: "good weights!")
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, sha256: hex) == false)
        #expect(exists(model.folder.appendingPathComponent(ModelFolderInspector.markerName)))
    }

    @Test func truncatedWeightsAreFoundAndTheModelIsRemoved() throws {
        let model = try makeModel(weights: "good wei")
        #expect(exists(model.folder.appendingPathComponent(ModelFolderInspector.markerName)))
        #expect(DamagedModelCheck.damagedPaths(in: model.folder, expected: model.expected, sha256: hex) == ["AudioEncoder.mlmodelc/" + weights])
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, sha256: hex))
        #expect(!exists(model.folder))
        #expect(!exists(model.folder.appendingPathComponent(ModelFolderInspector.markerName)))
        #expect(!exists(model.partial))
    }

    @Test func aFlippedByteIsFoundByTheChecksum() throws {
        let model = try makeModel(weights: "good weightz!")
        #expect(DamagedModelCheck.damagedPaths(in: model.folder, expected: model.expected, sha256: hex).count == 1)
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, sha256: hex))
        #expect(!exists(model.folder))
    }

    @Test func checksumsAreSkippedWhileASizeIsWrong() throws {
        let model = try makeModel(weights: "short")
        var hashed = 0
        let paths = DamagedModelCheck.damagedPaths(in: model.folder, expected: model.expected) { _ in hashed += 1; return "" }
        #expect(paths.count == 1)
        #expect(hashed == 0)
    }

    @Test func aMissingFileCountsAsDamaged() throws {
        let model = try makeModel(weights: "good weights!")
        let more = model.expected + [ExpectedModelFile(path: "TextDecoder.mlmodelc/coremldata.bin", size: 4)]
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: more, sha256: hex))
    }

    @Test func nothingToCompareAgainstLeavesTheModelAlone() throws {
        let model = try makeModel(weights: "good wei")
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: [], sha256: hex) == false)
        #expect(exists(model.folder))
    }

    @Test func aModelIsNotRemovedTwiceWhileTheRepairedMarkerExists() throws {
        let model = try makeModel(weights: "good wei")
        let marker = model.partial.deletingLastPathComponent().appendingPathComponent("m.damaged-removed")
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, repairedMarker: marker, sha256: hex))
        #expect(exists(marker))
        let again = try makeModel(weights: "good wei")
        try FileManager.default.moveItem(at: again.folder, to: model.folder)
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, repairedMarker: marker, sha256: hex) == false)
        #expect(exists(model.folder))
    }

    @Test func aChecksumReadErrorKeepsTheModel() throws {
        struct Unreadable: Error {}
        let model = try makeModel(weights: "good weights!")
        #expect(DamagedModelCheck.damagedPaths(in: model.folder, expected: model.expected) { _ in throw Unreadable() }.isEmpty)
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected) { _ in throw Unreadable() } == false)
        #expect(exists(model.folder))
    }

    @Test func aLoadedFreshCopyCanBeRepairedAgain() throws {
        let model = try makeModel(weights: "good wei")
        let marker = model.partial.deletingLastPathComponent().appendingPathComponent("m.damaged-removed")
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, repairedMarker: marker, sha256: hex))
        DamagedModelCheck.clearRepairedMarker(marker)
        #expect(!exists(marker))
        let again = try makeModel(weights: "good wei")
        try FileManager.default.moveItem(at: again.folder, to: model.folder)
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, repairedMarker: marker, sha256: hex))
    }

    @Test func listedFilesTheDownloadNeverFetchedAreNotCountedAsDamaged() throws {
        let model = try makeModel(weights: "good weights!")
        let json = """
        [{"type":"file","path":"m/AudioEncoder.mlmodelc/coremldata.bin","size":4},
         {"type":"file","path":"m/README.md","size":900}]
        """
        let files = DamagedModelCheck.expected(fromHubListing: Data(json.utf8), folderName: "m", presentIn: model.folder)
        #expect(files?.map(\.path) == ["AudioEncoder.mlmodelc/coremldata.bin"])
    }

    @Test func releasePackagesAreLeftOutBecauseTheyAreCompiledAway() {
        let manifest = ReleaseModelManifest(files: [
            ReleaseModelFile(path: "Enc.mlpackage/Data/weight.bin", asset: "a", size: 9, sha256: String(repeating: "a", count: 64)),
            ReleaseModelFile(path: "config.json", asset: "b", size: 5, sha256: String(repeating: "b", count: 64)),
        ])
        #expect(DamagedModelCheck.expected(from: manifest).map(\.path) == ["config.json"])
    }

    @Test func hubListingGivesSizesAndChecksumsRelativeToTheFolder() {
        let json = """
        [{"type":"directory","path":"m/X.mlmodelc"},
         {"type":"file","path":"m/X.mlmodelc/weights/weight.bin","size":12,"lfs":{"oid":"abc","size":12}},
         {"type":"file","path":"m/config.json","size":7},
         {"type":"file","path":"other/skip.bin","size":1}]
        """
        let files = DamagedModelCheck.expected(fromHubListing: Data(json.utf8), folderName: "m")
        #expect(files == [
            ExpectedModelFile(path: "X.mlmodelc/weights/weight.bin", size: 12, sha256: "abc"),
            ExpectedModelFile(path: "config.json", size: 7, sha256: nil),
        ])
        #expect(DamagedModelCheck.expected(fromHubListing: Data("nope".utf8), folderName: "m") == nil)
    }
}
