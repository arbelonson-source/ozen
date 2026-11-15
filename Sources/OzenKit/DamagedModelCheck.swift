import Foundation

public struct ExpectedModelFile: Sendable, Equatable {
    public var path: String
    public var size: Int64
    public var sha256: String?

    public init(path: String, size: Int64, sha256: String? = nil) {
        self.path = path
        self.size = size
        self.sha256 = sha256
    }
}

/// Finds out whether a model that failed to load is damaged on disk, and
/// removes it so the normal download fetches it again. The folder's own
/// checks only look at which files exist, so a cut-off or altered weights
/// file passes them; this compares every file with what was published:
/// sizes first, and the (slow) checksums only when every size matches.
public enum DamagedModelCheck {
    public static func damagedPaths(
        in folder: URL,
        expected: [ExpectedModelFile],
        fileManager: FileManager = .default,
        sha256: (URL) throws -> String
    ) -> [String] {
        let wrongSize = expected.filter { file in
            let attributes = try? fileManager.attributesOfItem(atPath: folder.appendingPathComponent(file.path).path)
            return (attributes?[.size] as? NSNumber)?.int64Value != file.size
        }
        guard wrongSize.isEmpty else { return wrongSize.map(\.path) }
        return expected.compactMap { file in
            guard let published = file.sha256 else { return nil }
            let actual = try? sha256(folder.appendingPathComponent(file.path))
            return actual?.lowercased() == published.lowercased() ? nil : file.path
        }
    }

    /// Removes the model folder (its completion marker goes with it) and
    /// the hub's half-fetched leftovers, but only when at least one file
    /// differs from what was published. Nothing to compare against, or
    /// everything matching, leaves the model alone.
    public static func removeIfDamaged(
        folder: URL,
        partialFolder: URL,
        expected: [ExpectedModelFile],
        fileManager: FileManager = .default,
        sha256: (URL) throws -> String
    ) -> Bool {
        guard !expected.isEmpty,
              !damagedPaths(in: folder, expected: expected, fileManager: fileManager, sha256: sha256).isEmpty
        else { return false }
        return (try? ModelDiskSpace.delete(folder, partialFolder: partialFolder, fileManager: fileManager)) != nil
    }

    /// A release model's files, minus the Core ML packages: those are
    /// compiled and removed right after the download, so they can no
    /// longer be compared.
    public static func expected(from manifest: ReleaseModelManifest) -> [ExpectedModelFile] {
        manifest.files
            .filter { !$0.path.contains(".mlpackage/") }
            .map { ExpectedModelFile(path: $0.path, size: $0.size, sha256: $0.sha256) }
    }

    /// The hub's file listing of one model folder (`.../tree/main/<folder>?recursive=true`).
    public static func expected(fromHubListing data: Data, folderName: String) -> [ExpectedModelFile]? {
        struct Entry: Decodable {
            struct LFS: Decodable { var oid: String }
            var type: String
            var path: String
            var size: Int64?
            var lfs: LFS?
        }
        guard let entries = try? JSONDecoder().decode([Entry].self, from: data) else { return nil }
        let prefix = folderName + "/"
        let files = entries.compactMap { entry -> ExpectedModelFile? in
            guard entry.type == "file", entry.path.hasPrefix(prefix), let size = entry.size else { return nil }
            return ExpectedModelFile(path: String(entry.path.dropFirst(prefix.count)), size: size, sha256: entry.lfs?.oid)
        }
        return files.isEmpty ? nil : files
    }
}
