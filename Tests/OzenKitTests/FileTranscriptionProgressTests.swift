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
