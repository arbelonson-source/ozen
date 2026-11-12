import Dispatch
import Foundation
import Testing
@testable import OzenKit

@Suite("Autosave keeps what it wrote in memory")
struct TranscriptAutosaveMemoryTests {
    private func lines(_ count: Int) -> [SavedSegment] {
        (0..<count).map {
            SavedSegment(id: UUID(), text: "word\($0)", speakerName: nil, speakerClusterID: nil, startTimestamp: Double($0), isCommitted: true)
        }
    }

    private func record(_ id: UUID, _ count: Int, engine: TranscriptionEngineKind = .whisperKit, title: String? = nil, ended: Bool = false) -> TranscriptSessionRecord {
        var record = TranscriptSessionRecord(
            id: id,
            startedAt: 100,
            endedAt: ended ? 900 : nil,
            engine: engine,
            modelVariant: "small",
            inputName: "mic",
            segments: lines(count)
        )
        record.title = title
        return record
    }

    private func withDirectory(_ body: (URL) throws -> Void) rethrows {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-autosave-\(UUID())")
        defer { try? FileManager.default.removeItem(at: dir) }
        try body(dir)
    }

    private func file(_ id: UUID, in dir: URL) -> URL {
        dir.appendingPathComponent("\(id.uuidString).json")
    }

    @Test("later saves of a growing conversation don't read its file again")
    func laterSavesDoNotRead() throws {
        try withDirectory { dir in
            let id = UUID()
            try TranscriptHistoryStore(directoryURL: dir).save(record(id, 1))
            let fresh = TranscriptHistoryStore(directoryURL: dir)
            try fresh.save(record(id, 2))
            #expect(fresh.conversationReads == 1, "a new store reads the file already there once")
            let before = fresh.conversationReads
            try fresh.save(record(id, 3))
            try fresh.save(record(id, 4))
            #expect(fresh.conversationReads - before == 0, "reads during the second and third saves")
        }
    }

    @Test("the first engine stays across remembered saves, and a name given meanwhile is kept")
    func firstEngineAndRenameKept() throws {
        try withDirectory { dir in
            let id = UUID()
            let store = TranscriptHistoryStore(directoryURL: dir)
            try store.save(record(id, 1))
            try store.save(record(id, 2, engine: .homeServer))
            try store.rename(id: id, title: "Lecture")
            try store.save(record(id, 3, engine: .cloud))
            let loaded = try #require(store.load(id: id))
            #expect(loaded.engine == .whisperKit)
            #expect(loaded.title == "Lecture")
        }
    }

    @Test("a file another store changed is read again, keeping its name and source")
    func changedFileIsReadAgain() throws {
        try withDirectory { dir in
            let id = UUID()
            let store = TranscriptHistoryStore(directoryURL: dir)
            try store.save(record(id, 1))
            try store.save(record(id, 2))
            try FileManager.default.removeItem(at: file(id, in: dir))
            try TranscriptHistoryStore(directoryURL: dir).save(record(id, 5, engine: .cloud, title: "Renamed elsewhere"))
            let before = store.conversationReads
            try store.save(record(id, 6))
            #expect(store.conversationReads > before, "the changed file must be read again")
            let loaded = try #require(store.load(id: id))
            #expect(loaded.title == "Renamed elsewhere")
            #expect(loaded.engine == .cloud)
            try FileManager.default.removeItem(at: file(id, in: dir))
            try store.save(record(id, 7, engine: .homeServer))
            #expect(store.load(id: id)?.engine == .homeServer)
        }
    }

    @Test("six autosaves inside two minutes rebuild History's caches once, and History still finds the last")
    func autosavesRefreshCachesOnce() throws {
        try withDirectory { dir in
            let id = UUID()
            let clock = TestClock()
            let store = TranscriptHistoryStore(directoryURL: dir, now: { Date(timeIntervalSince1970: clock.now) })
            for count in 1...6 {
                try store.save(record(id, count), throttleCaches: true)
                clock.advance(20)
                // Linux stamps files from a coarse clock: saves a few
                // milliseconds apart got the same time, and the stale
                // summary passed as fresh.
                Thread.sleep(forTimeInterval: 0.02)
            }
            #expect(store.cacheRefreshes == 1, "cache refreshes in six autosaves")
            #expect(store.search("word5").map(\.id) == [id])
            #expect(store.listSummaries().map(\.segmentCount) == [6])
            clock.advance(120)
            let ended = TranscriptHistoryStore(directoryURL: dir, now: { Date(timeIntervalSince1970: clock.now) })
            try ended.save(record(id, 7, ended: true))
            #expect(ended.cacheRefreshes == 1)
            #expect(ended.listSummaries().map(\.segmentCount) == [7])
        }
    }

    @Test("an autosave two minutes after the last rebuild rebuilds again")
    func autosaveAfterTwoMinutesRefreshes() throws {
        try withDirectory { dir in
            let id = UUID()
            let clock = TestClock()
            let store = TranscriptHistoryStore(directoryURL: dir, now: { Date(timeIntervalSince1970: clock.now) })
            try store.save(record(id, 1), throttleCaches: true)
            clock.advance(119)
            try store.save(record(id, 2), throttleCaches: true)
            clock.advance(1)
            try store.save(record(id, 3), throttleCaches: true)
            #expect(store.cacheRefreshes == 2)
        }
    }

    @Test("the writer's autosave of an open conversation spares the caches, an ended one rebuilds them")
    func writerThrottlesOnlyOpenConversations() throws {
        try withDirectory { dir in
            let id = UUID()
            let store = TranscriptHistoryStore(directoryURL: dir)
            let writer = TranscriptHistoryWriter(store: store)
            func save(_ record: TranscriptSessionRecord) {
                let done = DispatchSemaphore(value: 0)
                writer.saveInBackground(record) { done.signal() }
                #expect(done.wait(timeout: .now() + 10) == .success)
            }
            save(record(id, 1))
            save(record(id, 2))
            save(record(id, 3))
            #expect(store.cacheRefreshes == 1)
            save(record(id, 4, ended: true))
            #expect(store.cacheRefreshes == 2)
        }
    }
}
