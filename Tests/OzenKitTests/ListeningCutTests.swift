import Foundation
import Testing
@testable import OzenKit

private final class MemoryStore: ListeningRecordStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?

    init(_ value: String? = nil) { stored = value }

    var value: String? {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }

    func read() -> String? { value }
    func write(_ value: String) { self.value = value }
    func clear() { value = nil }
}

private final class DirectExecutor: ListeningExecutor {
    func async(_ job: @escaping @Sendable () -> Void) { job() }
    func sync(_ job: @Sendable () -> Void) { job() }
}

private final class DeferredExecutor: ListeningExecutor, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [@Sendable () -> Void] = []

    func async(_ job: @escaping @Sendable () -> Void) { lock.withLock { pending.append(job) } }

    func sync(_ job: @Sendable () -> Void) {
        drain()
        job()
    }

    func drain() {
        let jobs = lock.withLock { () -> [@Sendable () -> Void] in
            defer { pending = [] }
            return pending
        }
        jobs.forEach { $0() }
    }
}

private let day = 1_700_000_000.0 - 1_700_000_000.0.truncatingRemainder(dividingBy: 86_400)
private func at(_ hours: Int, _ minutes: Int) -> Double { day + Double(hours * 3_600 + minutes * 60) }
private let version = 7
private let failure = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")

private final class Clock: @unchecked Sendable {
    var time = at(0, 30)
}

@Suite("ListeningCut")
struct ListeningCutTests {
    private let store = MemoryStore()
    private let clock = Clock()

    private func recorder(_ executor: any ListeningExecutor = DirectExecutor()) -> ListeningRecorder {
        let clock = clock
        return ListeningRecorder(store: store, version: version, now: { clock.time }, executor: executor)
    }

    private func record(_ alive: Double) -> ListeningCut.Record {
        ListeningCut.Record(startedAt: at(0, 30), aliveAt: alive, version: version)
    }

    @Test("listening cut at 01:10 and seen at 04:20 gives a notice about 01:10 and the length")
    func noticeTimeAndLength() throws {
        let notice = try #require(ListeningCut.notice(record(at(1, 10)), now: at(4, 20), version: version))
        #expect(notice.stoppedAt == at(1, 10))
        #expect(notice.offSeconds == 3 * 3_600 + 10 * 60)
        let text = Localization.$override.withValue(.english) { notice.text(timeZone: .gmt) }
        #expect(text.contains("1:10"), Comment(rawValue: text))
        #expect(text.contains("3 hours 10 minutes"), Comment(rawValue: text))
        #expect(text.hasPrefix("Listening stopped at about "))
        #expect(text.hasSuffix("No alerts could come through during that time."))
    }

    @Test("a gap of two minutes or less is a quick restart and says nothing")
    func shortGap() {
        let alive = record(at(1, 10))
        #expect(ListeningCut.notice(alive, now: at(1, 10) + 119, version: version) == nil)
        #expect(ListeningCut.notice(alive, now: at(1, 10) + 120, version: version) == nil)
        #expect(ListeningCut.notice(alive, now: at(1, 10) + 121, version: version) != nil)
    }

    @Test("a record from another app version, or none, says nothing")
    func otherVersionOrNone() {
        #expect(ListeningCut.notice(record(at(1, 10)), now: at(4, 20), version: version + 1) == nil)
        #expect(ListeningCut.notice(nil, now: at(4, 20), version: version) == nil)
    }

    @Test("a corrupt record or a time in the future says nothing and does not crash")
    func corruptOrFuture() {
        for raw in ["", "garbage", "listening 1", "listening 1 2", "listening a b 7", "listening NaN NaN 7", "listening -5 -5 7", "listening 9 3 7", "listening 1 2 7 8"] {
            #expect(ListeningCut.notice(ListeningCut.parse(raw), now: at(4, 20), version: version) == nil, Comment(rawValue: raw))
        }
        #expect(ListeningCut.parse(nil) == nil)
        let future = ListeningCut.Record(startedAt: at(5, 0), aliveAt: at(6, 0), version: version)
        #expect(ListeningCut.notice(future, now: at(4, 20), version: version) == nil)
    }

    @Test("a record survives being written and read back")
    func roundTrip() {
        let original = record(at(1, 10))
        #expect(ListeningCut.parse(ListeningCut.encode(original)) == original)
    }

    @Test("listening starting writes a record of when it started")
    func startWrites() {
        recorder().phaseChanged(.listening, recovering: false)
        #expect(ListeningCut.parse(store.value) == ListeningCut.Record(startedAt: at(0, 30), aliveAt: at(0, 30), version: version))
    }

    @Test("the keep-alive tick moves the alive time and keeps the start")
    func tickMovesAlive() {
        let recorder = recorder()
        recorder.phaseChanged(.listening, recovering: false)
        clock.time = at(1, 10)
        recorder.keepAlive()
        #expect(ListeningCut.parse(store.value) == record(at(1, 10)))
    }

    @Test("a tick while nothing listens writes nothing")
    func tickIdle() {
        recorder().keepAlive()
        #expect(store.value == nil)
    }

