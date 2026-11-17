import Foundation
import Testing
@testable import OzenKit

@Suite("Compiled model checksums")
struct CompiledModelSumsTests {
    private struct Echo: StreamingHasher {
        var seen = Data()
        mutating func update(_ data: Data) { seen.append(data) }
        func finalize() -> String { seen.map { String(format: "%02x", $0) }.joined() }
    }

    private func echo(_ url: URL) throws -> String {
        try ChunkedDigest.hex(of: url, makeHasher: { Echo() })
    }

    private func makeFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-sums-\(UUID().uuidString)", isDirectory: true)
        for name in ["MelSpectrogram", "AudioEncoder", "TextDecoder"] {
            let bundle = folder.appendingPathComponent(name + ".mlmodelc", isDirectory: true)
            try FileManager.default.createDirectory(at: bundle.appendingPathComponent("weights"), withIntermediateDirectories: true)
            try Data("meta-\(name)".utf8).write(to: bundle.appendingPathComponent("coremldata.bin"))
            try Data("weights-of-\(name)".utf8).write(to: bundle.appendingPathComponent("weights/weight.bin"))
        }
        try Data("{}".utf8).write(to: folder.appendingPathComponent("config.json"))
        try ModelFolderInspector.markComplete(folder)
        return folder
    }

    private func damaged(_ folder: URL) -> [String] {
        let expected = CompiledModelSums.merged([], in: folder)
        return DamagedModelCheck.damagedPaths(in: folder, expected: expected, sha256: echo)
    }

    @Test func aSidecarIsWrittenAndMatchesAFreshFolder() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        let expected = CompiledModelSums.expected(in: folder)
        #expect(expected.count == 6)
        #expect(expected.map(\.path).contains("AudioEncoder.mlmodelc/weights/weight.bin"))
        #expect(!expected.map(\.path).contains("config.json"))
        #expect(damaged(folder).isEmpty)
        #expect(ModelFolderInspector.state(of: folder) == .verified)
    }

    @Test func aFlippedByteInAWeightsFileIsDamaged() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        let weights = folder.appendingPathComponent("AudioEncoder.mlmodelc/weights/weight.bin")
        try Data("weightz-of-AudioEncoder".utf8).write(to: weights)
        #expect(damaged(folder) == ["AudioEncoder.mlmodelc/weights/weight.bin"])
    }

    @Test func aTruncatedWeightsFileIsDamaged() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        try Data("weights".utf8).write(to: folder.appendingPathComponent("TextDecoder.mlmodelc/weights/weight.bin"))
        #expect(damaged(folder) == ["TextDecoder.mlmodelc/weights/weight.bin"])
    }

    @Test func aMissingBundleFileIsDamaged() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        try FileManager.default.removeItem(at: folder.appendingPathComponent("TextDecoder.mlmodelc/coremldata.bin"))
        #expect(damaged(folder) == ["TextDecoder.mlmodelc/coremldata.bin"])
    }

    @Test func aFolderWithoutASidecarIsNotCalledDamaged() throws {
        let folder = try makeFolder()
        #expect(CompiledModelSums.expected(in: folder).isEmpty)
        #expect(!DamagedModelCheck.removeIfDamaged(folder: folder, partialFolder: folder.appendingPathComponent("p"), expected: CompiledModelSums.merged([], in: folder), sha256: echo))
        #expect(FileManager.default.fileExists(atPath: folder.path))
    }

    @Test func aDamagedCompiledModelIsRemovedAfterALoadFailure() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        try Data("x".utf8).write(to: folder.appendingPathComponent("AudioEncoder.mlmodelc/weights/weight.bin"))
        let partial = folder.deletingLastPathComponent().appendingPathComponent("partial-\(UUID().uuidString)")
        #expect(DamagedModelCheck.removeIfDamaged(folder: folder, partialFolder: partial, expected: CompiledModelSums.merged([], in: folder), sha256: echo))
        #expect(!FileManager.default.fileExists(atPath: folder.path))
    }

    @Test func manifestEntriesAreKeptAndNotDuplicated() throws {
        let folder = try makeFolder()
        try CompiledModelSums.record(in: folder, sha256: echo)
        let manifest = [ExpectedModelFile(path: "config.json", size: 2, sha256: "7b7d"), ExpectedModelFile(path: "AudioEncoder.mlmodelc/coremldata.bin", size: 1, sha256: nil)]
        let merged = CompiledModelSums.merged(manifest, in: folder)
        #expect(merged.count == 7)
        #expect(merged.first?.path == "config.json")
    }

    @Test func aLargeFileIsHashedInChunks() throws {
        let folder = try makeFolder()
        let big = folder.appendingPathComponent("big.bin")
        try Data(repeating: 7, count: 10_000).write(to: big)
        var reads = 0
        var largest = 0
        let digest = try ChunkedDigest.hex(of: big, chunkSize: 4096, makeHasher: { Echo() }) { handle, count in
            reads += 1
            let chunk = try handle.read(upToCount: count)
            largest = max(largest, chunk?.count ?? 0)
            return chunk
        }
        #expect(reads == 4)
        #expect(largest == 4096)
        #expect(digest.count == 20_000)
    }
}
