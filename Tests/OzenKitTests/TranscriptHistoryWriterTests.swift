import Dispatch
import Foundation
import Testing
@testable import OzenKit

/// A call made on a thread of its own, checked on without holding one of
/// the few threads every test shares: a semaphore wait held one for as long
/// as it waited, while all the other tests waited for a turn.
final class OffThreadCall: @unchecked Sendable {
    private let lock = NSLock()
    private var started = false
    private var returned = false

    /// Runs `work` on a thread of its own, so a call that wrongly blocks
    /// shows up as a failed expectation instead of hanging the whole test
    /// run. Not the shared global queue: on a one-core runner its threads
    /// were all taken by other tests, and the work did not even start
    /// within a second.
    static func start(_ work: @escaping @Sendable () -> Void) -> OffThreadCall {
        let call = OffThreadCall()
        Thread {
            call.markStarted()
            work()
            call.markReturned()
        }.start()
        return call
    }

    func markStarted() { lock.withLock { started = true } }

    func markReturned() { lock.withLock { returned = true } }

    /// Whether the call returned within `limit` of its start, seen within
    /// milliseconds of its return. Counted from when the thread began the
    /// call: a busy machine can start a thread late, and a call that has
    /// not begun has not been held up.
    func hasReturned(within limit: Duration) async -> Bool {
        guard await holds(within: .seconds(60), { $0.started }) else { return false }
        return await holds(within: limit) { $0.returned }
    }

    private func holds(within limit: Duration, _ condition: (OffThreadCall) -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + limit
        while !lock.withLock({ condition(self) }) {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return true
    }

    /// Waits for a call the test has let go on.
    func finish() async {
        _ = await hasReturned(within: .seconds(60))
    }
}

@Suite("Transcript history writer")
struct TranscriptHistoryWriterTests {
    private func record(id: UUID, lines: Int, ended: Bool) -> TranscriptSessionRecord {
        TranscriptSessionRecord(
            id: id,
            startedAt: 100,
            endedAt: ended ? 200 : nil,
            engine: .whisperKit,
            modelVariant: nil,
            inputName: nil,
            segments: (0..<lines).map {
                SavedSegment(id: UUID(), text: "שורה \($0)", speakerName: nil, speakerClusterID: nil, startTimestamp: 100, isCommitted: true)
            }
        )
    }

    private func offThread(_ work: @escaping @Sendable () -> Void) -> OffThreadCall {
        OffThreadCall.start(work)
    }

    private func makeStore() -> (TranscriptHistoryStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-writer-\(UUID())", isDirectory: true)
        return (TranscriptHistoryStore(directoryURL: dir), dir)
    }

    @Test("an autosave returns without waiting for the disk")
    func autosaveDoesNotBlock() async {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)

        queue.suspend()
        let returned = offThread { writer.saveInBackground(self.record(id: UUID(), lines: 1, ended: false)) }
        let returnedWhilePaused = await returned.hasReturned(within: .seconds(30))
        #expect(returnedWhilePaused)
        #expect(store.listSummaries().isEmpty)

        queue.resume()
        await returned.finish()
        writer.waitUntilIdle()
        #expect(store.listSummaries().count == 1)
    }

    @Test("an autosave says when it is done, by which time a failure is already known")
    func autosaveReportsWhenDone() async throws {
        // A plain file where the history folder should be: the save fails.
        let blocker = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-writer-blocked-\(UUID())")
        try Data("x".utf8).write(to: blocker)
        defer { try? FileManager.default.removeItem(at: blocker) }
        let writer = TranscriptHistoryWriter(store: TranscriptHistoryStore(directoryURL: blocker.appendingPathComponent("history", isDirectory: true)))

        let failureWhenDone: String? = await withCheckedContinuation { continuation in
            writer.saveInBackground(record(id: UUID(), lines: 1, ended: false)) {
                continuation.resume(returning: writer.lastFailure)
            }
        }
        #expect(failureWhenDone != nil)
    }

    @Test("a rename waits for autosaves already queued, so the name ends up on the newest copy")
    func renameQueuesBehindAutosave() async {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)
        let id = UUID()
        writer.saveNow(record(id: id, lines: 1, ended: false))

        queue.suspend()
        let autosaved = offThread { writer.saveInBackground(self.record(id: id, lines: 4, ended: false)) }
        #expect(await autosaved.hasReturned(within: .seconds(30)))
        let renamed = offThread { writer.renameNow(id: id, title: "ארוחת ערב") }
        let renameReturnedEarly = await renamed.hasReturned(within: .milliseconds(200))
        queue.resume()
        await autosaved.finish()
        await renamed.finish()
        writer.waitUntilIdle()

