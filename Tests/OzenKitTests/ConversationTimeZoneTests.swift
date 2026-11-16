import Foundation
import Testing
@testable import OzenKit

@Suite("A saved conversation keeps the time zone it happened in")
struct ConversationTimeZoneTests {
    private let halfPastMidnightInTelAviv: TimeInterval = 1_794_695_400
    private let telAviv = 7_200
    private let london = 0

    private func record(offset: Int?) -> TranscriptSessionRecord {
        let line = SavedSegment(id: UUID(), text: "hello", speakerName: nil, speakerClusterID: nil, startTimestamp: halfPastMidnightInTelAviv, isCommitted: true)
        return TranscriptSessionRecord(
            startedAt: halfPastMidnightInTelAviv,
            engine: .whisperKit,
            modelVariant: nil,
            inputName: nil,
            segments: [line],
            utcOffsetSeconds: offset
        )
    }

    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("time-zone-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("an export read on a phone set to another zone shows the conversation's own clock times and date")
    func exportUsesOwnOffset() {
        let text = TranscriptHistoryStore.exportText(record(offset: telAviv), utcOffsetAt: { _ in london }, currentLanguageCode: "en")
        #expect(text.contains("[00:30:00] hello"))
        #expect(!text.contains("14.11.2026"))
        #expect(text.contains("15.11.2026"))
    }

    @Test("a record without an offset is read in the phone's zone, as before")
    func oldRecordFollowsPhone() {
        let text = TranscriptHistoryStore.exportText(record(offset: nil), utcOffsetAt: { _ in london }, currentLanguageCode: "en")
        #expect(text.contains("[22:30:00] hello"))
        #expect(text.contains("14.11.2026"))
    }

    @Test("History puts a conversation under the day it happened on, whatever zone the phone is in now")
    func daysUseOwnOffset() {
        let now = halfPastMidnightInTelAviv + 3_600
        let own = TranscriptSessionSummary(summarizing: record(offset: telAviv))
        let old = TranscriptSessionSummary(summarizing: record(offset: nil))
        let phoneOffset: (TimeInterval) -> Int = { _ in 0 }
        let ownDays = HistoryDays.grouped([own], now: now, utcOffsetSeconds: phoneOffset)
        let oldDays = HistoryDays.grouped([old], now: now, utcOffsetSeconds: phoneOffset)
        #expect(ownDays[0].id == oldDays[0].id + 1)
    }

    @Test("the offset survives saving and loading, and a record that never had one still loads")
    func roundTrip() throws {
        let store = TranscriptHistoryStore(directoryURL: try folder())
        let kept = record(offset: telAviv)
        try store.save(kept)
        #expect(store.load(id: kept.id)?.utcOffsetSeconds == telAviv)
        #expect(store.listSummaries().first?.utcOffsetSeconds == telAviv)
        let plain = record(offset: nil)
        try store.save(plain)
        #expect(store.load(id: plain.id)?.utcOffsetSeconds == nil)
        let json = String(decoding: try JSONEncoder().encode(plain), as: UTF8.self)
        #expect(!json.contains("utcOffsetSeconds"))
        let damaged = json.replacingOccurrences(of: "\"engine\"", with: "\"utcOffsetSeconds\":\"x\",\"engine\"")
        #expect(try JSONDecoder().decode(TranscriptSessionRecord.self, from: Data(damaged.utf8)).utcOffsetSeconds == nil)
    }

    @Test("a later save from another zone keeps the offset the conversation was first saved with")
    func laterSaveKeepsFirstOffset() throws {
        let store = TranscriptHistoryStore(directoryURL: try folder())
        var first = record(offset: telAviv)
        try store.save(first)
        first.utcOffsetSeconds = london
        first.title = "Visit"
        try store.save(first)
        #expect(store.load(id: first.id)?.utcOffsetSeconds == telAviv)
    }

    @Test("starred lines are shared in the conversation's own zone")
    func starredUsesOwnOffset() {
        let line = StarredLine(sessionID: UUID(), sessionStartedAt: halfPastMidnightInTelAviv, segment: record(offset: nil).segments[0], utcOffsetSeconds: telAviv)
        #expect(TranscriptHistoryStore.exportStarredText([line], utcOffsetAt: { _ in london }).contains("[00:30:00] hello"))
    }
}
