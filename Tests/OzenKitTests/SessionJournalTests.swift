import Foundation
import Testing
@testable import OzenKit

@Suite("SessionJournal")
struct SessionJournalTests {
    private let temporary = TemporaryFolder("ozen-journal")

    private func temporaryFile() -> URL {
        temporary.url
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("journal.log")
    }

    @Test("what one run of the app wrote is there for the next one to read")
    func survivesRelaunch() throws {
        let url = temporaryFile()
        let first = SessionJournal(fileURL: url)
        first.append("listening", at: 1_800_000_000)
        first.append("microphone stopped delivering audio", at: 1_800_000_065.5)
        _ = first.entries()

        let second = SessionJournal(fileURL: url)
        second.append("app started", at: 1_800_000_100)

        #expect(second.entries().map(\.text) == ["listening", "microphone stopped delivering audio", "app started"])
        #expect(second.entries()[1].at == 1_800_000_065.5)
        let written = try String(decoding: Data(contentsOf: url), as: UTF8.self)
        #expect(written == "1800000000.00\tlistening\n1800000065.50\tmicrophone stopped delivering audio\n1800000100.00\tapp started\n")
    }

    @Test("flush puts the buffered lines on disk right away, and each line is still read once")
    func flushWritesNow() throws {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        journal.append("app left the screen", at: 1_800_000_000)
        journal.append("listening", at: 1_800_000_001)
        journal.flush()

        let written = try String(decoding: Data(contentsOf: url), as: UTF8.self)
        #expect(written == "1800000000.00\tapp left the screen\n1800000001.00\tlistening\n")
        #expect(journal.entries().map(\.text) == ["app left the screen", "listening"])
    }

    @Test("without a flush the lines wait in memory for a moment")
    func unflushedWaits() {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        journal.append("app left the screen", at: 1_800_000_000)
        let onDisk = (try? Data(contentsOf: url)) ?? Data()
        #expect(onDisk.isEmpty)
        _ = journal.entries()
    }

    @Test("a line cut off when the app was killed mid-write doesn't swallow the next run's first line")
    func survivesATornLastLine() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("1800000000.00\tlistening\n1800000001.00\tmicrophone sto".utf8).write(to: url)

        let journal = SessionJournal(fileURL: url)
        journal.append("app started", at: 1_800_000_100)