        #expect(renameReturnedEarly == false)
        #expect(store.load(id: id)?.title == "ארוחת ערב")
        #expect(store.load(id: id)?.segments.count == 4)
    }

    @Test("a save made now lands after an autosave that was still waiting, never under it")
    func saveNowWinsOverPendingAutosave() async {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)
        let id = UUID()

        queue.suspend()
        let autosaved = offThread { writer.saveInBackground(self.record(id: id, lines: 1, ended: false)) }
        #expect(await autosaved.hasReturned(within: .seconds(30)))
        let saved = offThread { writer.saveNow(self.record(id: id, lines: 3, ended: true)) }
        // A correct writer is stuck behind the paused autosave here; one
        // that skips the queue has already written and returned.
        let saveReturnedEarly = await saved.hasReturned(within: .milliseconds(200))
        queue.resume()
        await autosaved.finish()
        await saved.finish()
        writer.waitUntilIdle()

        #expect(saveReturnedEarly == false)
        let summary = store.listSummaries().first
        #expect(summary?.segmentCount == 3)
        #expect(summary?.endedAt == 200)
    }

    private func summaryFile(_ dir: URL, _ id: UUID) -> URL {
        dir.appendingPathComponent(TranscriptHistoryStore.summariesFolderName).appendingPathComponent("\(id.uuidString).json")
    }

    @Test("a save made now skips the summary and search-text files an autosave would have written")
    func saveNowSkipsSearchCaches() {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store)

        let now = record(id: UUID(), lines: 1, ended: true)
        writer.saveNow(now)
        #expect(!FileManager.default.fileExists(atPath: summaryFile(dir, now.id).path))
        // The conversation itself is still there and lists correctly --
        // only the cache files are skipped.
        #expect(store.listSummaries().map(\.id) == [now.id])

        let autosaved = record(id: UUID(), lines: 1, ended: false)
        writer.saveInBackground(autosaved)
        writer.waitUntilIdle()
        #expect(FileManager.default.fileExists(atPath: summaryFile(dir, autosaved.id).path))
    }

    @Test("a delete waits for an autosave of the same conversation, so the autosave can't bring it back")
    func deleteQueuesBehindAutosave() async throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)
        let id = UUID()
        writer.saveNow(record(id: id, lines: 1, ended: false))

        queue.suspend()
        let autosaved = offThread { writer.saveInBackground(self.record(id: id, lines: 2, ended: false)) }
        #expect(await autosaved.hasReturned(within: .seconds(30)))
        let deleted = offThread { try? writer.deleteNow(id: id) }
        let deleteReturnedEarly = await deleted.hasReturned(within: .milliseconds(200))
        queue.resume()
        await autosaved.finish()
        await deleted.finish()
        writer.waitUntilIdle()

        #expect(deleteReturnedEarly == false)
        #expect(store.load(id: id) == nil)
        #expect(store.listSummaries().isEmpty)
    }

    @Test("delete all waits for queued autosaves too")
    func deleteAllQueuesBehindAutosave() async throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)
        writer.saveNow(record(id: UUID(), lines: 1, ended: true))

        queue.suspend()
        let autosaved = offThread { writer.saveInBackground(self.record(id: UUID(), lines: 2, ended: false)) }
        #expect(await autosaved.hasReturned(within: .seconds(30)))
        let deleted = offThread { try? writer.deleteAllNow() }
        let deleteReturnedEarly = await deleted.hasReturned(within: .milliseconds(200))
        queue.resume()
        await autosaved.finish()
        await deleted.finish()
        writer.waitUntilIdle()

        #expect(deleteReturnedEarly == false)
        #expect(store.listSummaries().isEmpty)
    }

    private func removeRecordFiles(in dir: URL) throws {
        for name in try FileManager.default.contentsOfDirectory(atPath: dir.path) where name.hasSuffix(".json") {
            try FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
    }

    @Test("an autosave with nothing new since the last write leaves the disk alone; a new line is written")
    func unchangedAutosaveIsSkipped() throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store)
        let id = UUID()
        let quiet = record(id: id, lines: 2, ended: false)
        writer.saveInBackground(quiet)
        writer.waitUntilIdle()
        #expect(store.load(id: id) == quiet)

        // Removed behind the writer's back: only a write brings it back.
        try removeRecordFiles(in: dir)
        writer.saveInBackground(quiet)
        writer.waitUntilIdle()
        #expect(store.load(id: id) == nil)

        var grown = quiet
        grown.segments.append(SavedSegment(id: UUID(), text: "עוד שורה", speakerName: nil, speakerClusterID: nil, startTimestamp: 150, isCommitted: true))
        writer.saveInBackground(grown)
        writer.waitUntilIdle()
        #expect(store.load(id: id) == grown)
    }

    @Test("after a failed write, a delete or a rename, the same conversation is written again")
    func unchangedAutosaveWrittenAfterTrouble() throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store)
        let conversation = record(id: UUID(), lines: 1, ended: false)

        writer.saveNow(conversation)
        try FileManager.default.removeItem(at: dir)
        try Data("not a folder".utf8).write(to: dir)
        writer.saveNow(conversation)
        #expect(writer.lastFailure != nil)
        try FileManager.default.removeItem(at: dir)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        #expect(store.load(id: conversation.id) == conversation)
        #expect(writer.lastFailure == nil)

        try writer.deleteNow(id: conversation.id)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        #expect(store.load(id: conversation.id) == conversation)

        writer.renameNow(id: conversation.id, title: "ביקור")
        try removeRecordFiles(in: dir)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        #expect(store.load(id: conversation.id) != nil)
    }

    @Test("a line starred after the conversation ended is saved, counted, and keeps the conversation from being cleared out")
    func starAfterwards() throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.star"))
        let conversation = record(id: UUID(), lines: 3, ended: true)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        let line = conversation.segments[1].id

        #expect(writer.toggleStarNow(sessionID: conversation.id, segmentID: line) == true)
        #expect(store.load(id: conversation.id)?.segments.map(\.isStarred) == [false, true, false])
        let summary = try #require(store.listSummaries().first { $0.id == conversation.id })
        #expect(summary.starredCount == 1)
        #expect(summary.isKeptByChoice)

        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        #expect(store.load(id: conversation.id)?.segments.map(\.isStarred) == [false, false, false])
        #expect(writer.toggleStarNow(sessionID: conversation.id, segmentID: line) == true)
        #expect(writer.toggleStarNow(sessionID: conversation.id, segmentID: line) == false)
        #expect(try #require(store.listSummaries().first { $0.id == conversation.id }).starredCount == 0)
        #expect(writer.toggleStarNow(sessionID: conversation.id, segmentID: UUID()) == nil)
        #expect(writer.toggleStarNow(sessionID: UUID(), segmentID: line) == nil)
    }

    @Test("a save that can't reach the disk is reported, and the next one that does clears it")
    func saveFailureIsReported() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-writer-fail-\(UUID())", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: base) }
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        // A file where the history folder should be: every save fails, the
        // way it would on a phone with no room left.
        let blocked = base.appendingPathComponent("history")
        try Data("not a folder".utf8).write(to: blocked)
        let failing = TranscriptHistoryWriter(store: TranscriptHistoryStore(directoryURL: blocked), queue: DispatchQueue(label: "test.fail"))

        #expect(failing.lastFailure == nil)
        failing.saveInBackground(record(id: UUID(), lines: 1, ended: false))
        failing.waitUntilIdle()
        #expect(failing.lastFailure != nil)

        // The folder becomes usable again (room was freed): the next save
        // works and the problem is no longer reported.
        try FileManager.default.removeItem(at: blocked)
        failing.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(failing.lastFailure == nil)
    }

    @Test("a voice rename the disk refused is finished by the next save once there is room, and is reported until then")
    func refusedRenameCatchesUp() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.rename"))
        let old = TranscriptSessionRecord(
            id: UUID(), startedAt: 100, endedAt: 200, engine: .whisperKit, modelVariant: nil, inputName: nil,
            segments: [SavedSegment(id: UUID(), text: "שלום", speakerName: "Avi", speakerClusterID: nil, startTimestamp: 100, isCommitted: true)]
        )
        writer.saveNow(old)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.renameSpeakerInBackground(from: "Avi", to: "Aviv")
        writer.waitUntilIdle()
        #expect(writer.lastFailure != nil)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: old.id)?.segments.first?.speakerName == "Aviv")
        #expect(writer.lastFailure == nil)
    }

    @Test("a conversation the disk refused is written by the next save once there is room, and is reported until then")
    func refusedConversationCatchesUp() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused"))
        let evening = record(id: UUID(), lines: 3, ended: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(evening)
        #expect(writer.lastFailure != nil)
        writer.saveInBackground(record(id: UUID(), lines: 1, ended: false))
        writer.waitUntilIdle()
        #expect(writer.lastFailure != nil)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.count == 3)
        #expect(writer.lastFailure == nil)
    }

    @Test("a refused conversation waiting for room takes a voice rename made meanwhile")
    func refusedConversationTakesRename() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-rename"))
        let evening = TranscriptSessionRecord(
            id: UUID(), startedAt: 100, endedAt: 200, engine: .whisperKit, modelVariant: nil, inputName: nil,
            segments: [SavedSegment(id: UUID(), text: "שלום", speakerName: "Avi", speakerClusterID: nil, startTimestamp: 100, isCommitted: true)]
        )
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(evening)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.renameSpeakerInBackground(from: "Avi", to: "Aviv")
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.speakerName == "Aviv")
    }

    @Test("a refused conversation deleted before there is room is not written back later")
    func refusedConversationDeletedStaysDeleted() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-deleted"))
        let evening = record(id: UUID(), lines: 3, ended: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(evening)
        try? writer.deleteNow(id: evening.id)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id) == nil)
        #expect(writer.lastFailure == nil)
    }

    @Test("a star put on a conversation still waiting for room is kept when the waiting version is written")
    func starOnRefusedConversationKept() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-star"))
        let early = record(id: UUID(), lines: 2, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments += record(id: early.id, lines: 2, ended: true).segments
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        let starred = writer.toggleStarNow(sessionID: early.id, segmentID: early.segments[0].id)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))

        #expect(starred == true)
        let saved = try #require(store.load(id: early.id))
        #expect(saved.segments.count == 4)
        #expect(saved.segments[0].isStarred)
    }

    @Test("a star tapped in History follows the conversation as History shows it, the version waiting for room, and the file takes the same state")
    func starFollowsHistoryWhileWaiting() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-unstar"))
        let early = record(id: UUID(), lines: 2, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments[0].isStarred = true
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        #expect(try writer.loadNow(id: early.id)?.segments[0].isStarred == true)
        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: early.segments[0].id) == false)
        #expect(store.load(id: early.id)?.segments[0].isStarred == false)
        #expect(store.load(id: early.id)?.endedAt == 200)
        #expect(writer.lastFailure == nil)
    }

    // The starred lines list shows the files' stars. Its Remove the star,
    // as a tap following the waiting version (no star), put the star back.
    @Test("Remove the star from the starred lines list takes the star off, though the version waiting for room has none")
    func unstarFromStarredLinesWhileWaiting() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.unstar-from-list"))
        var early = record(id: UUID(), lines: 2, ended: false)
        early.segments[0].isStarred = true
        writer.saveNow(early)
        var later = early
        later.segments[0].isStarred = false
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: early.segments[0].id, keepingRefused: false, to: false) == false)
        #expect(store.load(id: early.id)?.segments[0].isStarred == false)
        #expect(store.load(id: early.id)?.endedAt == 200)
        #expect(try writer.loadNow(id: early.id)?.segments[0].isStarred == false)
        #expect(writer.lastFailure == nil)
    }

    @Test("a star History can't save stays out of the waiting version, while one from the caption screen waits with it")
    func refusedStarLeavesTheWaitingVersion() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-star-waiting"))
        let early = record(id: UUID(), lines: 2, ended: false)
        writer.saveNow(early)
        var later = early
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)

        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: early.segments[0].id, keepingRefused: false) == nil)
        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: early.segments[1].id) == true)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: early.id)?.endedAt == 200)
        #expect(store.load(id: early.id)?.segments.map(\.isStarred) == [false, true], "the caption screen's star waits with the version waiting for room")
    }

    @Test("deleting everything also drops a conversation still waiting for room, and its warning")
    func deleteAllDropsTheWaiting() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.delete-all-waiting"))
        let evening = record(id: UUID(), lines: 3, ended: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(evening)
        #expect(writer.lastFailure != nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        try writer.deleteAllNow()
        #expect(writer.lastFailure == nil)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id) == nil)
    }

    @Test("a star on a line only the waiting version has is put on that line")
    func starOnLineOnlyWaiting() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-new-line-star"))
        let early = record(id: UUID(), lines: 2, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments += record(id: early.id, lines: 2, ended: true).segments
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        let starred = writer.toggleStarNow(sessionID: early.id, segmentID: later.segments[2].id)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))

        #expect(starred == true)
        let saved = try #require(store.load(id: early.id))
        #expect(saved.segments.map(\.isStarred) == [false, false, true, false])
    }

    @Test("a name given to a conversation the disk refused before it was ever written is kept when it is written")
    func renameRefusedConversation() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-title"))
        let evening = record(id: UUID(), lines: 2, ended: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(evening)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        writer.renameNow(id: evening.id, title: "ארוחת ערב")
        #expect(store.load(id: evening.id)?.title == "ארוחת ערב")
        #expect(writer.lastFailure == nil)
    }

    @Test("a name the disk refuses says so, unless a version waiting for room keeps it")
    func refusedNameSaysSo() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-name"))
        let morning = record(id: UUID(), lines: 2, ended: true)
        let evening = record(id: UUID(), lines: 2, ended: false)
        writer.saveNow(morning)
        writer.saveNow(evening)
        var later = evening
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)

        #expect(!writer.renameNow(id: morning.id, title: "בוקר"))
        #expect(writer.renameNow(id: evening.id, title: "ערב"))
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(writer.renameNow(id: UUID(), title: "לילה"))
        #expect(store.load(id: morning.id)?.title == nil)
        #expect(store.load(id: evening.id)?.title == "ערב")
    }

    // Taken for gone, a file that couldn't be read just now was named with
    // nothing written, and History said the name was saved. Its outcome,
    // none at all, also took the place of a save that had really failed,
    // and the warning went while the phone was still full.
    @Test("a name given in History while its conversation can't be read just now is not saved, and is not a save that failed")
    func nameOfAConversationThatCantBeRead() throws {
        let (store, dir) = makeStore()
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.rename-unreadable"))
        let evening = record(id: UUID(), lines: 1, ended: true)
        let other = record(id: UUID(), lines: 1, ended: true)
        writer.saveNow(evening)
        writer.saveNow(other)
        let file = dir.appendingPathComponent("\(evening.id.uuidString).json")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            try? FileManager.default.removeItem(at: dir)
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)
        #expect(!writer.renameNow(id: evening.id, title: "Dinner"), "History says the name wasn't saved")
        #expect(writer.lastFailure == nil, "nothing failed to save")

        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(!writer.renameNow(id: other.id, title: "Dinner"))
        let full = try #require(writer.lastFailure, "a name the disk refuses is still reported")
        #expect(!writer.renameNow(id: evening.id, title: "Dinner"))
        #expect(writer.lastFailure == full, "the phone is still full")

        writer.saveNow(record(id: UUID(), lines: 1, ended: true))
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(!writer.renameNow(id: evening.id, title: "Dinner"))
        #expect(writer.lastFailure == nil, "what waited is written, so there is room")

        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        #expect(writer.renameNow(id: evening.id, title: "Dinner"))
        #expect(store.load(id: evening.id)?.title == "Dinner")
        #expect(writer.lastFailure == nil)
    }

    @Test("deleting the one conversation the disk refused takes the saving warning away with it")
    func deletingRefusedConversationClearsWarning() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-deleted"))
        let refused = record(id: UUID(), lines: 2, ended: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(refused)
        #expect(writer.lastFailure != nil)

        try writer.deleteNow(id: refused.id)
        #expect(writer.lastFailure == nil)
    }

    @Test("a rename that works does not clear the warning while a refused conversation still waits")
    func warningStaysWhileRefusedWaits() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-warning"))
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 3, ended: true))
        #expect(writer.lastFailure != nil)

        writer.renameNow(id: UUID(), title: "ארוחת ערב")
        #expect(writer.lastFailure != nil)
        writer.renameSpeakerInBackground(from: "Avi", to: "Aviv")
        writer.waitUntilIdle()
        #expect(writer.lastFailure != nil)
    }

    @Test("a word fixed in History is fixed in the words the saved line has now, and one the line no longer has writes nothing")
    func wordFixReadsTheSavedLineAsItIsNow() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix"))
        var evening = record(id: UUID(), lines: 1, ended: true)
        evening.segments[0].text = "I saw aval today"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        writer.replaceTextNow(sessionID: evening.id, segmentID: line, text: "I saw aval at the market")

        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .saved)
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi at the market")
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avital")) == .lineChanged)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: UUID(), with: WordFix(heard: "Avi", written: "Avital")) == .lineChanged)
        #expect(writer.fixTextNow(sessionID: UUID(), segmentID: line, with: WordFix(heard: "Avi", written: "Avital")) == .lineChanged)
        #expect(writer.lastFailure == nil, "nothing was written, so the read-only folder refused nothing")
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi at the market")
    }

    // The file was read as no conversation at all, and History said the
    // line changed or was gone, though the line was fine and the same fix
    // went through once the file could be read.
    @Test("a word fixed in History while its conversation can't be read just now says so and writes nothing, and goes through once it can be read")
    func wordFixOfAConversationThatCantBeReadSaysSo() throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-unreadable"))
        var evening = record(id: UUID(), lines: 1, ended: true)
        evening.segments[0].text = "I saw aval today"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        let file = dir.appendingPathComponent("\(evening.id.uuidString).json")
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path) }

        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .unreadable)
        #expect(writer.fixTextNow(sessionID: UUID(), segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .lineChanged, "no file at all is a conversation that is gone")
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw aval today")
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .saved)
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi today")
        #expect(writer.lastFailure == nil)
    }

    @Test("a word fixed in History while the line's newer text waits for room fixes that text, not the older file")
    func wordFixReadsTheWaitingText() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-waiting-text"))
        var evening = record(id: UUID(), lines: 1, ended: true)
        evening.segments[0].text = "I saw aval today"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: evening.id, segmentID: line, text: "I saw Dan today")
        #expect(writer.lastFailure != nil)

        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .lineChanged)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "Dan", written: "Dana")) == .saved)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Dana today")
        #expect(writer.lastFailure == nil)
    }

    @Test("a word fixed in History in a conversation still waiting for room is fixed in the waiting version, not the older file")
    func wordFixReadsTheWaitingConversation() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-waiting-conversation"))
        let early = record(id: UUID(), lines: 1, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments[0].text = "I saw aval today"
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        #expect(writer.fixTextNow(sessionID: early.id, segmentID: early.segments[0].id, with: WordFix(heard: "aval", written: "Avi")) == .saved)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: early.id)?.segments.first?.text == "I saw Avi today")
        #expect(writer.lastFailure == nil)
    }

    @Test("a word fixed in History on a line whose conversation was saved again after its text queued for room fixes the newer words")
    func wordFixReadsTheConversationSavedAfterTheWaitingText() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-newer-save"))
        var evening = record(id: UUID(), lines: 1, ended: false)
        evening.segments[0].text = "I saw aval today"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: evening.id, segmentID: line, text: "I saw Dan today")
        var later = evening
        later.segments[0].text = "I saw Dan at the market"
        later.endedAt = 200
        writer.saveNow(later)

        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "Dan", written: "Dana")) == .saved)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Dana at the market")
        #expect(writer.lastFailure == nil)
    }

    @Test("a line's text waiting for room does not replace the newer words of a later save of its conversation")
    func waitingTextGivesWayToALaterSave() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.waiting-text-newer-save"))
        var evening = record(id: UUID(), lines: 1, ended: false)
        evening.segments[0].text = "I saw aval today"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: evening.id, segmentID: line, text: "I saw Avi today")
        var later = evening
        later.segments[0].text = "I saw Avi at the market"
        later.endedAt = 200
        writer.saveNow(later)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi at the market")
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: evening.id, segmentID: line, text: "I saw Dan today")
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveInBackground(later)
        writer.waitUntilIdle()
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi at the market", "an autosave that reached the disk is newer too")
        #expect(writer.lastFailure == nil)
    }

    @Test("a second word fixed in History once there is room again is not undone by the first fix still waiting")
    func wordFixReplacesTheTextWaitingForRoom() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-replaces-waiting"))
        var evening = record(id: UUID(), lines: 1, ended: true)
        evening.segments[0].text = "I saw aval and Dan"
        writer.saveNow(evening)
        let line = evening.segments[0].id
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "aval", written: "Avi")) == .saved)
        #expect(writer.lastFailure != nil)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: line, with: WordFix(heard: "Dan", written: "Dana")) == .saved)
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi and Dana")
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.first?.text == "I saw Avi and Dana")
        #expect(writer.lastFailure == nil)
    }

    // History's dialog says a fix that waits for room is done. Read from the
    // file, History kept showing the old words, and the person fixed the
    // same word again.
    @Test("History shows a word fixed while the phone is full with the fix, not the older file")
    func historyShowsTheTextWaitingForRoom() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.load-waiting-text"))
        var evening = record(id: UUID(), lines: 2, ended: true)
        evening.segments[0].text = "I saw Cohen today"
        writer.saveNow(evening)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(writer.fixTextNow(sessionID: evening.id, segmentID: evening.segments[0].id, with: WordFix(heard: "Cohen", written: "Avi Cohen")) == .saved)
        #expect(writer.lastFailure != nil)

        #expect(try writer.loadNow(id: evening.id)?.segments.map(\.text) == ["I saw Avi Cohen today", "שורה 1"])
        #expect(try writer.loadNow(id: UUID()) == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: evening.id)?.segments.map(\.text) == ["I saw Avi Cohen today", "שורה 1"])
        #expect(try writer.loadNow(id: evening.id) == store.load(id: evening.id))
    }

    @Test("History shows the newer version of a conversation still waiting for room, with a word fixed in it")
    func historyShowsTheConversationWaitingForRoom() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.load-waiting-conversation"))
        let early = record(id: UUID(), lines: 1, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments[0].text = "I saw aval today"
        later.segments += record(id: early.id, lines: 1, ended: true).segments
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        #expect(writer.fixTextNow(sessionID: early.id, segmentID: early.segments[0].id, with: WordFix(heard: "aval", written: "Avi")) == .saved)

        let shown = try #require(try writer.loadNow(id: early.id))
        #expect(shown.endedAt == 200)
        #expect(shown.segments.map(\.text) == ["I saw Avi today", "שורה 0"])
    }

    // Read as no conversation, the conversation open in History turned into
    // "Conversation not found" right after the fix sheet said to try again
    // in a moment, and there was nothing left to try again from.
    @Test("History reads a conversation whose file can't be read just now as unreadable, not as gone, and still shows its version waiting for room")
    func historyReadOfAConversationThatCantBeRead() throws {
        let (store, dir) = makeStore()
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.load-unreadable"))
        let early = record(id: UUID(), lines: 1, ended: false)
        writer.saveNow(early)
        let file = dir.appendingPathComponent("\(early.id.uuidString).json")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            try? FileManager.default.removeItem(at: dir)
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)

        #expect(throws: (any Error).self) { try writer.loadNow(id: early.id) }
        #expect(try writer.loadNow(id: UUID()) == nil, "no file at all is a conversation that is gone")

        var later = early
        later.segments += record(id: early.id, lines: 1, ended: true).segments
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        #expect(writer.lastFailure != nil)
        #expect(try writer.loadNow(id: early.id)?.segments == later.segments, "the version waiting for room is shown as before")

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(try writer.loadNow(id: early.id)?.segments == later.segments)
        try writer.deleteNow(id: early.id)
        #expect(try writer.loadNow(id: early.id) == nil, "a deleted conversation is gone")
    }

    // Room came back before the refused fix: like every other fix, it
    // writes what was waiting, so the newer version isn't left only in
    // memory until the next save.
    @Test("a refused History fix still writes the conversation waiting for room once there is room")
    func refusedWordFixCatchesUp() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-fix-catches-up"))
        var early = record(id: UUID(), lines: 2, ended: false)
        early.segments[1].text = "I saw aval today"
        writer.saveNow(early)
        let gone = early.segments[1].id
        var later = early
        later.segments[1] = record(id: early.id, lines: 1, ended: true).segments[0]
        later.segments[1].text = "I saw Avi today"
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        #expect(writer.fixTextNow(sessionID: early.id, segmentID: gone, with: WordFix(heard: "aval", written: "Avital")) == .lineChanged)
        #expect(store.load(id: early.id)?.segments.map(\.text) == ["שורה 0", "I saw Avi today"])
        #expect(writer.lastFailure == nil, "nothing is left waiting, so the warning goes")
    }

    // A replay took the line off the screen before the conversation closed,
    // so the version waiting for room no longer has it. The fix was written
    // to the file and called saved, then the waiting version was written
    // over it without the line.
    @Test("a word fixed in History on a line the conversation's version waiting for room no longer has is not saved")
    func wordFixOfALineTheWaitingVersionLacksIsRefused() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.word-fix-line-not-waiting"))
        var early = record(id: UUID(), lines: 2, ended: false)
        early.segments[1].text = "I saw aval today"
        writer.saveNow(early)
        let gone = early.segments[1].id
        var later = early
        later.segments[1] = record(id: early.id, lines: 1, ended: true).segments[0]
        later.segments[1].text = "I saw Avi today"
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        #expect(writer.fixTextNow(sessionID: early.id, segmentID: gone, with: WordFix(heard: "aval", written: "Avital")) == .lineChanged)
        #expect(try writer.loadNow(id: early.id)?.segments.contains { $0.id == gone } == false, "History doesn't show the line")
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        #expect(store.load(id: early.id)?.segments.map(\.text) == ["שורה 0", "I saw Avi today"])
        #expect(writer.lastFailure == nil)
    }

    // The version waiting for room is the one the caption screen saved,
    // which knows nothing of a name given in History or of what first wrote
    // the conversation; its save keeps those from the file.
    @Test("History shows a version waiting for room with the name and source its save keeps from the file")
    func historyShowsTheWaitingVersionWithItsSavedDetails() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.load-waiting-details"))
        var early = record(id: UUID(), lines: 1, ended: false)
        early.inputName = "Kitchen"
        early.languageCode = "he"
        writer.saveNow(early)
        #expect(writer.renameNow(id: early.id, title: "Doctor"))
        var later = early
        later.segments += record(id: early.id, lines: 1, ended: true).segments
        later.title = nil
        later.inputName = "Car"
        later.languageCode = "en"
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)

        let shown = try #require(try writer.loadNow(id: early.id))
        #expect(shown.title == "Doctor")
        #expect(shown.inputName == "Kitchen")
        #expect(shown.languageCode == "he")
        #expect(shown.segments.count == 2)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.saveNow(record(id: UUID(), lines: 1, ended: false))
        let saved = try #require(store.load(id: early.id))
        #expect([saved.title, saved.inputName, saved.languageCode] == [shown.title, shown.inputName, shown.languageCode])
        #expect(saved.segments.count == 2)
    }

    // History is told a star it can't save didn't change. A line only the
    // waiting version has isn't in the file, so the file had nothing to
    // refuse, and the star was called saved while it only waited.
    @Test("a star from History on a line only the waiting version has counts once that version reaches the disk")
    func historyStarOnALineOnlyWaiting() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.history-star-line-only-waiting"))
        let early = record(id: UUID(), lines: 1, ended: false)
        writer.saveNow(early)
        var later = early
        later.segments += record(id: early.id, lines: 1, ended: true).segments
        later.endedAt = 200
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.saveNow(later)
        let added = later.segments[1].id

        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: added, keepingRefused: false) == nil)
        #expect(try writer.loadNow(id: early.id)?.segments.map(\.isStarred) == [false, false])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(writer.toggleStarNow(sessionID: early.id, segmentID: added, keepingRefused: false) == true)
        #expect(store.load(id: early.id)?.segments.map(\.isStarred) == [false, true])
        #expect(writer.lastFailure == nil)
    }

    // A star that wrote nothing put its outcome, none at all, in place of a
    // save that had really failed, and the warning went while the phone was
    // still full. A word fix that writes nothing leaves it.
    @Test("a star from History that writes nothing leaves a save that failed before reported, and goes once what waited is written")
    func historyStarThatWritesNothingKeepsTheWarning() throws {
        let (store, dir) = makeStore()
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.history-star-writes-nothing"))
        let evening = record(id: UUID(), lines: 1, ended: true)
        let other = record(id: UUID(), lines: 1, ended: true)
        writer.saveNow(evening)
        writer.saveNow(other)
        let line = evening.segments[0].id
        let file = dir.appendingPathComponent("\(evening.id.uuidString).json")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            try? FileManager.default.removeItem(at: dir)
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(!writer.renameNow(id: other.id, title: "Dinner"))
        let full = try #require(writer.lastFailure)

        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)
        #expect(writer.toggleStarNow(sessionID: evening.id, segmentID: line, keepingRefused: false) == nil)
        #expect(writer.lastFailure == full, "the phone is still full")
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        #expect(writer.toggleStarNow(sessionID: other.id, segmentID: UUID(), keepingRefused: false) == nil)
        #expect(writer.lastFailure == full, "a line that is gone says nothing about room either")

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(writer.toggleStarNow(sessionID: evening.id, segmentID: line, keepingRefused: false) == true)
        #expect(writer.lastFailure == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(writer.toggleStarNow(sessionID: evening.id, segmentID: line, keepingRefused: false) == nil)
        #expect(writer.lastFailure != nil, "a star the disk refuses is still reported")

        writer.saveNow(record(id: UUID(), lines: 1, ended: true))
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)
        #expect(writer.toggleStarNow(sessionID: evening.id, segmentID: line, keepingRefused: false) == nil)
        #expect(writer.lastFailure == nil, "what waited is written, so there is room")
    }

    // A word fixed on the caption screen is written to the line's saved
    // conversation too. Words that wrote nothing (no such conversation, no
    // such line, or the line had those words already) counted as a save
    // that worked, and took away the warning of a write that had really been
    // refused, while the phone was still full.
    @Test("a line's words that write nothing leave a save that failed before reported, and go once what waited is written")
    func lineWordsThatWriteNothingKeepTheWarning() throws {
        let (store, dir) = makeStore()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.line-words-write-nothing"))
        var old = record(id: UUID(), lines: 1, ended: true)
        old.segments[0].text = "I saw Avi"
        let line = old.segments[0].id
        let other = record(id: UUID(), lines: 1, ended: true)
        writer.saveNow(old)
        writer.saveNow(other)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(!writer.renameNow(id: other.id, title: "Dinner"))
        let full = try #require(writer.lastFailure)

        writer.replaceTextNow(sessionID: UUID(), segmentID: line, text: "I saw Avital")
        #expect(writer.lastFailure == full, "there is no such conversation, so nothing was written")
        writer.replaceTextNow(sessionID: other.id, segmentID: line, text: "I saw Avital")
        #expect(writer.lastFailure == full, "a conversation without the line says nothing about room either")
        writer.replaceTextNow(sessionID: old.id, segmentID: line, text: "I saw Avi")
        #expect(writer.lastFailure == full, "the line has those words already, so nothing was written")

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: old.id, segmentID: line, text: "I saw Avital")
        #expect(writer.lastFailure == nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: old.id, segmentID: line, text: "I saw Avi Cohen")
        #expect(writer.lastFailure != nil, "words the disk refuses are still reported")
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: other.id, segmentID: line, text: "I saw Dana")
        #expect(writer.lastFailure == nil, "what waited is written, so there is room")
        #expect(store.load(id: old.id)?.segments.first?.text == "I saw Avi Cohen")
    }

    @Test("once a waiting conversation is written, the warning goes even if the one on screen hasn't changed")
    func warningClearsWhenRefusedWritten() throws {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.refused-clears"))
        let refused = record(id: UUID(), lines: 3, ended: true)
        let blocker = dir.appendingPathComponent("\(refused.id.uuidString).json", isDirectory: true)
        try FileManager.default.createDirectory(at: blocker, withIntermediateDirectories: true)
        writer.saveNow(refused)
        let onScreen = record(id: UUID(), lines: 1, ended: false)
        writer.saveInBackground(onScreen)
        writer.waitUntilIdle()
        #expect(writer.lastFailure != nil)

        try FileManager.default.removeItem(at: blocker)
        writer.saveInBackground(onScreen)
        writer.waitUntilIdle()
        #expect(store.load(id: refused.id)?.segments.count == 3)
        #expect(writer.lastFailure == nil)
    }

    final class MadeOn: @unchecked Sendable {
        private let lock = NSLock()
        private var threads: [Thread] = []
        func note() { lock.withLock { threads.append(Thread.current) } }
        var all: [Thread] { lock.withLock { threads } }
    }

    @Test("an autosave's record is made on the writer's queue, in turn with the saves before it, not by the caller")
    func autosaveMakesItsRecordOnTheQueue() {
        let (store, dir) = makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        let queue = DispatchQueue(label: "test.writer")
        let writer = TranscriptHistoryWriter(store: store, queue: queue)
        let first = record(id: UUID(), lines: 1, ended: false)
        let later = record(id: first.id, lines: 3, ended: false)
        let made = MadeOn()

        queue.suspend()
        writer.saveInBackground(first)
        writer.saveInBackground(making: {
            made.note()
            return later
        })
        #expect(made.all.isEmpty)
        queue.resume()
        writer.waitUntilIdle()

        #expect(made.all.count == 1)
        #expect(!made.all.contains { $0 === Thread.current })
        #expect(store.load(id: first.id)?.segments.count == 3)
    }
}
