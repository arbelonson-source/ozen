import Foundation
import OzenKit
import OzenPlatform

nonisolated final class RecordingFile: @unchecked Sendable {
    let url: URL
    let displayName: String

    init(url: URL, displayName: String, cleanups: [() -> Void]) {
        self.url = url
        self.displayName = displayName
    }

    func finish() {}
}

nonisolated enum RecordingFiles {
    static var copiesFolder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("ozen-recording-copies", isDirectory: true)
    }

    static func sweepCopies(in folder: URL = RecordingFiles.copiesFolder) {}

    static func open(_ picked: URL, guardian: SharedRecordingGuard) async throws -> RecordingFile {
        throw RecordingReadProblem.unreadable
    }

    static func writeShare(_ text: String, name: String, fileExtension: String) -> URL? { nil }
}
