import Foundation
import Testing
@testable import OzenKit

@Suite("ProblemSnapshot")
struct ProblemSnapshotTests {
    @Test("says which engine, model and microphone were running, and what the captions last said")
    func lines() {
        var settings = AppSettings.default
        settings.whisperModelVariant = "small"
        var stats = PipelineStats()
        stats.engineRestarts = 2
        let segments = (1...6).map { index in
            TranscriptSegment(id: UUID(), text: "line \(index)", isCommitted: index < 6, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 0)
        }
        let lines = ProblemSnapshot.lines(
            settings: settings,
            activeEngine: .whisperKit,
            input: AudioInputDescriptor(uid: "bt", portName: "AirPods", portType: .bluetooth),
            stats: stats,
            segments: segments,
            device: "thermal 1",
            utcOffsetSeconds: 0
        )
        #expect(lines[0] == "PROBLEM MARKED: engine whisperKit model small lang he microphone AirPods [bluetooth]")
        #expect(lines[1].contains("restarts 2"))
        #expect(lines[2] == "  thermal 1")
        #expect(lines.count == 3 + ProblemSnapshot.lineCount)
        #expect(lines.last == "  line (live, sure -, 00:00:00): line 6")
        #expect(lines[3].hasSuffix("line 3"))
    }

    @Test("a line held to mark the problem goes in too, after the newest four, marked as held")
    func heldOlderLine() {
        let segments = (1...8).map { index in
            TranscriptSegment(id: UUID(), text: "line \(index)", isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 0)
        }
        let lines = ProblemSnapshot.lines(
            settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, held: segments[1], device: "-", utcOffsetSeconds: 0
        )
        #expect(lines.count == 3 + ProblemSnapshot.lineCount + 1)
        #expect(lines.last == "  line (held, final, sure -, 00:00:00): line 2")
        #expect(ProblemSnapshot.isCaptionLine(lines.last ?? ""))
        #expect(lines[3].hasSuffix("line 5"))
    }

    @Test("a held line among the newest four is marked where it is, not added twice")
    func heldNewestLine() {
        let segments = (1...6).map { index in
            TranscriptSegment(id: UUID(), text: "line \(index)", isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 0)
        }
        let lines = ProblemSnapshot.lines(
            settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, held: segments[4], device: "-", utcOffsetSeconds: 0
        )
        #expect(lines.count == 3 + ProblemSnapshot.lineCount)
        #expect(lines.filter { $0.contains("held") } == ["  line (held, final, sure -, 00:00:00): line 5"])
    }

    @Test("each line carries its own last-update clock time, at her offset, not the moment the problem was marked")
    func lineTimestamps() {
        let segments = (1...4).map { index in
            TranscriptSegment(id: UUID(), text: "line \(index)", isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: TimeInterval(index * 60))
        }
        let lines = ProblemSnapshot.lines(
            settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: segments, device: "-", utcOffsetSeconds: 3 * 3_600
        )
        #expect(lines[3] == "  line (final, sure -, 03:01:00): line 1")
        #expect(lines.last == "  line (final, sure -, 03:04:00): line 4")
    }

    @Test("the phone's own model covering for the cloud is the model named")
    func modelOfTheRunningEngine() {
        var settings = AppSettings.default
        settings.engine = .cloud
        settings.cloudModel = "cloud-model-x"
        settings.whisperModelVariant = "small"
        let lines = ProblemSnapshot.lines(
            settings: settings, activeEngine: .whisperKit, input: nil, stats: PipelineStats(), segments: [], device: "-", utcOffsetSeconds: 0
        )
        #expect(lines[0] == "PROBLEM MARKED: engine whisperKit model small lang he microphone -")
    }

    @Test("with nothing running and nothing said there is still a line to find")
    func empty() {
        let lines = ProblemSnapshot.lines(settings: .default, activeEngine: nil, input: nil, stats: PipelineStats(), segments: [], device: "-", utcOffsetSeconds: 0)
        #expect(lines.count == 3)
        #expect(lines[0].hasSuffix("microphone -"))
    }
}
