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
        // A size that can't be read (passing I/O error) proves nothing:
        // that file is left out. Only a missing file or a size actually
        // read and different counts.
        var unreadable = Set<String>()
        let wrongSize = expected.filter { file in
            let path = folder.appendingPathComponent(file.path).path
            guard fileManager.fileExists(atPath: path) else { return true }
            guard let attributes = try? fileManager.attributesOfItem(atPath: path) else {
                unreadable.insert(file.path)
                return false
            }
            return (attributes[.size] as? NSNumber)?.int64Value != file.size
        }
        guard wrongSize.isEmpty else { return wrongSize.map(\.path) }
        return expected.compactMap { file in
            guard !unreadable.contains(file.path), let published = file.sha256 else { return nil }
            // A file that can't be read (locked phone, passing I/O error)
            // proves nothing, so it never counts as damaged.
            guard let actual = try? sha256(folder.appendingPathComponent(file.path)) else { return nil }
            return actual.lowercased() == published.lowercased() ? nil : file.path
        }
    }

    /// Called once the fresh copy has loaded: later damage to it can be
    /// repaired again.
    public static func clearRepairedMarker(_ marker: URL, fileManager: FileManager = .default) {
        try? fileManager.removeItem(at: marker)
    }

    /// Removes the model folder (its completion marker goes with it) and
    /// the hub's half-fetched leftovers, but only when at least one file
    /// differs from what was published. Nothing to compare against, or
    /// everything matching, leaves the model alone. A model already removed
    /// once is never removed again while `repairedMarker` exists, so a load
    /// failure with another cause cannot make it download over and over.
    public static func removeIfDamaged(
        folder: URL,
        partialFolder: URL,
        expected: [ExpectedModelFile],
        repairedMarker: URL? = nil,
        fileManager: FileManager = .default,
        sha256: (URL) throws -> String
    ) -> Bool {
        if let repairedMarker, fileManager.fileExists(atPath: repairedMarker.path) { return false }
        guard !expected.isEmpty,
              !damagedPaths(in: folder, expected: expected, fileManager: fileManager, sha256: sha256).isEmpty
        else { return false }
        guard (try? ModelDiskSpace.delete(folder, partialFolder: partialFolder, fileManager: fileManager)) != nil else { return false }
        if let repairedMarker {
            try? fileManager.createDirectory(at: repairedMarker.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? Data(ISO8601DateFormatter().string(from: Date()).utf8).write(to: repairedMarker)
        }
        return true
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
    /// Only files the download fetched are compared: with `presentIn`, a
    /// listed file that is not on the phone is left out, so a file the
    /// download never fetches cannot make a good model look damaged.
    public static func expected(fromHubListing data: Data, folderName: String, presentIn folder: URL? = nil, fileManager: FileManager = .default) -> [ExpectedModelFile]? {
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
            let relative = String(entry.path.dropFirst(prefix.count))
            if let folder, !fileManager.fileExists(atPath: folder.appendingPathComponent(relative).path) { return nil }
            return ExpectedModelFile(path: relative, size: size, sha256: entry.lfs?.oid)
        }
        return files.isEmpty ? nil : files
    }
}
