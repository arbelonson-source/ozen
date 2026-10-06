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
            partialFolder: ModelDiskSpace.partialFolder(modelsRoot: modelsRoot, folderName: option.folderName),
            totalBytes: Int64(option.sizeMB) * 1_000_000
        )
    }

    public func fraction(reported: Double) -> Double {
        guard reported < 1 else {
            lock.withLock { shown = 1 }
            return 1
        }
        let finished = ModelDiskSpace.bytes(in: folder)
        let arriving = ModelDiskSpace.bytes(in: partialFolder) { $0.hasSuffix(".incomplete") }
        let measured = min(Double(finished + arriving) / Double(max(totalBytes, 1)), 0.99)
        return lock.withLock {
            shown = max(shown, measured)
            return shown
        }
    }
}
