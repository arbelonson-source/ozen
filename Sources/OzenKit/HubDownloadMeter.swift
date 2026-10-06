import Foundation

public final class HubDownloadMeter: @unchecked Sendable {
    public let folder: URL
    public let partialFolder: URL
    public let totalBytes: Int64
    private let lock = NSLock()
    private var shown = 0.0

    public init(folder: URL, partialFolder: URL, totalBytes: Int64) {
        self.folder = folder
        self.partialFolder = partialFolder
        self.totalBytes = totalBytes
    }

    public convenience init?(modelsRoot: URL, variant: String) {
        guard let option = WhisperModelCatalog.option(for: variant), option.source == .whisperKitHub else { return nil }
        self.init(
            folder: modelsRoot.appendingPathComponent(option.folderName, isDirectory: true),
            partialFolder: modelsRoot
                .appendingPathComponent(".cache/huggingface/download", isDirectory: true)
                .appendingPathComponent(option.folderName, isDirectory: true),
            totalBytes: Int64(option.sizeMB) * 1_000_000
        )
    }

    public func fraction(reported: Double) -> Double {
        guard reported < 1 else {
            lock.withLock { shown = 1 }
            return 1
        }
        let finished = Self.bytes(in: folder) { _ in true }
        let arriving = Self.bytes(in: partialFolder) { $0.hasSuffix(".incomplete") }
        let measured = min(Double(finished + arriving) / Double(max(totalBytes, 1)), 0.99)
        return lock.withLock {
            shown = max(shown, measured)
            return shown
        }
    }

    static func bytes(in folder: URL, counting: (String) -> Bool) -> Int64 {
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
