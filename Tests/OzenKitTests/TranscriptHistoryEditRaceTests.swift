import Testing
@testable import OzenKit
import Foundation

@Suite("Transcript history edits and autosave take turns")
struct TranscriptHistoryEditRaceTests {
    private func makeTempDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("ozen-history-race-\(UUID())")
    }

    private func record(id: UUID, texts: [String]) -> TranscriptSessionRecord {
        TranscriptSessionRecord(
            id: id,
            startedAt: 100,
            endedAt: nil,
            engine: .whisperKit,
            modelVariant: "small",
            inputName: nil,
            segments: texts.map {
                SavedSegment(id: UUID(), text: $0, speakerName: nil, speakerClusterID: nil, startTimestamp: 100, isCommitted: true)
            }
        )
    }

    private func raw(_ dir: URL, _ id: UUID) throws -> [String: Any] {
        let data = try Data(contentsOf: dir.appendingPathComponent("\(id.uuidString).json"))
        return try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// Runs `work` on another thread and waits a short, bounded time for it.
    private func startedElsewhere(_ work: @escaping @Sendable () -> Void) -> DispatchGroup {
        let group = DispatchGroup()
        DispatchQueue.global().async(group: group) { work() }
        _ = group.wait(timeout: .now() + 0.3)
        return group
    }

    @Test("a rename does not lose a line the autosave adds meanwhile")
    func renameKeepsAutosavedLine() throws {
        let dir = makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        var editing = TranscriptHistoryStore(directoryURL: dir)
        let autosaving = TranscriptHistoryStore(directoryURL: dir)
        try editing.save(record(id: id, texts: ["one", "two"]))
        let longer = record(id: id, texts: ["one", "two", "three"])
        let pending = PendingSave()
        editing.betweenPatchReadAndWrite = {
            pending.group = startedElsewhere { _ = try? autosaving.save(longer) }
        }
        try editing.rename(id: id, title: "Visit")
        pending.group?.wait()
        let object = try raw(dir, id)
        #expect((object["segments"] as? [Any])?.count == 3)
        #expect(object["title"] as? String == "Visit")
    }

    @Test("a star does not lose a line the autosave adds meanwhile")
    func starKeepsAutosavedLine() throws {
        let dir = makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        var editing = TranscriptHistoryStore(directoryURL: dir)
        let autosaving = TranscriptHistoryStore(directoryURL: dir)
        let first = record(id: id, texts: ["one", "two"])
        try editing.save(first)
        let longer = TranscriptSessionRecord(
            id: id, startedAt: 100, endedAt: nil, engine: .whisperKit, modelVariant: "small", inputName: nil,
            segments: first.segments + [SavedSegment(id: UUID(), text: "three", speakerName: nil, speakerClusterID: nil, startTimestamp: 101, isCommitted: true)]
        )
        let pending = PendingSave()
        editing.betweenPatchReadAndWrite = {
            pending.group = startedElsewhere { _ = try? autosaving.save(longer) }
        }
        let state = try editing.toggleStar(segmentID: first.segments[0].id, inSession: id)
        pending.group?.wait()
        #expect(state == true)
        #expect((try raw(dir, id)["segments"] as? [Any])?.count == 3)
    }

    @Test("an edit does not bring back a conversation deleted meanwhile")
    func editDoesNotResurrectDeleted() throws {
        let dir = makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        var editing = TranscriptHistoryStore(directoryURL: dir)
        let deleting = TranscriptHistoryStore(directoryURL: dir)
        try editing.save(record(id: id, texts: ["one", "two"]))
        let pending = PendingSave()
        editing.betweenPatchReadAndWrite = {
            pending.group = startedElsewhere { try? deleting.delete(id: id) }
        }
        try editing.rename(id: id, title: "Visit")
        pending.group?.wait()
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent("\(id.uuidString).json").path))
    }

    @Test("an edit of a deleted conversation changes nothing")
    func editAfterDeleteLeavesNoFile() throws {
        let dir = makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        let store = TranscriptHistoryStore(directoryURL: dir)
        let saved = record(id: id, texts: ["one", "two"])
        try store.save(saved)
        try store.delete(id: id)
        try store.rename(id: id, title: "Visit")
        #expect(try store.toggleStar(segmentID: saved.segments[0].id, inSession: id) == nil)
        try store.replaceText(segmentID: saved.segments[0].id, inSession: id, with: "x")
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent("\(id.uuidString).json").path))
    }

    @Test("a rename to a same-length title, with the file's date and size unchanged, is not written back over")
    func renameSurvivesSameSizeSameDate() throws {
        let dir = makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        let file = dir.appendingPathComponent("\(id.uuidString).json")
        let autosaving = TranscriptHistoryStore(directoryURL: dir)
        let editing = TranscriptHistoryStore(directoryURL: dir)
        let saved = record(id: id, texts: ["one", "two"])
        try autosaving.save(saved)
        try editing.rename(id: id, title: "Alpha")
        try autosaving.save(saved)
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let seenModified = try #require(attributes[.modificationDate] as? Date)
        let seenSize = try #require(attributes[.size] as? NSNumber)
        try editing.rename(id: id, title: "Bravo")
        try FileManager.default.setAttributes([.modificationDate: seenModified], ofItemAtPath: file.path)
        let after = try FileManager.default.attributesOfItem(atPath: file.path)
        #expect((after[.size] as? NSNumber) == seenSize)
        try autosaving.save(saved)
        #expect(try raw(dir, id)["title"] as? String == "Bravo")
    }
}

private final class PendingSave: @unchecked Sendable {
    var group: DispatchGroup?
}
