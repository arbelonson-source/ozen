import Foundation
import Testing
@testable import Ozen
import OzenKit

@Suite("Sharing all conversations")
struct AllConversationsExportTests {
    private func saved(_ store: TranscriptHistoryStore, startedAt: TimeInterval, text: String) throws -> TranscriptSessionRecord {
        let record = TranscriptSessionRecord(
            startedAt: startedAt,
            endedAt: startedAt + 10,
            engine: .whisperKit,
            modelVariant: "small",
            inputName: nil,
            segments: [SavedSegment(id: UUID(), text: text, speakerName: nil, speakerClusterID: nil, startTimestamp: startedAt, isCommitted: true)]
        )
        _ = try store.save(record)
        return record
    }

    @Test("the file holds every saved conversation oldest first, is named by the date, and is plain UTF-8")
    func writesOneTextFile() throws {
        let top = FileManager.default.temporaryDirectory.appendingPathComponent("share-all-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: top) }
        let store = TranscriptHistoryStore(directoryURL: top.appendingPathComponent("history"))
        let newer = try saved(store, startedAt: 2_000_000, text: "second talk")
        let older = try saved(store, startedAt: 1_000_000, text: "first talk")

        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let url = try AllConversationsExport(store: store, marksUncertain: false)
            .writeFile(now: now, in: top.appendingPathComponent("out"))

        let offset: (TimeInterval) -> Int = { TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: $0)) }
        let expected = try #require(TranscriptHistoryStore.exportAllText([older, newer], utcOffsetAt: offset, marksUncertain: false))
        let bytes = try Data(contentsOf: url)
        #expect(String(decoding: bytes, as: UTF8.self) == expected)
        #expect(bytes.prefix(3) != Data([0xEF, 0xBB, 0xBF]))
        #expect(url.lastPathComponent == TranscriptHistoryStore.exportAllFileName(now: 1_700_000_000, utcOffsetSeconds: TimeZone.current.secondsFromGMT(for: now)))
        #expect(url.pathExtension == "txt")
    }

    @Test("with nothing saved there is no file to share")
    func nothingSavedWritesNothing() {
        let top = FileManager.default.temporaryDirectory.appendingPathComponent("share-none-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: top) }
        let store = TranscriptHistoryStore(directoryURL: top.appendingPathComponent("history"))
        #expect(throws: (any Error).self) {
            try AllConversationsExport(store: store, marksUncertain: false).writeFile(in: top)
        }
    }
}
