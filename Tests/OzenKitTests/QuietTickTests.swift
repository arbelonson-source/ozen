import Foundation
import Testing
@testable import OzenKit

private final class VirtualClock: @unchecked Sendable {
    private let lock = NSLock()
    private var time: TimeInterval = 1_000
    private var slept: [(seconds: TimeInterval, at: TimeInterval)] = []
    var now: TimeInterval { lock.withLock { time } }
    var sleeps: [(seconds: TimeInterval, at: TimeInterval)] { lock.withLock { slept } }
    func begin(_ seconds: TimeInterval) { lock.withLock { slept.append((seconds, time)) } }
    func advance(_ seconds: TimeInterval) { lock.withLock { time += seconds } }
}

@MainActor
private func makeQuietPipeline(
    clock: VirtualClock,
    engine: FakeEngine,
    watchdog: AudioStallWatchdog
) -> CaptionPipeline {
    let pipeline = CaptionPipeline(
        audio: FakeAudioCapturer(),
        engineFactory: { _ in engine },
        embedder: FakeEmbedder(),
        recovery: .disabled,
        audioWatchdog: watchdog,
        now: { clock.now }
    )
    pipeline.staleCommitSleep = { seconds in
        clock.begin(seconds)
        let realSeconds = seconds * 0.1
        let started = ContinuousClock.now
        let deadline = started + .seconds(realSeconds)
        while ContinuousClock.now < deadline, !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(1))
        }
        if Task.isCancelled {
            let fraction = (ContinuousClock.now - started) / .seconds(realSeconds)
            clock.advance(seconds * min(1, fraction))
        } else {
            clock.advance(seconds)
        }
    }
    return pipeline
}

@Suite("Live captions tick less in silence")
@MainActor
struct QuietTickTests {
    @Test("with no line open the checks run once a second", .timeLimit(.minutes(1)))
    func silenceTicksOncePerSecond() async {
        let clock = VirtualClock()
        let pipeline = makeQuietPipeline(clock: clock, engine: FakeEngine(), watchdog: .disabled)
        await pipeline.start(settings: .default)
        #expect(await eventually { clock.sleeps.count >= 8 })
        pipeline.stop()
        #expect(clock.sleeps.allSatisfy { $0.seconds == 1 })
        let times = clock.sleeps.map(\.at)
        #expect(zip(times, times.dropFirst()).allSatisfy { abs($1 - $0 - 1) < 0.01 })
        #expect(clock.now >= 1_000 + 7)
    }

    @Test("with a line open the checks run every quarter second, and the stale line commits as soon as it is due", .timeLimit(.minutes(1)))
    func openLineTicksFast() async {
        let clock = VirtualClock()
        let engine = FakeEngine()
        let pipeline = makeQuietPipeline(clock: clock, engine: engine, watchdog: .disabled)
        await pipeline.start(settings: .default)
        #expect(await eventually { clock.sleeps.count >= 2 })
        let opened = clock.now
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "hello", isFinal: false, timestamp: opened))
        #expect(await eventually { pipeline.stats.hasOpenLine })
        let sleepsBefore = clock.sleeps.count
        #expect(await eventually { pipeline.segments.first?.isCommitted == true })
        let committedAt = clock.now
        pipeline.stop()
        let during = clock.sleeps.dropFirst(sleepsBefore + 1).filter { $0.at < opened + CaptionStabilizer.defaultSilenceCommitThreshold }
        #expect(!during.isEmpty)
        #expect(during.allSatisfy { $0.seconds == 0.25 })
        let wait = committedAt - opened
        #expect(wait >= CaptionStabilizer.defaultSilenceCommitThreshold)
        #expect(wait <= CaptionStabilizer.defaultSilenceCommitThreshold + 0.25 + 0.3)
    }

    @Test("a line that opens during a one second sleep wakes the loop at once", .timeLimit(.minutes(1)))
    func lineOpeningWakesTheLoop() async {
        let clock = VirtualClock()
        let engine = FakeEngine()
        let pipeline = makeQuietPipeline(clock: clock, engine: engine, watchdog: .disabled)
        await pipeline.start(settings: .default)
        #expect(await eventually { clock.sleeps.count >= 2 })
        let count = clock.sleeps.count
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "hello", isFinal: false, timestamp: clock.now))
        #expect(await eventually { clock.sleeps.count >= count + 3 })
        let next = clock.sleeps[count...]
        pipeline.stop()
        #expect(next.contains { $0.seconds == 0.25 })
        #expect(next.first { $0.seconds == 0.25 }.map { $0.at - clock.sleeps[count - 1].at } ?? 9 < 1)
    }

    @Test("a microphone that stops sending audio is reported after the same stall limit in a one second rhythm", .timeLimit(.minutes(1)))
    func stallInQuietRhythm() async {
        let clock = VirtualClock()
        let pipeline = makeQuietPipeline(clock: clock, engine: FakeEngine(), watchdog: AudioStallWatchdog(stallSeconds: 6))
        await pipeline.start(settings: .default)
        let start = clock.now
        #expect(await eventually { pipeline.stats.audioStalls == 1 })
        let noticedAt = clock.now
        pipeline.stop()
        let after = noticedAt - start
        #expect(after >= 6)
        #expect(after <= 7.25)
    }

    @Test("a line opening in the middle of a stall changes neither the limit nor the report", .timeLimit(.minutes(1)))
    func stallWithLineOpeningMidway() async {
        let clock = VirtualClock()
        let engine = FakeEngine()
        let pipeline = makeQuietPipeline(clock: clock, engine: engine, watchdog: AudioStallWatchdog(stallSeconds: 6))
        await pipeline.start(settings: .default)
        let start = clock.now
        #expect(await eventually { clock.now >= start + 2.5 })
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "late", isFinal: false, timestamp: clock.now))
        #expect(await eventually { pipeline.stats.audioStalls == 1 })
        let noticedAt = clock.now
        pipeline.stop()
        let after = noticedAt - start
        #expect(after >= 6)
        #expect(after <= 7.25)
        #expect(clock.sleeps.contains { $0.seconds == 0.25 })
    }
}
