import Foundation
import Testing
@testable import OzenKit

@Suite("A conversation that runs across a clock change reads each line by the offset it had")
struct ClockChangeTimeTests {
    private let beforeChange: TimeInterval = 1_792_881_000
    private let afterChange: TimeInterval = 1_792_888_200
    private let summerOffset = 10_800

    private func line(_ text: String, at time: TimeInterval) -> SavedSegment {
        SavedSegment(id: UUID(), text: text, speakerName: nil, speakerClusterID: nil, startTimestamp: time, isCommitted: true)
    }

    private func record(zone: String?, offset: Int?) -> TranscriptSessionRecord {
        TranscriptSessionRecord(
            startedAt: beforeChange,
            engine: .whisperKit,
            modelVariant: nil,
            inputName: nil,
            segments: [line("before", at: beforeChange), line("after", at: afterChange)],
            utcOffsetSeconds: offset,
            timeZoneIdentifier: zone
        )
    }

    @Test("an export shows 01:30 before the clocks go back and 02:30 after")
    func exportAcrossChange() {
        let text = TranscriptHistoryStore.exportText(record(zone: "Asia/Jerusalem", offset: summerOffset), utcOffsetAt: { _ in 0 }, currentLanguageCode: "en")
        #expect(text.contains("[01:30:00] before"))
        #expect(text.contains("[02:30:00] after"))
    }

    @Test("a record saved with only the starting offset keeps reading by it, as before")
    func oldRecordKeepsFixedOffset() {
        let text = TranscriptHistoryStore.exportText(record(zone: nil, offset: summerOffset), utcOffsetAt: { _ in 0 }, currentLanguageCode: "en")
        #expect(text.contains("[01:30:00] before"))
        #expect(text.contains("[03:30:00] after"))
    }

    @Test("a zone name this phone does not know falls back to the starting offset")
    func unknownZoneFallsBack() {
        let kept = record(zone: "Nowhere/Land", offset: summerOffset)
        #expect(kept.utcOffsetSeconds(at: afterChange) == summerOffset)
    }

    @Test("starred lines are shared with the offset each line had")
    func starredAcrossChange() {
        let source = record(zone: "Asia/Jerusalem", offset: summerOffset)
        let lines = source.segments.map {
            StarredLine(sessionID: source.id, sessionStartedAt: beforeChange, segment: $0, utcOffsetSeconds: summerOffset, timeZoneIdentifier: "Asia/Jerusalem")
        }
        let text = TranscriptHistoryStore.exportStarredText(lines, utcOffsetAt: { _ in 0 })
        #expect(text.contains("[01:30:00] before"))
        #expect(text.contains("[02:30:00] after"))
    }

    @Test("the zone survives saving and loading, and a later save keeps the first one")
    func zoneRoundTrip() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("clock-change-\(UUID().uuidString)")
        let store = TranscriptHistoryStore(directoryURL: folder)
        var first = record(zone: "Asia/Jerusalem", offset: summerOffset)
        try store.save(first)
        #expect(store.load(id: first.id)?.timeZoneIdentifier == "Asia/Jerusalem")
        first.timeZoneIdentifier = "America/New_York"
        try store.save(first)
        #expect(store.load(id: first.id)?.timeZoneIdentifier == "Asia/Jerusalem")
        let plain = record(zone: nil, offset: summerOffset)
        try store.save(plain)
        #expect(store.load(id: plain.id)?.timeZoneIdentifier == nil)
        let json = String(decoding: try JSONEncoder().encode(plain), as: UTF8.self)
        #expect(!json.contains("timeZoneIdentifier"))
    }
}
