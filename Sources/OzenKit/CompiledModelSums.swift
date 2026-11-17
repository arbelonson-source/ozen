import Foundation

public enum CompiledModelSums {
    public static let fileName = ".ozen-compiled-sums.json"
    static let bundleSuffix = ".mlmodelc"

    struct Entry: Codable, Equatable {
        var path: String
        var size: Int64
        var sha256: String
    }

    struct Document: Codable {
        var version: Int
        var files: [Entry]
    }

    public static func record(
        in folder: URL,
        fileManager: FileManager = .default,
        sha256: (URL) throws -> String
    ) throws {
        let names = try fileManager.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(bundleSuffix) }.sorted()
        var entries = [Entry]()
        for name in names {
            for relative in try files(under: name, in: folder, fileManager: fileManager) {
                let url = folder.appendingPathComponent(relative)
                let size = (try fileManager.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
                entries.append(Entry(path: relative, size: size, sha256: try sha256(url)))
            }
        }
        guard !entries.isEmpty else { return }
        let data = try JSONEncoder().encode(Document(version: 1, files: entries))
        try data.write(to: folder.appendingPathComponent(fileName), options: .atomic)
    }

    public static func expected(in folder: URL) -> [ExpectedModelFile] {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(fileName)),
              let document = try? JSONDecoder().decode(Document.self, from: data),
              document.version == 1 else { return [] }
        return document.files.map { ExpectedModelFile(path: $0.path, size: $0.size, sha256: $0.sha256) }
    }

    public static func merged(_ expected: [ExpectedModelFile], in folder: URL) -> [ExpectedModelFile] {
        let known = Set(expected.map(\.path))
        return expected + self.expected(in: folder).filter { !known.contains($0.path) }
    }

    private static func files(under relative: String, in folder: URL, fileManager: FileManager) throws -> [String] {
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: folder.appendingPathComponent(relative).path, isDirectory: &isDirectory) else { return [] }
        guard isDirectory.boolValue else { return [relative] }
        let children = try fileManager.contentsOfDirectory(atPath: folder.appendingPathComponent(relative).path).sorted()
        return try children.flatMap { try files(under: relative + "/" + $0, in: folder, fileManager: fileManager) }
    }
}
