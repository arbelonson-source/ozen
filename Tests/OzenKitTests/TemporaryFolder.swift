import Foundation

final class TemporaryFolder: Sendable {
    let url: URL

    init(_ prefix: String) {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}
