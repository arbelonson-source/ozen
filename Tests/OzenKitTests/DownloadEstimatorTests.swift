import Foundation
import Testing
@testable import OzenKit

@Suite("DownloadEstimator")
struct DownloadEstimatorTests {
    @Test("a steady download is estimated from its pace")
    func steady() throws {
        var estimator = DownloadEstimator()
        // 1% a second.
        for second in 0...10 {
            estimator.record(fraction: Double(second) / 100, at: 1_000 + Double(second))
        }
        let left = try #require(estimator.secondsRemaining())
        #expect(abs(left - 90) < 0.5)
    }

    @Test("no estimate in the first seconds, or while nothing moves")
    func notYet() {
        var estimator = DownloadEstimator()
        #expect(estimator.secondsRemaining() == nil)
        estimator.record(fraction: 0.1, at: 0)
        estimator.record(fraction: 0.12, at: 3)
        #expect(estimator.secondsRemaining() == nil)

        var stalled = DownloadEstimator()
        stalled.record(fraction: 0.4, at: 0)
        stalled.record(fraction: 0.4, at: 20)
        #expect(stalled.secondsRemaining() == nil)
    }

    @Test("the estimate follows the recent pace, not the average since the start")
    func recentPace() throws {
        var estimator = DownloadEstimator()
        // Two minutes at 0.1% a second on a poor connection...
        for second in stride(from: 0, through: 120, by: 5) {
            estimator.record(fraction: Double(second) / 1_000, at: Double(second))
        }
        // ...then half a minute at 1% a second on better Wi-Fi.
        for second in stride(from: 125, through: 160, by: 5) {
            estimator.record(fraction: 0.12 + Double(second - 120) / 100, at: Double(second))
        }
        let left = try #require(estimator.secondsRemaining())
        // 48% left at 1%/s is 48 s; the whole-download average would say several minutes.
        #expect(left < 60)
        #expect(left > 40)
    }

    @Test("a stall longer than the window doesn't leave the estimate anchored to pre-stall progress")
    func recoversFromAStall() throws {
        var estimator = DownloadEstimator()
        estimator.record(fraction: 0.1, at: 0)
        // A ten-minute stall -- no progress reported at all.
        estimator.record(fraction: 0.11, at: 600)
        estimator.record(fraction: 0.5, at: 605)
        let left = try #require(estimator.secondsRemaining())
        // True recent pace is (0.5-0.11)/5 ≈ 7.8%/s, about 6.4s left.
        // Anchored to the stale sample from before the stall instead, the
        // span balloons to 605s and the estimate to well over ten minutes.
        #expect(left < 15)
    }

    @Test("a resumed download's first reading is where it picked up, not how fast it goes")
    func resumedJump() throws {
        var estimator = DownloadEstimator()
        // Announced at 0%, then the part already on the phone: 57%.
        estimator.record(fraction: 0, at: 0)
        estimator.record(fraction: 0.57, at: 1)
        // Then 0.1% a second.
        for second in 2...6 {
            estimator.record(fraction: 0.57 + Double(second - 1) / 1_000, at: Double(second))
        }
        let left = try #require(estimator.secondsRemaining())
        #expect(abs(left - 425) < 5)
    }

    @Test("silence counts as news only when it is much longer than the usual gap between reports")
    func quietLimit() {
        var estimator = DownloadEstimator()
        #expect(estimator.quietLimit(floor: 30, gaps: 3) == 30)
        for second in stride(from: 0, through: 60, by: 20) {
            estimator.record(fraction: Double(second) / 1_000, at: Double(second))
        }
        #expect(estimator.quietLimit(floor: 30, gaps: 3) == 60)
        #expect(estimator.quietLimit(floor: 90, gaps: 3) == 90)

        var quick = DownloadEstimator()
        for second in 0...10 {
            quick.record(fraction: Double(second) / 100, at: Double(second))
        }
        #expect(quick.quietLimit(floor: 30, gaps: 3) == 30)
    }

    @Test("a download that starts over starts the estimate over")
    func restart() throws {
        var estimator = DownloadEstimator()
        estimator.record(fraction: 0.5, at: 0)
        estimator.record(fraction: 0.6, at: 10)
        estimator.record(fraction: 0.0, at: 11)
        estimator.record(fraction: 0.01, at: 13)
        #expect(estimator.secondsRemaining() == nil)
        // The new download's own pace, not the old one's higher readings.
        estimator.record(fraction: 0.11, at: 23)
        let left = try #require(estimator.secondsRemaining())
        #expect(abs(left - 89) < 0.5)
    }

    @Test("reset forgets the pace, so a new download is timed afresh")
    func resetForgets() {
        var estimator = DownloadEstimator()
        estimator.record(fraction: 0.1, at: 0)
        estimator.record(fraction: 0.2, at: 10)
        #expect(estimator.secondsRemaining() != nil)
        estimator.reset()
        #expect(estimator.secondsRemaining() == nil)
    }
}

