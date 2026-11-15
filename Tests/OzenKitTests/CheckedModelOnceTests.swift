import Foundation
import Testing
@testable import OzenKit

@Suite("Good model checked once")
struct CheckedModelOnceTests {
    private func hex(_ text: String) -> String {
        text.utf8.map { String(format: "%02x", $0) }.joined()
    }

    private func makeModel() throws -> (folder: URL, partial: URL, memo: URL, expected: [ExpectedModelFile]) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-once-\(UUID().uuidString)", isDirectory: true)
        let folder = root.appendingPathComponent("model", isDirectory: true)
        let partial = root.appendingPathComponent("partial", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("good weights!".utf8).write(to: folder.appendingPathComponent("weight.bin"))
        let expected = [ExpectedModelFile(path: "weight.bin", size: 13, sha256: hex("good weights!"))]
        return (folder, partial, root.appendingPathComponent("model.checked-good"), expected)
    }

    private func check(_ model: (folder: URL, partial: URL, memo: URL, expected: [ExpectedModelFile]), hashes: inout Int) -> Bool {
        var count = 0
        let removed = DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, checkedMemo: model.memo) { url in
            count += 1
            return hex(String(decoding: try Data(contentsOf: url), as: UTF8.self))
        }
        hashes += count
        return removed
    }

    @Test func aGoodFileIsHashedOnlyOnce() throws {
        let model = try makeModel()
        var hashes = 0
        #expect(check(model, hashes: &hashes) == false)
        #expect(check(model, hashes: &hashes) == false)
        #expect(check(model, hashes: &hashes) == false)
        #expect(hashes == 1)
    }

    @Test func aChangedModificationTimeIsHashedAgain() throws {
        let model = try makeModel()
        var hashes = 0
        #expect(check(model, hashes: &hashes) == false)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(3600)], ofItemAtPath: model.folder.appendingPathComponent("weight.bin").path)
        #expect(check(model, hashes: &hashes) == false)
        #expect(hashes == 2)
    }

    @Test func aFileDamagedAfterBeingFoundFineIsRemoved() throws {
        let model = try makeModel()
        var hashes = 0
        #expect(check(model, hashes: &hashes) == false)
        let weights = model.folder.appendingPathComponent("weight.bin")
        try Data("good weightz!".utf8).write(to: weights)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(3600)], ofItemAtPath: weights.path)
        #expect(check(model, hashes: &hashes))
        #expect(!FileManager.default.fileExists(atPath: model.folder.path))
        #expect(!FileManager.default.fileExists(atPath: model.memo.path))
    }

    @Test func aReadErrorIsNotRemembered() throws {
        struct Unreadable: Error {}
        let model = try makeModel()
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, checkedMemo: model.memo) { _ in throw Unreadable() } == false)
        #expect(!FileManager.default.fileExists(atPath: model.memo.path))
    }

    private final class FailsAfterFirstRead: FileManager, @unchecked Sendable {
        private var seen = Set<String>()
        override func attributesOfItem(atPath path: String) throws -> [FileAttributeKey: Any] {
            if !seen.insert(path).inserted { throw CocoaError(.fileReadUnknown) }
            return try super.attributesOfItem(atPath: path)
        }
    }

    @Test func aFileThatWasNotHashedIsNotRemembered() throws {
        let model = try makeModel()
        let manager = FailsAfterFirstRead()
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: model.expected, checkedMemo: model.memo, fileManager: manager, sha256: { _ in "" }) == false)
        #expect(!FileManager.default.fileExists(atPath: model.memo.path))
    }

    @Test func aChangedPublishedSizeIsCheckedAgain() throws {
        let model = try makeModel()
        let sizeOnly = [ExpectedModelFile(path: "weight.bin", size: 13)]
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: sizeOnly, checkedMemo: model.memo) { _ in "" } == false)
        let longer = [ExpectedModelFile(path: "weight.bin", size: 14)]
        #expect(DamagedModelCheck.removeIfDamaged(folder: model.folder, partialFolder: model.partial, expected: longer, checkedMemo: model.memo) { _ in "" })
    }
}