    @Test("a tick after the listening ended does not bring the record back")
    func tickAfterEnd() {
        let recorder = recorder()
        recorder.phaseChanged(.listening, recovering: false)
        recorder.phaseChanged(.idle, recovering: false)
        recorder.keepAlive()
        #expect(store.value == nil)
    }

    @Test("stop, pause and a failure that is reported each clear the record")
    func normalEnds() {
        for end in [PipelinePhase.idle, .paused, .failed(failure)] {
            let recorder = recorder()
            recorder.phaseChanged(.listening, recovering: false)
            #expect(store.value != nil)
            recorder.phaseChanged(end, recovering: false)
            #expect(store.value == nil, Comment(rawValue: "\(end)"))
        }
    }

    @Test("steps of a start and a failure that retries by itself keep the record")
    func keepsRecord() {
        let steps: [(PipelinePhase, Bool)] = [
            (.preparingEngine(EnginePreparationProgress(stage: .downloadingModel)), false),
            (.requestingMicrophonePermission, false),
            (.startingAudio, false),
            (.failed(failure), true),
        ]
        for (phase, recovering) in steps {
            let recorder = recorder()
            recorder.phaseChanged(.listening, recovering: false)
            recorder.phaseChanged(phase, recovering: recovering)
            #expect(store.value != nil, Comment(rawValue: "\(phase)"))
        }
    }

    @Test("listening again after a stop starts a fresh record")
    func freshAfterStop() {
        let recorder = recorder()
        recorder.phaseChanged(.listening, recovering: false)
        recorder.phaseChanged(.idle, recovering: false)
        clock.time = at(2, 0)
        recorder.phaseChanged(.listening, recovering: false)
        #expect(ListeningCut.parse(store.value) == ListeningCut.Record(startedAt: at(2, 0), aliveAt: at(2, 0), version: version))
    }

    @Test("taking reads the leftover record, gives its notice and clears it")
    func takeClears() {
        store.value = ListeningCut.encode(record(at(1, 10)))
        clock.time = at(4, 20)
        #expect(recorder().take()?.stoppedAt == at(1, 10))
        #expect(store.value == nil)
    }

    @Test("a quick restart keeps the record, so a later start still tells the cut from the real last-alive time")
    func quickRestartKeeps() {
        store.value = ListeningCut.encode(record(at(1, 10)))
        clock.time = at(1, 11)
        #expect(recorder().take() == nil)
        #expect(store.value != nil)
        clock.time = at(4, 20)
        #expect(recorder().take()?.stoppedAt == at(1, 10))
        #expect(store.value == nil)
    }

    @Test("listening again after a quick restart writes a fresh record")
    func quickRestartThenListening() {
        store.value = ListeningCut.encode(record(at(1, 10)))
        clock.time = at(1, 11)
        let recorder = recorder()
        #expect(recorder.take() == nil)
        recorder.phaseChanged(.listening, recovering: false)
        #expect(ListeningCut.parse(store.value)?.startedAt == at(1, 11))
    }

    @Test("a record from another version is cleared even right after it was written")
    func otherVersionCleared() {
        var old = record(at(1, 10))
        old.version -= 1
        store.value = ListeningCut.encode(old)
        clock.time = at(1, 11)
        #expect(recorder().take() == nil)
        #expect(store.value == nil)
    }

    @Test("taking a corrupt record gives nothing and clears it")
    func takeCorrupt() {
        store.value = "garbage"
        #expect(recorder().take() == nil)
        #expect(store.value == nil)
    }

    @Test("ending right now clears the record before returning, without waiting for the queue")
    func endNowDoesNotWait() {
        let executor = DeferredExecutor()
        let recorder = recorder(executor)
        recorder.phaseChanged(.listening, recovering: false)
        executor.drain()
        #expect(store.value != nil)
        recorder.endNow()
        #expect(store.value == nil)
    }

    @Test("a second listening report keeps the original start")
    func secondListeningKeepsStart() {
        let recorder = recorder()
        recorder.phaseChanged(.listening, recovering: false)
        clock.time = at(1, 10)
        recorder.keepAlive()
        clock.time = at(1, 20)
        recorder.phaseChanged(.listening, recovering: false)
        #expect(ListeningCut.parse(store.value) == record(at(1, 10)))
    }

    @Test("each release and build gets its own number, and odd values never crash")
    func versionNumbers() {
        let first = ListeningCut.versionNumber(shortVersion: "0.3.0", build: "2")
        #expect(ListeningCut.versionNumber(shortVersion: "0.3.0", build: "3") > first)
        #expect(ListeningCut.versionNumber(shortVersion: "0.3.1", build: "2") > first)
        #expect(ListeningCut.versionNumber(shortVersion: "0.4.0", build: "1") > first)
        #expect(ListeningCut.versionNumber(shortVersion: "1.0.0", build: "1") > first)
        #expect(ListeningCut.versionNumber(shortVersion: "0.3.0", build: "2") == first)
        _ = ListeningCut.versionNumber(shortVersion: nil, build: nil)
        _ = ListeningCut.versionNumber(shortVersion: "x.y", build: "99999999999999999999")
        _ = ListeningCut.versionNumber(shortVersion: "1.2.3.4.5", build: "-4")
    }
}
