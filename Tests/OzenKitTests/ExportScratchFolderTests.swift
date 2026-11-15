import Foundation
import Testing
@testable import OzenKit

@Suite("Export scratch folder")
struct ExportScratchFolderTests {
    private func scratch() -> ExportScratchFolder {
        ExportScratchFolder(root: FileManager.default.temporaryDirectory
            .appendingPathComponent("scratch-\(UUID().uuidString)", isDirectory: true))
    }

    @Test("clearing removes every exported file")
    func clearLeavesNothing() throws {
        let scratch = scratch()
        defer { scratch.clear() }
        for name in ["one.txt", "two.txt"] {
            let folder = try scratch.makeFolder()
            try Data("private words".utf8).write(to: folder.appendingPathComponent(name))
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: scratch.root.path).count == 2)

        scratch.clear()

        let left = (try? FileManager.default.contentsOfDirectory(atPath: scratch.root.path)) ?? []
        #expect(left.isEmpty)
    }

    @Test("clearing when nothing was exported does nothing")
    func clearWithoutFolder() {
        let scratch = scratch()
        scratch.clear()
        #expect(!FileManager.default.fileExists(atPath: scratch.root.path))
    }

    @Test("each export gets its own folder inside the scratch folder")
    func foldersAreInside() throws {
        let scratch = scratch()
        defer { scratch.clear() }
        let first = try scratch.makeFolder()
        let second = try scratch.makeFolder()
        #expect(first != second)
        #expect(first.deletingLastPathComponent().standardizedFileURL == scratch.root.standardizedFileURL)
    }
}
