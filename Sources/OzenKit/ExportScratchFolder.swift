import Foundation

public struct ExportScratchFolder: Sendable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    public static let standard = ExportScratchFolder(
        root: FileManager.default.temporaryDirectory.appendingPathComponent("ozen-exports", isDirectory: true)
    )

    public func makeFolder() throws -> URL {
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    public func clear() {
        try? FileManager.default.removeItem(at: root)
    }
}
