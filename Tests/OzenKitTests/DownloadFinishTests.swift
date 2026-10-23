import Foundation
import Testing
@testable import OzenKit

@Suite("Download finish")
struct DownloadFinishTests {
    private func makeFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("ozen-finish-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private func hasMarker(_ folder: URL) -> Bool {
        FileManager.default.fileExists(atPath: folder.appendingPathComponent(ModelFolderInspector.markerName).path)
    }

    private func finishing(_ folder: URL, cancelled: Bool) async -> String {
        await Task {
            if cancelled { withUnsafeCurrentTask { $0?.cancel() } }
            do {
                try DownloadFinish.finish(folder)
                return "finished"
            } catch is CancellationError {
                return "cancelled"
            } catch {
                return "failed: \(error)"
            }
        }.value
    }

    @Test("a download whose task was cancelled is not marked complete", .timeLimit(.minutes(1)))
    func cancelled() async throws {
        let folder = try makeFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let outcome = await finishing(folder, cancelled: true)
        #expect(outcome == "cancelled")
        #expect(!hasMarker(folder))
    }

    @Test("a download that was not cancelled is marked complete", .timeLimit(.minutes(1)))
    func finished() async throws {
        let folder = try makeFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let outcome = await finishing(folder, cancelled: false)
        #expect(outcome == "finished")
        #expect(hasMarker(folder))
    }
}
