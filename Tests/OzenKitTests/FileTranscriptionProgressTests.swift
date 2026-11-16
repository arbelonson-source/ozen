import Foundation
import Testing
@testable import OzenKit

@Suite("How far a recording has come, and how long is left")
struct FileTranscriptionProgressTests {
    @Test("the fraction follows the audio heard, and the time left waits until there is something to go on")
    func progress() async {
        let clock = FileRunClock()
        let reports = FileRunReports()
        let samples = FileRunAudio.timeline([(false, 1), (true, 4), (false, 55)])
        let driver = FileTranscriptionDriver(
            engine: FileRunEngine { _, _ in [FileRunEngine.final("x")] },
            languageCode: "he", names: [], durationSeconds: 60, allowCellularDownload: false,
            score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { nil }, now: { clock.now }),
            retryDelay: .zero
        )
        _ = await driver.run(source: fileRunSource(samples, onPull: { _ in clock.advance(2) }), report: { reports.add($0) })
        func report(after seconds: Int) -> FileTranscriptionDriver.Progress? {
            reports.all.first { $0.stage == .transcribing && abs(($0.fraction ?? -1) - Double(seconds) / 60) < 1e-9 }
        }
        #expect(report(after: 1)?.timeLeftSeconds == nil)
        #expect(report(after: 2)?.timeLeftSeconds == nil)
        let third = report(after: 3)?.timeLeftSeconds
        let sixth = report(after: 6)?.timeLeftSeconds
        #expect(abs((third ?? -1) - 114) < 1e-6)
        #expect(abs((sixth ?? -1) - 108) < 1e-6)
        #expect(reports.all.last?.fraction == 1)
        #expect(reports.all.last?.timeLeftSeconds == 0)
    }

    @Test("the lines found so far are shown while the rest is still being worked on")
    func linesSoFar() async {
        let reports = FileRunReports()
        let samples = FileRunAudio.timeline([(false, 1), (true, 2), (false, 2), (true, 2), (false, 2)])
        let driver = FileTranscriptionDriver(
            engine: FileRunEngine { number, _ in [FileRunEngine.final("line \(number)")] },
            languageCode: "he", names: [], durationSeconds: nil, allowCellularDownload: false,
            score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { nil }, now: { 0 }),
            retryDelay: .zero
        )
        _ = await driver.run(source: fileRunSource(samples), report: { reports.add($0) })
        let counts = reports.all.filter { $0.stage == .transcribing }.map { $0.lines.count }
        #expect(counts.contains(0))
        #expect(counts.contains(1))
        #expect(counts.last == 2)
        #expect(counts == counts.sorted())
        #expect(reports.all.allSatisfy { $0.fraction == nil } || reports.all.contains { $0.stage == .transcribing && $0.fraction == nil })
    }
}

@Suite("A recording that ends before its header says")
struct FileEndsEarlyTests {
    private func early(_ heard: Double, of total: Double?) -> Bool {
        TranscriptionProgress.endsEarly(heardSeconds: heard, totalSeconds: total)
    }

    @Test("only a clear shortfall counts: over 5% and over 10 seconds")
    func rule() {
        #expect(!early(3_600, of: 3_600))
        #expect(!early(3_598, of: 3_600))
        #expect(!early(3_601, of: 3_600))
        #expect(early(2_400, of: 3_600))
        #expect(!early(3_500, of: 3_600))
        #expect(!early(100, of: nil))
        #expect(!early(100, of: 0))
        #expect(!early(3, of: 8))
        #expect(!early(0, of: 10))
        #expect(early(48, of: 60))
        #expect(!early(52, of: 60))
        #expect(early(20, of: 60))
        #expect(!early(55, of: 60))
    }

    private func run(heard: Double, header: Double?) async -> (FileTranscriptionDriver.Outcome, [FileTranscriptionDriver.Progress]) {
        let reports = FileRunReports()
        let driver = FileTranscriptionDriver(
            engine: FileRunEngine { _, _ in [FileRunEngine.final("x")] },
            languageCode: "he", names: [], durationSeconds: header, allowCellularDownload: false,
            score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { nil }, now: { 0 }),
            retryDelay: .zero
        )
        let samples = FileRunAudio.timeline([(false, 1), (true, 2), (false, heard - 3)])
        let outcome = await driver.run(source: fileRunSource(samples), report: { reports.add($0) })
        return (outcome, reports.all)
    }

    private func flag(_ outcome: FileTranscriptionDriver.Outcome) -> Bool? {
        if case .finished(_, let endMissing) = outcome.result { return endMissing }
        return nil
    }

    @Test("a file that stops at 20 of 60 seconds is flagged and never shows 100%")
    func flagged() async {
        let (outcome, reports) = await run(heard: 20, header: 60)
        #expect(flag(outcome) == true)
        #expect((reports.last?.fraction ?? 1) < 0.5)
    }

    @Test("a whole file, a file just inside the margin, one of unknown length and a very short one are not flagged")
    func notFlagged() async {
        let whole = await run(heard: 60, header: 60)
        #expect(flag(whole.0) == false)
        #expect(whole.1.last?.fraction == 1)
        #expect(flag(await run(heard: 58, header: 60).0) == false)
        #expect(flag(await run(heard: 20, header: nil).0) == false)
        #expect(flag(await run(heard: 5, header: 8).0) == false)
    }
}