@Suite("CaptionPipeline download time left")
@MainActor
struct CaptionPipelineDownloadEstimateTests {
    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var time: TimeInterval = 1_000
        func tick() -> TimeInterval {
            lock.withLock {
                time += 3
                return time
            }
        }
    }

    private final class Engines: @unchecked Sendable {
        var current: FakeEngine
        init(_ engine: FakeEngine) { current = engine }
    }

    @Test("the time left goes away when progress stops coming, as while the model is set up at the end", .timeLimit(.minutes(1)))
    func quietDropsTimeLeft() async {
        let engine = FakeEngine(progressUpdates: [0.1, 0.2, 0.3, 0.4].map {
            EnginePreparationProgress(stage: .downloadingModel, fraction: $0)
        })
        let gate = PrepareGate()
        engine.afterProgressGate = gate
        let clock = Clock()
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engine },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            audioWatchdog: .disabled,
            now: { clock.tick() }
        )
        pipeline.downloadQuietSeconds = 0.3
        pipeline.downloadQuietGaps = 0
        let start = Task { await pipeline.start(settings: .default) }
        #expect(await eventually { pipeline.downloadSecondsRemaining != nil })
        #expect(await eventually(within: .seconds(5)) { pipeline.downloadSecondsRemaining == nil })
        await gate.open()
        await start.value
    }

    @Test("a start waiting on an abandoned download keeps that download's time left up to date", .timeLimit(.minutes(1)))
    func waitingStartKeepsTimeLeft() async {
        let engine = FakeEngine(progressUpdates: [0.1, 0.2, 0.3, 0.4].map {
            EnginePreparationProgress(stage: .downloadingModel, fraction: $0)
        })
        let held = PrepareGate()
        let rest = PrepareGate()
        engine.prepareGate = held
        engine.afterProgressGate = rest
        let clock = Clock()
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engine },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            audioWatchdog: .disabled,
            now: { clock.tick() }
        )
        let first = Task { await pipeline.start(settings: .default) }
        while engine.prepareCount == 0 { await Task.yield() }
        pipeline.stop()
        let second = Task { await pipeline.start(settings: .default) }
        for _ in 0..<50 { await Task.yield() }
        await held.open()
        let last = EnginePreparationProgress(stage: .downloadingModel, fraction: 0.4)
        #expect(await eventually { pipeline.phase == .preparingEngine(last) })
        #expect(pipeline.downloadSecondsRemaining != nil)
        await rest.open()
        await first.value
        await second.value
    }

    @Test("a download that keeps reporting keeps its time left", .timeLimit(.minutes(1)))
    func reportingKeepsTimeLeft() async {
        let updates = (1...40).map { EnginePreparationProgress(stage: .downloadingModel, fraction: Double($0) / 100) }
        let engine = FakeEngine(progressUpdates: updates)
        engine.progressSpacing = .milliseconds(50)
        let gate = PrepareGate()
        engine.afterProgressGate = gate
        let clock = Clock()
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engine },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            audioWatchdog: .disabled,
            now: { clock.tick() }
        )
        pipeline.downloadQuietSeconds = 1.5
        pipeline.downloadQuietGaps = 0
        let start = Task { await pipeline.start(settings: .default) }
        #expect(await eventually { pipeline.downloadSecondsRemaining != nil })
        var dropped = false
        while pipeline.phase != .preparingEngine(updates[updates.count - 1]) {
            if pipeline.downloadSecondsRemaining == nil { dropped = true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        #expect(!dropped)
        await gate.open()
        await start.value
    }

    @Test("a model download reports how long it has left, and a new start times it afresh")
    func estimatesDownload() async {
        let downloading = FakeEngine(progressUpdates: [0.1, 0.2, 0.3, 0.4].map {
            EnginePreparationProgress(stage: .downloadingModel, fraction: $0)
        })
        let engines = Engines(downloading)
        let clock = Clock()
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engines.current },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            audioWatchdog: .disabled,
            now: { clock.tick() }
        )
        var left: Double?
        downloading.duringPrepare = { left = pipeline.downloadSecondsRemaining }
        await pipeline.start(settings: .default)
        #expect((left ?? 0) > 0)

        // Another model, already on the phone: nothing left over from before.
        pipeline.stop()
        let ready = FakeEngine(kind: .appleSpeech)
        engines.current = ready
        left = -1
        ready.duringPrepare = { left = pipeline.downloadSecondsRemaining }
        var settings = AppSettings.default
        settings.engine = .appleSpeech
        await pipeline.start(settings: settings)
        #expect(left == nil)
    }
}

