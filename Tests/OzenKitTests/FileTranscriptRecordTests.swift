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
        let record = FileTranscriptRecord.make(fileName: "Lecture 4.mp3", lines: lines, finishedAt: 1_100, recordingSeconds: 100, modelVariant: "small")
        #expect(record.title == "Lecture 4")
        #expect(FileTranscriptRecord.make(fileName: "PTT-2026.10.16-WA0003.opus", lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil).title == "PTT-2026.10.16-WA0003")
        #expect(FileTranscriptRecord.make(fileName: nil, lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil).title == nil)
        #expect(FileTranscriptRecord.make(fileName: "  ", lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil).title == nil)
    }

    @Test("every line is saved committed, at its offset from the start")
    func offsets() {
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: "base")
        #expect(record.segments.map(\.text) == ["first", "second"])
        #expect(record.segments.map(\.startTimestamp) == [5_000, 5_010.5])
        #expect(record.segments.allSatisfy { $0.isCommitted && $0.speakerName == nil })
        #expect(record.endedAt == 5_060)
        #expect(record.modelVariant == "base")
        #expect(record.engine == .whisperKit)
    }

    @Test("the entry is as long as the recording, not as the time turning it into text took, and ends when that was done")
    func recordingLength() {
        let lecture = [
            TranscribedLine(startSeconds: 1, endSeconds: 3, text: "first"),
            TranscribedLine(startSeconds: 3_500, endSeconds: 3_540, text: "last"),
        ]
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lecture, finishedAt: 10_000, recordingSeconds: 3_600, modelVariant: nil)
        #expect(record.endedAt == 10_000)
        #expect(record.startedAt == 6_400)
        #expect(record.segments.map(\.startTimestamp) == [6_401, 9_900])
        let unknown = FileTranscriptRecord.make(fileName: "a.wav", lines: lecture, finishedAt: 10_000, recordingSeconds: nil, modelVariant: nil)
        #expect(unknown.startedAt == 6_460)
        let tooShort = FileTranscriptRecord.make(fileName: "a.wav", lines: lecture, finishedAt: 10_000, recordingSeconds: 100, modelVariant: nil)
        #expect(tooShort.startedAt == 6_460)
    }

    @Test("a blank line is left out and the rest is trimmed")
    func blank() {
        let spaced = lines + [TranscribedLine(startSeconds: 20, endSeconds: 21, text: "   "), TranscribedLine(startSeconds: 30, endSeconds: 31, text: " padded \n")]
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: spaced, finishedAt: 1, recordingSeconds: nil, modelVariant: nil)
        #expect(record.segments.map(\.text) == ["first", "second", "padded"])
    }

    @Test("each save gets its own id unless one is given")
    func ids() {
        let one = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil)
        let two = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil)
        #expect(one.id != two.id)
        let fixed = UUID()
        #expect(FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 1, recordingSeconds: nil, modelVariant: nil, id: fixed).id == fixed)
    }

    @Test("the lines come back from the saved conversation in order")
    func roundTrip() {
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: nil)
        let back = FileTranscriptRecord.lines(of: record)
        #expect(back.map(\.text) == ["first", "second"])
        #expect(back.map(\.startSeconds) == [0, 10.5])
    }

    @Test("saved to the history store, it loads back and shows in the list under its name")
    func storeRoundTrip() throws {
        let directory = try folder()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = TranscriptHistoryStore(directoryURL: directory)
        let record = FileTranscriptRecord.make(fileName: "Lecture 4.mp3", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: "small")
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
        let empty = FileTranscriptRecord.make(fileName: "a.wav", lines: [], finishedAt: 1, recordingSeconds: nil, modelVariant: nil)
        #expect(try store.save(empty) == false)
    }

    @Test("the text export of a transcribed recording shows the time into the recording, not a clock time")
    func exportShowsOffsets() {
        let lecture = [
            TranscribedLine(startSeconds: 5, endSeconds: 8, text: "first"),
            TranscribedLine(startSeconds: 3_727, endSeconds: 3_730, text: "second"),
        ]
        let record = FileTranscriptRecord.make(fileName: "Lecture 4.mp3", lines: lecture, finishedAt: 1_700_000_000, recordingSeconds: 3_800, modelVariant: "small")
        let text = TranscriptHistoryStore.exportText(record)
        #expect(text.contains("[0:05] first"))
        #expect(text.contains("[1:02:07] second"))
        #expect(!text.contains("[\(TranscriptHistoryStore.formattedClockTime(record.segments[0].startTimestamp, utcOffsetSeconds: 0))]"))
    }

    @Test("the text export of a live conversation keeps its clock times")
    func liveExportKeepsClockTimes() {
        let segment = SavedSegment(id: UUID(), text: "hello", speakerName: nil, speakerClusterID: nil, startTimestamp: 50_527, isCommitted: true)
        let live = TranscriptSessionRecord(startedAt: 50_500, endedAt: 50_600, engine: .whisperKit, modelVariant: "small", inputName: nil, segments: [segment])
        #expect(TranscriptHistoryStore.exportText(live).contains("[14:02:07] hello"))
    }

    @Test("a record saved without the recording marker loads as a live conversation, and the marker survives saving")
    func markerIsLenient() throws {
        let record = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: nil)
        #expect(record.fromRecording)
        let data = try JSONEncoder().encode(record)
        #expect(try JSONDecoder().decode(TranscriptSessionRecord.self, from: data).fromRecording)
        var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object.removeValue(forKey: "fromRecording")
        let old = try JSONDecoder().decode(TranscriptSessionRecord.self, from: JSONSerialization.data(withJSONObject: object))
        #expect(!old.fromRecording)
        object["fromRecording"] = "yes"
        #expect(try !JSONDecoder().decode(TranscriptSessionRecord.self, from: JSONSerialization.data(withJSONObject: object)).fromRecording)
    }

    @Test("a transcript that stops early is saved as cut short, and a complete one is not")
    func cutShortIsSaved() throws {
        let whole = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: nil)
        #expect(!whole.cutShort)
        let early = FileTranscriptRecord.make(fileName: "a.wav", lines: lines, finishedAt: 5_060, recordingSeconds: 60, modelVariant: nil, cutShort: true)
        #expect(early.cutShort)
        let back = try JSONDecoder().decode(TranscriptSessionRecord.self, from: JSONEncoder().encode(early))
        #expect(back.cutShort)
        #expect(!TranscriptSessionRecord.cutShortNote.isEmpty)
    }

    @Test("a record saved before the cut-short marker existed loads as complete, and a damaged marker reads as complete")
    func oldRecordIsComplete() throws {
        let old = """
        {"id":"6F1B3C2A-1111-4222-8333-444455556666","startedAt":5000,"endedAt":5060,"engine":"whisperKit","modelVariant":"small","segments":[{"id":"6F1B3C2A-1111-4222-8333-444455556667","text":"first","startTimestamp":5000,"isCommitted":true}],"title":"a","fromRecording":true}
        """
        let record = try JSONDecoder().decode(TranscriptSessionRecord.self, from: Data(old.utf8))
        #expect(record.segments.count == 1)
        #expect(!record.cutShort)
        let damaged = old.replacingOccurrences(of: "\"fromRecording\":true", with: "\"fromRecording\":true,\"cutShort\":\"yes\"")
        #expect(try !JSONDecoder().decode(TranscriptSessionRecord.self, from: Data(damaged.utf8)).cutShort)
    }
}
