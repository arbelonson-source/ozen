import CoreTransferable
import Foundation
import UniformTypeIdentifiers
import OzenKit

struct AllConversationsExport: Transferable, Sendable {
    let store: TranscriptHistoryStore
    let marksUncertain: Bool

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .plainText) { export in
            SentTransferredFile(try export.writeFile())
        }
    }

    func writeFile(now: Date = Date(), in directory: URL = FileManager.default.temporaryDirectory) throws -> URL {
        let folder = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = TranscriptHistoryStore.exportAllFileName(
            now: now.timeIntervalSince1970,
            utcOffsetSeconds: TimeZone.current.secondsFromGMT(for: now)
        )
        let url = folder.appendingPathComponent(name)
        let wrote = try store.writeExportAll(
            to: url,
            utcOffsetAt: { TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: $0)) },
            marksUncertain: marksUncertain
        )
        guard wrote else {
            try? FileManager.default.removeItem(at: folder)
            throw CocoaError(.fileNoSuchFile)
        }
        return url
    }
}
