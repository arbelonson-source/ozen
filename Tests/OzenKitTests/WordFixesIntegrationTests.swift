import Foundation
import Testing
@testable import OzenKit

@Suite("Word fixes in settings, history and recordings")
struct WordFixesIntegrationTests {
    private let avi = WordFix(heard: "aval", written: "Avi")

    @Test("settings keep the fixes through a save and clean a messy list on load")
    func settingsRoundTrip() throws {
        var settings = AppSettings.default
        settings.wordFixes = [avi]
        let decoded = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(settings))
        #expect(decoded.wordFixes == [avi])
        let messy = #"{"wordFixes":[{"heard":"aval","written":"Avi"},{"heard":"AVAL","written":"x"},{"heard":"","written":"y"}]}"#
        let cleaned = try JSONDecoder().decode(AppSettings.self, from: Data(messy.utf8))
        #expect(cleaned.wordFixes == [avi])
        let old = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        #expect(old.wordFixes.isEmpty)
    }

    @Test("fixing a saved line changes the stored text, so search finds the new word")
    func savedLineChanges() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-wordfix-\(UUID())", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = TranscriptHistoryStore(directoryURL: dir)
        let id = UUID()
        let lineID = UUID()
        let other = UUID()
        let record = TranscriptSessionRecord(
            id: id, startedAt: 100, endedAt: 200, engine: .whisperKit, modelVariant: nil, inputName: nil,
            segments: [
                SavedSegment(id: lineID, text: "I saw aval today", speakerName: nil, speakerClusterID: nil, startTimestamp: 100, isCommitted: true),
                SavedSegment(id: other, text: "aval again", speakerName: nil, speakerClusterID: nil, startTimestamp: 101, isCommitted: true),
            ]
        )
        _ = try store.save(record)
        try store.replaceText(segmentID: lineID, inSession: id, with: "I saw Avi today")
        let loaded = try #require(store.load(id: id))
        #expect(loaded.segments.map(\.text) == ["I saw Avi today", "aval again"])
        #expect(store.search("Avi").map(\.id) == [id])
    }

    @Test("a recording's lines are written with the fixes applied")
    func recordingLines() async {
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("I saw aval today")] }
        let timeline = FileRunAudio.timeline([(false, 1), (true, 3), (false, 4)])
        let driver = FileTranscriptionDriver(
            engine: engine, languageCode: "he", names: [], wordFixes: [avi], durationSeconds: nil,
            allowCellularDownload: true, score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { 50_000_000_000 }, now: { FileRunClock().now }),
            retryDelay: .zero
        )
        let outcome = await driver.run(source: fileRunSource(timeline), report: { _ in })
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("did not finish")
            return
        }
        #expect(lines.map(\.text) == ["I saw Avi today"])
    }

    @Test("a fix added while a recording is being transcribed is in the finished lines")
    func fixAddedMidRun() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { number, _ in
            if number == 2 { fixes.value = [avi] }
            return [FileRunEngine.final("I saw aval today")]
        }
        let outcome = await liveRun(engine, fixes)
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("did not finish")
            return
        }
        #expect(lines.map(\.text) == ["I saw Avi today", "I saw Avi today"])
    }

    @Test("a fix added after the last line is made is still in the finished lines")
    func fixAddedAfterTheLastLine() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("I saw aval today")] }
        let outcome = await liveRun(engine, fixes) { progress in
            if progress.lines.count == 2 { fixes.value = [avi] }
        }
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("did not finish")
            return
        }
        #expect(lines.map(\.text) == ["I saw Avi today", "I saw Avi today"])
    }

    @Test("a fix added before a run breaks off is in the lines it keeps")
    func fixAddedBeforeTheRunStops() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { number, _ in
            guard number == 1 else {
                fixes.value = [avi]
                throw FileRunFailure()
            }
            return [FileRunEngine.final("I saw aval today")]
        }
        let outcome = await liveRun(engine, fixes)
        guard case .stoppedPartway(let lines, _) = outcome.result else {
            Issue.record("did not stop partway: \(outcome.result)")
            return
        }
        #expect(lines.map(\.text) == ["I saw Avi today"])
    }

    @Test("a fix whose written form contains its heard word, added mid-run, shows once and never grows")
    func growingFixAddedMidRun() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { number, _ in
            if number == 2 { fixes.value = [WordFix(heard: "Avi", written: "Avi Cohen")] }
            return [FileRunEngine.final("I saw Avi today")]
        }
        let outcome = await liveRun(engine, fixes)
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("did not finish")
            return
        }
        #expect(lines.map(\.text) == ["I saw Avi Cohen today", "I saw Avi Cohen today"])
    }

    @Test("a fix added mid-run reaches the earlier lines of the copy kept in case the app is ended")
    func fixAddedMidRunReachesTheKeptCopy() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { number, _ in
            if number == 2 { fixes.value = [avi] }
            return [FileRunEngine.final("I saw aval today")]
        }
        let copy = await keptCopy(engine, fixes)
        #expect(copy == ["I saw Avi today", "I saw Avi today"])
    }

    @Test("a fix removed mid-run leaves the earlier lines of the copy kept in case the app is ended")
    func fixRemovedMidRunLeavesTheKeptCopy() async {
        let fixes = FixesBox([avi])
        let engine = FileRunEngine { number, _ in
            if number == 2 { fixes.value = [] }
            return [FileRunEngine.final("I saw aval today")]
        }
        let copy = await keptCopy(engine, fixes)
        #expect(copy == ["I saw aval today", "I saw aval today"])
    }

    @Test("a fix whose written form contains its heard word, added mid-run, shows once in the kept copy")
    func growingFixAddedMidRunInTheKeptCopy() async {
        let fixes = FixesBox([])
        let engine = FileRunEngine { number, _ in
            if number == 2 { fixes.value = [WordFix(heard: "Avi", written: "Avi Cohen")] }
            return [FileRunEngine.final("I saw Avi today")]
        }
        let copy = await keptCopy(engine, fixes)
        #expect(copy == ["I saw Avi Cohen today", "I saw Avi Cohen today"])
    }

    /// The lines the run reports go to the keeper as the screen hands them
    /// on; what it last saved is what the next launch finds if the app is
    /// ended there.
    private func keptCopy(_ engine: FileRunEngine, _ fixes: FixesBox) async -> [String] {
        let reports = ReportsBox()
        _ = await liveRun(engine, fixes) { reports.add($0.lines) }
        var saved: [TranscriptSessionRecord] = []
        let keeper = FileTranscriptKeeper(
            historyOn: { true },
            save: { saved.append($0); return true },
            delete: { _ in },
            now: { 1_000 },
            everyLines: 2
        )
        keeper.begin(.init(fileName: "Talk.m4a", recordingSeconds: nil, modelVariant: "small", languageCode: "he"))
        for lines in reports.value { keeper.linesSoFar(lines, run: keeper.id) }
        return saved.last?.segments.map(\.text) ?? []
    }

    private func liveRun(
        _ engine: FileRunEngine,
        _ fixes: FixesBox,
        report: @escaping FileTranscriptionDriver.Report = { _ in }
    ) async -> FileTranscriptionDriver.Outcome {
        let timeline = FileRunAudio.timeline([(false, 1), (true, 3), (false, 2), (true, 2), (false, 4)])
        let driver = FileTranscriptionDriver(
            engine: engine, languageCode: "he", names: [], currentWordFixes: { fixes.value }, durationSeconds: nil,
            allowCellularDownload: true, score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { 50_000_000_000 }, now: { FileRunClock().now }),
            retryDelay: .zero
        )
        return await driver.run(source: fileRunSource(timeline), report: report)
    }
}

private final class FixesBox: @unchecked Sendable {
    private let lock = NSLock()
    private var fixes: [WordFix]

    init(_ fixes: [WordFix]) { self.fixes = fixes }

    var value: [WordFix] {
        get { lock.lock(); defer { lock.unlock() }; return fixes }
        set { lock.lock(); defer { lock.unlock() }; fixes = newValue }
    }
}

private final class ReportsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var reports: [[TranscribedLine]] = []

    func add(_ lines: [TranscribedLine]) {
        lock.lock()
        defer { lock.unlock() }
        reports.append(lines)
    }

    var value: [[TranscribedLine]] {
        lock.lock()
        defer { lock.unlock() }
        return reports
    }
}
