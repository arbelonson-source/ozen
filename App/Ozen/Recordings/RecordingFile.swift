import Foundation
import OzenKit
import OzenPlatform

nonisolated final class RecordingFile: @unchecked Sendable {
    let url: URL
    let displayName: String
    private let lock = NSLock()
    private var cleanups: [() -> Void]

    init(url: URL, displayName: String, cleanups: [() -> Void]) {
        self.url = url
        self.displayName = displayName
        self.cleanups = cleanups
    }

    func finish() {
        let pending = lock.withLock { () -> [() -> Void] in
            defer { cleanups = [] }
            return cleanups
        }
        for cleanup in pending { cleanup() }
    }

    deinit { finish() }
}

nonisolated enum RecordingFiles {
    static var copiesFolder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("ozen-recording-copies", isDirectory: true)
    }

    static func guardian() -> SharedRecordingGuard {
        let manager = FileManager.default
        let documents = manager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let airdrop = manager.temporaryDirectory.appendingPathComponent("\(Bundle.main.bundleIdentifier ?? "")-Inbox", isDirectory: true)
        return SharedRecordingGuard(
            containerRoots: [URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)],
            allowed: [documents.appendingPathComponent("Inbox", isDirectory: true), copiesFolder, airdrop]
        )
    }

    static func sweepCopies(in folder: URL = RecordingFiles.copiesFolder) {
        try? FileManager.default.removeItem(at: folder)
    }

    static func open(_ picked: URL, guardian: SharedRecordingGuard = RecordingFiles.guardian()) async throws -> RecordingFile {
        guard guardian.accepts(picked) else { throw RecordingReadProblem.unreadable }
        let name = picked.lastPathComponent
        if guardian.isOurCopy(picked) {
            return RecordingFile(url: picked, displayName: name, cleanups: [{ try? FileManager.default.removeItem(at: picked) }])
        }
        let scoped = picked.startAccessingSecurityScopedResource()
        let release: () -> Void = { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let size = (try? picked.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init)
        if RecordingCopyRoom.fits(fileBytes: size, freeBytes: DeviceStorage.availableBytes()), let copy = await copy(picked) {
            release()
            return RecordingFile(url: copy, displayName: name, cleanups: [{ try? FileManager.default.removeItem(at: copy.deletingLastPathComponent()) }])
        }
        return RecordingFile(url: picked, displayName: name, cleanups: [release])
    }

    static func writeShare(_ text: String, name: String, fileExtension: String) -> URL? {
        let folder = copiesFolder.appendingPathComponent("shared-\(UUID().uuidString)", isDirectory: true)
        let url = folder.appendingPathComponent("\(SharedFileName.make(from: name)).\(fileExtension)")
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private static func copy(_ url: URL) async -> URL? {
        await Task.detached(priority: .userInitiated) { () -> URL? in
            let folder = copiesFolder.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let base = SharedFileName.make(from: url.deletingPathExtension().lastPathComponent)
            let ext = url.pathExtension.lowercased()
            let destination = folder.appendingPathComponent(ext.isEmpty ? base : "\(base).\(ext)")
            guard (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil else { return nil }
            var coordinationError: NSError?
            var copied = false
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
                copied = (try? FileManager.default.copyItem(at: readable, to: destination)) != nil
            }
            if coordinationError == nil, copied { return destination }
            try? FileManager.default.removeItem(at: folder)
            return nil
        }.value
    }
}
