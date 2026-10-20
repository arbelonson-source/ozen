import Foundation
import Testing
@testable import OzenKit

@Suite("A transcribed recording as a saved conversation")
struct FileTranscriptRecordTests {
    private let lines = [
        TranscribedLine(startSeconds: 0, endSeconds: 2, text: "first"),
        TranscribedLine(startSeconds: 10.5, endSeconds: 12, text: "second"),
    ]

    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("file-record-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("the file name, without its extension, becomes the title")
    func title() {
        let record = FileTranscriptRecord.make(fileName: "Lecture 4.mp3", lines: lines, startedAt: 1_000, endedAt: 1_100, modelVariant: "small")
        #expect(record.title == "Lecture 4")
        #expect(FileTranscriptRecord.make(fileName: "PTT-2026.10.16-WA0003.opus", lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil).title == "PTT-2026.10.16-WA0003")
        #expect(FileTranscriptRecord.make(fileName: nil, lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil).title == nil)
        #expect(FileTranscriptRecord.make(fileName: "  ", lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil).title == nil)
    }

    @Test("every line is saved committed, at its offset from the start")
    func offsets() {
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, startedAt: 5_000, endedAt: 5_060, modelVariant: "base")
        #expect(record.segments.map(\.text) == ["first", "second"])
        #expect(record.segments.map(\.startTimestamp) == [5_000, 5_010.5])
        #expect(record.segments.allSatisfy { $0.isCommitted && $0.speakerName == nil })
        #expect(record.endedAt == 5_060)
        #expect(record.modelVariant == "base")
        #expect(record.engine == .whisperKit)
    }

    @Test("a blank line is left out and the rest is trimmed")
    func blank() {
        let spaced = lines + [TranscribedLine(startSeconds: 20, endSeconds: 21, text: "   "), TranscribedLine(startSeconds: 30, endSeconds: 31, text: " padded \n")]
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: spaced, startedAt: 0, endedAt: 1, modelVariant: nil)
        #expect(record.segments.map(\.text) == ["first", "second", "padded"])
    }

    @Test("each save gets its own id unless one is given")
    func ids() {
        let one = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil)
        let two = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil)
        #expect(one.id != two.id)
        let fixed = UUID()
        #expect(FileTranscriptRecord.make(fileName: "a.wav", lines: lines, startedAt: 0, endedAt: 1, modelVariant: nil, id: fixed).id == fixed)
    }

    @Test("the lines come back from the saved conversation in order")
    func roundTrip() {
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, startedAt: 5_000, endedAt: 5_060, modelVariant: nil)
        let back = FileTranscriptRecord.lines(of: record)
        #expect(back.map(\.text) == ["first", "second"])
        #expect(back.map(\.startSeconds) == [0, 10.5])
    }

    @Test("saved to the history store, it loads back and shows in the list under its name")
    func storeRoundTrip() throws {
        let directory = try folder()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = TranscriptHistoryStore(directoryURL: directory)
        let record = FileTranscriptRecord.make(fileName: "Lecture 4.mp3", lines: lines, startedAt: 5_000, endedAt: 5_060, modelVariant: "small")
        #expect(try store.save(record))
        let loaded = try #require(store.load(id: record.id))
        #expect(loaded == record)
        let summary = try #require(store.listSummaries().first { $0.id == record.id })
        #expect(summary.title == "Lecture 4")
        #expect(summary.segmentCount == 2)
        #expect(summary.preview == "first")
        #expect(summary.durationSeconds == 60)
    }

    @Test("a recording with nothing in it is not saved at all")
    func nothingToSave() throws {
        let directory = try folder()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = TranscriptHistoryStore(directoryURL: directory)
        let empty = FileTranscriptRecord.make(fileName: "a.wav", lines: [], startedAt: 0, endedAt: 1, modelVariant: nil)
        #expect(try store.save(empty) == false)
    }
}