        #expect(journal.entries().map(\.text) == ["listening", "microphone sto", "app started"])
        #expect(journal.entries().last?.at == 1_800_000_100)
    }

    @Test("a caption ending in a carriage return doesn't swallow the line after it")
    func carriageReturnKeepsLinesApart() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("  line (final, sure -, 10:00:00): hello\r", at: 1_800_000_000)
        journal.append("  sound saved: problem-x.wav", at: 1_800_000_001)
        #expect(journal.entries().map(\.text) == ["  line (final, sure -, 10:00:00): hello ", "  sound saved: problem-x.wav"])
    }

    @Test("the caption lines a marked problem kept can be taken out, and everything else stays")
    func removesSpokenLines() {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        let segments = ["תתקשרי לרופא", "מחר בעשר"].map {
            TranscriptSegment(id: UUID(), text: $0, isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 60)
        }
        journal.append("listening", at: 1_800_000_000)
        for line in ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, device: "-", utcOffsetSeconds: 0) {
            journal.append(line, at: 1_800_000_010)
        }
        journal.append("  sound saved: problem.wav", at: 1_800_000_010)
        journal.append("stopped", at: 1_800_000_020)

        journal.removeEntries(where: ProblemSnapshot.isCaptionLine)

        let kept = SessionJournal(fileURL: url).entries().map(\.text)
        #expect(!kept.contains { $0.contains("תתקשרי לרופא") || $0.contains("מחר בעשר") })
        #expect(kept.count == 6)
        #expect(kept.first == "listening")
        #expect(kept.last == "stopped")
        #expect(kept.contains { $0.hasPrefix("PROBLEM MARKED") })
    }

    @Test("caption lines a full phone is still holding back are taken out too, and never reach the file once it has room")
    func removedCaptionLinesWaitingForRoomStayRemoved() throws {
        let url = temporaryFile()
        let folder = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path) }
        let journal = SessionJournal(fileURL: url)
        let segments = ["תתקשרי לרופא", "מחר בעשר"].map {
            TranscriptSegment(id: UUID(), text: $0, isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 60)
        }
        journal.append("listening", at: 1_800_000_000)
        for line in ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, device: "-", utcOffsetSeconds: 0) {
            journal.append(line, at: 1_800_000_010)
        }
        journal.append("stopped", at: 1_800_000_020)
        #expect(journal.entries().contains { $0.text.contains("תתקשרי לרופא") })

        journal.removeEntries(where: ProblemSnapshot.isCaptionLine)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
        journal.append("listening again", at: 1_800_000_030)
        _ = journal.entries()

        let kept = SessionJournal(fileURL: url).entries().map(\.text)
        #expect(!kept.contains { $0.contains("תתקשרי לרופא") || $0.contains("מחר בעשר") })
        #expect(kept.first == "listening")
        #expect(kept.suffix(2) == ["stopped", "listening again"])
        #expect(kept.contains { $0.hasPrefix("PROBLEM MARKED") })
        let onDisk = try String(contentsOf: url, encoding: .utf8)
        #expect(!onDisk.contains("תתקשרי לרופא"))
    }

    @Test("lines from before a clock change keep the clock they were written at")
    func reportAcrossClockChange() {
        let journal = SessionJournal(fileURL: temporaryFile())
        let change: TimeInterval = 1_800_000_000
        journal.append("listening", at: change - 3_600)
        journal.append("stopped", at: change + 3_600)
        let lines = journal.reportLines(utcOffsetAt: { $0 < change ? 3 * 3_600 : 2 * 3_600 })
        #expect(lines == ["2027-01-15 10:00:00 listening", "2027-01-15 11:00:00 stopped"])
    }

    @Test("lines carry the day as well as the time, at her clock")
    func reportLines() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("listening", at: 1_800_000_000)
        #expect(journal.reportLines(utcOffsetSeconds: 3 * 3_600) == ["2027-01-15 11:00:00 listening"])
    }

    @Test("an error that runs to paragraphs stays one line, and a short one")
    func longText() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("first\nsecond\tthird " + String(repeating: "x", count: 2_000), at: 1)
        let entries = journal.entries()
        #expect(entries.count == 1)
        #expect(entries[0].text.hasPrefix("first second third x"))
        #expect(entries[0].text.count <= SessionJournal.textLimit + 1)
    }

    @Test("months of lines never grow the file without end: the oldest go")
    func trims() throws {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        let text = String(repeating: "y", count: 300)
        for index in 0..<1_000 {
            journal.append("\(index) \(text)", at: TimeInterval(index))
        }
        let entries = journal.entries()
        #expect(entries.last?.text.hasPrefix("999 ") == true)
        #expect(entries.count >= 100)
        let size = try #require(try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int)
        #expect(size <= SessionJournal.maximumBytes + 1_000)
    }

    #if canImport(Darwin)
    @Test("the journal stays out of iCloud and computer backups, also once its oldest lines were trimmed")
    func notBackedUp() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let journal = SessionJournal(fileURL: url)
        func excluded() throws -> Bool {
            var fresh = url
            fresh.removeAllCachedResourceValues()
            return try fresh.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true
        }
        journal.append("listening", at: 1)
        _ = journal.entries()
        #expect(try excluded())
        // A flush past maximumBytes rewrites the file as a new one.
        let text = String(repeating: "y", count: 300)
        for index in 0..<1_000 {
            journal.append("\(index) \(text)", at: TimeInterval(index))
        }
        _ = journal.entries()
        #expect(try excluded())
    }
    #endif

    @Test("lines that could not be written, say on a full phone, are kept and written once the disk takes them")
    func failedWriteKeepsLines() throws {
        let url = temporaryFile()
        let folder = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: folder.path)
        let journal = SessionJournal(fileURL: url)
        journal.append("problem marked", at: 1_800_000_000)
        _ = journal.entries()
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
        journal.append("listening", at: 1_800_000_010)
        #expect(journal.entries().map(\.text) == ["problem marked", "listening"])
    }

    @Test("lines the disk refused still show while they wait, so a report made meanwhile has the problem she marked")
    func refusedLinesStillListed() throws {
        let url = temporaryFile()
        let folder = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path) }
        let journal = SessionJournal(fileURL: url)
        journal.append("problem marked", at: 1_800_000_000)
        #expect(journal.entries().map(\.text) == ["problem marked"])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
        journal.flush()
    }

    @Test("a file someone damaged loses the bad lines, not the rest")
    func damagedFile() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("garbage\n12.00\tkept\n\tno time\n".utf8).write(to: url)
        #expect(SessionJournal(fileURL: url).entries() == [SessionJournal.Entry(at: 12, text: "kept")])
    }

    @Test("a burst of a thousand appends all still show up, in order, once asked for")
    func bufferedBurstIsComplete() {
        let journal = SessionJournal(fileURL: temporaryFile())
        for index in 0..<1_000 {
            journal.append("line \(index)", at: TimeInterval(index))
        }
        let entries = journal.entries()
        #expect(entries.count == 1_000)
        #expect(entries.first?.text == "line 0")
        #expect(entries.last?.text == "line 999")
    }

    @Test("buffered lines reach the disk by themselves, batch after batch, with nobody reading them", .timeLimit(.minutes(1)))
    func flushesByItself() async throws {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        func onDisk(_ text: String) async throws -> Bool {
            for _ in 0..<100 {
                if let data = try? Data(contentsOf: url), String(decoding: data, as: UTF8.self).contains(text) { return true }
                try await Task.sleep(for: .milliseconds(100))
            }
            return false
        }
        journal.append("first batch", at: 1)
        #expect(try await onDisk("first batch"))
        journal.append("second batch", at: 2)
        #expect(try await onDisk("second batch"))
    }

    @Test("a fresh append sits buffered in memory rather than touching disk right away; entries() flushes it and sees it immediately regardless")
    func bufferedUntilAskedFor() {
        let url = temporaryFile()
        let journal = SessionJournal(fileURL: url)
        journal.append("not on disk yet", at: 1)
        // Comfortably shorter than SessionJournal.flushDelay: the file
        // must not have been written this soon after a single append.
        Thread.sleep(forTimeInterval: 0.05)
        let onDiskAlready = (try? Data(contentsOf: url)).map { String(decoding: $0, as: UTF8.self) } ?? ""
        #expect(!onDiskAlready.contains("not on disk yet"), "a single fresh append should be buffered, not hit disk immediately")
        #expect(journal.entries().map(\.text) == ["not on disk yet"], "entries() must flush and see it right away regardless of the buffering delay")
    }

    private func markProblem(_ journal: SessionJournal, words: [String], at time: TimeInterval) {
        let segments = words.map {
            TranscriptSegment(id: UUID(), text: $0, isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 60)
        }
        for line in ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, device: "-", utcOffsetSeconds: 0) {
            journal.append(line, at: time)
        }
        journal.append("  sound saved: problem.wav", at: time)
    }

    @Test("the helper's lines carry the caption rows of the newest marked problem and none of an older one")
    func helperLinesKeepOnlyTheNewestCaptions() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("listening", at: 1_800_000_000)
        markProblem(journal, words: ["older words alpha", "older words beta"], at: 1_800_000_010)
        journal.append("microphone stopped delivering audio", at: 1_800_000_020)
        markProblem(journal, words: ["newer words gamma", "newer words delta", "newer words epsilon"], at: 1_800_000_030)

        let lines = journal.helperReportLines(utcOffsetAt: { _ in 0 })

        #expect(lines.filter { $0.contains("  line (") }.count == 3)
        #expect(lines.contains { $0.contains("newer words gamma") })
        #expect(lines.contains { $0.contains("newer words epsilon") })
        #expect(!lines.contains { $0.contains("older words") })
        #expect(lines.filter { $0.contains("PROBLEM MARKED") }.count == 2)
        #expect(lines.contains { $0.hasSuffix("microphone stopped delivering audio") })
        #expect(lines.contains { $0.hasSuffix("listening") })
    }

    @Test("with one marked problem the helper's lines are the journal's lines")
    func helperLinesWithOneProblemAreUnchanged() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("listening", at: 1_800_000_000)
        markProblem(journal, words: ["only words one", "only words two"], at: 1_800_000_010)
        journal.append("stopped", at: 1_800_000_020)

        #expect(journal.helperReportLines(utcOffsetAt: { _ in 0 }) == journal.reportLines(utcOffsetAt: { _ in 0 }))
    }

    @Test("caption rows with no marked problem above them stay out of the helper's lines")
    func helperLinesDropOrphanCaptionRows() {
        let journal = SessionJournal(fileURL: temporaryFile())
        journal.append("  line (final, sure -, 10:00:00): orphan words", at: 1_800_000_000)
        journal.append("listening", at: 1_800_000_010)

        #expect(journal.helperReportLines(utcOffsetAt: { _ in 0 }).count == 1)
        #expect(!journal.helperReportLines(utcOffsetAt: { _ in 0 }).joined().contains("orphan"))
    }

    private func markedJournal() -> SessionJournal {
        let journal = SessionJournal(fileURL: temporaryFile())
        func mark(_ words: [String], at time: TimeInterval) {
            let segments = words.map {
                TranscriptSegment(id: UUID(), text: $0, isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 60)
            }
            for line in ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, device: "-", utcOffsetSeconds: 0) {
                journal.append(line, at: time)
            }
        }
        journal.append("listening", at: 1_000)
        mark(["words of the first"], at: 1_100)
        mark(["more of the first"], at: 1_900)
        journal.append("stopped", at: 2_000)
        journal.append("listening", at: 3_000)
        mark(["words of the second"], at: 3_100)
        journal.append("stopped", at: 4_000)
        return journal
    }

    private func summary(from start: TimeInterval, to end: TimeInterval?, lastLineAt: TimeInterval? = nil) -> TranscriptSessionSummary {
        TranscriptSessionSummary(id: UUID(), startedAt: start, endedAt: end, segmentCount: 1, preview: "", engine: .whisperKit, lastLineAt: lastLineAt)
    }

    @Test("deleting a conversation takes out the caption lines marked inside it, and only those")
    func deletingConversationRemovesItsCaptionLines() {
        let journal = markedJournal()
        let before = journal.entries()

        journal.removeCaptionLines(of: summary(from: 1_000, to: 2_000), running: false)

        let kept = journal.entries()
        #expect(!kept.contains { $0.text.contains("of the first") })
        #expect(kept.filter { $0.text.contains("of the second") }.count == 1)
        #expect(kept.filter { !ProblemSnapshot.isCaptionLine($0.text) } == before.filter { !ProblemSnapshot.isCaptionLine($0.text) })
        #expect(kept.filter { $0.text.hasPrefix("PROBLEM MARKED") }.count == 3)
        #expect(kept.count == before.count - 2)
    }

    @Test("deleting a conversation nothing was marked in changes nothing")
    func deletingUnmarkedConversationChangesNothing() throws {
        let journal = markedJournal()
        let before = journal.entries()

        journal.removeCaptionLines(of: summary(from: 5_000, to: 6_000), running: false)

        #expect(journal.entries() == before)
    }

    @Test("a conversation with no end time is bounded by its last line plus a minute, and a running one by the open end")
    func conversationWithoutEndTime() {
        let cutShort = markedJournal()
        cutShort.removeCaptionLines(of: summary(from: 1_000, to: nil, lastLineAt: 1_900), running: false)
        #expect(!cutShort.entries().contains { $0.text.contains("of the first") })
        #expect(cutShort.entries().contains { $0.text.contains("of the second") })

        let running = markedJournal()
        running.removeCaptionLines(of: summary(from: 3_000, to: nil), running: true)
        #expect(running.entries().contains { $0.text.contains("of the first") })
        #expect(!running.entries().contains { $0.text.contains("of the second") })
    }

    @Test("a conversation that listening resumed in before its summary was saved again still takes the lines marked after its old end")
    func resumedConversationKeepsOpenEnd() {
        let journal = markedJournal()
        journal.removeCaptionLines(of: summary(from: 3_000, to: 3_050), running: true)
        #expect(!journal.entries().contains { $0.text.contains("of the second") })
        #expect(journal.entries().contains { $0.text.contains("of the first") })
    }

    private func mark(_ journal: SessionJournal, _ words: String, at time: TimeInterval, conversation: UUID?, microphone: String? = nil) {
        let segment = TranscriptSegment(id: UUID(), text: words, isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 60)
        let input = microphone.map { AudioInputDescriptor(uid: "u", portName: $0, portType: .builtInMic) }
        for line in ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: input, stats: PipelineStats(), segments: [segment], device: "-", utcOffsetSeconds: 0, conversationID: conversation) {
            journal.append(line, at: time)
        }
    }

    @Test("a problem marked in the quiet after the last caption, before the conversation closed, goes with its conversation")
    func markedInTheQuietAfterTheLastCaption() {
        let journal = SessionJournal(fileURL: temporaryFile())
        let first = summary(from: 1_000, to: 1_500)
        let other = UUID()
        mark(journal, "said before the quiet", at: 1_900, conversation: first.id)
        mark(journal, "said in another", at: 1_950, conversation: other, microphone: "mic conversation \(first.id.uuidString)")

        journal.removeCaptionLines(of: first, running: false)

        #expect(!journal.entries().contains { $0.text.contains("said before the quiet") })
        #expect(journal.entries().contains { $0.text.contains("said in another") })
    }

    @Test("an interrupted conversation's window stops where the next one starts, and a block naming the next one is never touched")
    func interruptedThenNewConversation() {
        let journal = SessionJournal(fileURL: temporaryFile())
        let interrupted = summary(from: 1_000, to: nil, lastLineAt: 1_900)
        mark(journal, "old block of the interrupted", at: 1_920, conversation: nil)
        mark(journal, "old block of the new", at: 1_960, conversation: nil)
        mark(journal, "named block of the new", at: 1_940, conversation: UUID())

        journal.removeCaptionLines(of: interrupted, running: false, nextStartedAt: 1_950)

        let kept = journal.entries().map(\.text).joined(separator: "\n")
        #expect(!kept.contains("old block of the interrupted"))
        #expect(kept.contains("old block of the new"))
        #expect(kept.contains("named block of the new"))
    }

    @Test("the conversation named in a header is read back, and a header without one reads as none")
    func readsConversationFromHeader() {
        let id = UUID()
        let named = ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: [], device: "-", utcOffsetSeconds: 0, conversationID: id)[0]
        let plain = ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: [], device: "-", utcOffsetSeconds: 0)[0]
        #expect(ProblemSnapshot.conversationID(inHeader: named) == id)
        #expect(ProblemSnapshot.conversationID(inHeader: plain) == nil)
        #expect(named.hasPrefix("PROBLEM MARKED: engine "))
    }

    @Test("an older block marked in the quiet after the saved end goes too, while one at the hundredth the next conversation starts in stays")
    func olderBlockInTheQuietAndAtTheBoundary() {
        let journal = SessionJournal(fileURL: temporaryFile())
        let first = summary(from: 1_000.004, to: 1_500)
        mark(journal, "at the very start", at: 1_000.004, conversation: nil)
        mark(journal, "in the quiet", at: 1_800, conversation: nil)
        mark(journal, "in the next hundredth", at: 1_950.0049, conversation: nil)
        mark(journal, "inside the next", at: 1_951, conversation: nil)

        journal.removeCaptionLines(of: first, running: false, nextStartedAt: 1_950.004)

        let kept = journal.entries().map(\.text).joined(separator: "\n")
        #expect(!kept.contains("at the very start"))
        #expect(!kept.contains("in the quiet"))
        #expect(kept.contains("in the next hundredth"))
        #expect(kept.contains("inside the next"))
    }
}
