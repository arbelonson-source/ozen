import Foundation

/// The one temporary folder every exported file goes into, so the words in
/// those files can be cleared in one go: when conversations are deleted
/// and when the app starts.
public struct ExportScratchFolder: Sendable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    public static let standard = ExportScratchFolder(
        root: FileManager.default.temporaryDirectory.appendingPathComponent("ozen-exports", isDirectory: true)
    )

    /// A new empty folder inside the scratch folder, for one export.
    public func makeFolder() throws -> URL {
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// Removes every exported file. Not to be called while a share sheet
    /// may still be reading one.
    public func clear() {
        try? FileManager.default.removeItem(at: root)
    }
}
