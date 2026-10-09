import Foundation

public enum ModelDiskSpace {
    public static func partialFolder(modelsRoot: URL, folderName: String) -> URL {
        modelsRoot
            .appendingPathComponent(".cache/huggingface/download", isDirectory: true)
            .appendingPathComponent(folderName, isDirectory: true)
    }

    public static func state(of folder: URL, partialFolder: URL, fileManager: FileManager = .default) -> ModelFolderState {
        let state = ModelFolderInspector.state(of: folder, fileManager: fileManager)
        guard state == .missing || state == .unverified else { return state }
        return bytes(in: partialFolder, counting: { $0.hasSuffix(".incomplete") }) > 0 ? .partial : state
    }

    public static func size(of folder: URL, partialFolder: URL) -> Int64 {
        bytes(in: folder) + bytes(in: partialFolder)
    }

    public static func delete(_ folder: URL, partialFolder: URL, fileManager: FileManager = .default) throws {
        for url in [folder, partialFolder] where fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    public static func bytes(in folder: URL, counting: (String) -> Bool = { _ in true }) -> Int64 {
        let keys: [URLResourceKey] = [.fileSizeKey, .isRegularFileKey]
        guard let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in files where counting(file.lastPathComponent) {
            guard let values = try? file.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }
}
