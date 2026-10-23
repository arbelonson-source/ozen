import Foundation
import Testing
@testable import OzenKit

@Suite("A recording waits for what it needs before it is turned into text")
struct FileTranscriptionGateTests {
    private let audio = FileRunAudio.timeline([(false, 1), (true, 2), (false, 2)])

    private func run(
        _ engine: FileRunEngine,
        allowCellular: Bool = false,
        network: NetworkConditions? = .wifi,
        freeBytes: Int64? = 50_000_000_000,
        reports: FileRunReports = FileRunReports()
    ) async -> FileTranscriptionDriver.Outcome {
        let driver = FileTranscriptionDriver(
            engine: engine,
            languageCode: "he",
            names: [],
            durationSeconds: nil,
            allowCellularDownload: allowCellular,
            score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { network }, freeBytes: { freeBytes }, now: { 0 }),
            retryDelay: .zero
        )
        return await driver.run(source: fileRunSource(audio), report: { reports.add($0) })
    }

    @Test("a download on cellular data that nobody allowed waits for Wi-Fi without asking the engine to prepare")
    func cellular() async {
        let engine = FileRunEngine(pendingMegabytes: 800)
        let outcome = await run(engine, network: .cellular)
        #expect(outcome.result == .failed(.needsWiFi))
        #expect(engine.prepares == 0)
        #expect(engine.streamsOpened == 0)
        let lowData = FileRunEngine(pendingMegabytes: 800)
        let constrained = await run(lowData, network: NetworkConditions(isConnected: true, isConstrained: true))
        #expect(constrained.result == .failed(.needsWiFi))
    }

    @Test("a download with no connection says there is none")
    func offline() async {
        let engine = FileRunEngine(pendingMegabytes: 800)
        let outcome = await run(engine, allowCellular: true, network: .offline)
        #expect(outcome.result == .failed(.offline))
        #expect(engine.prepares == 0)
    }

    @Test("a download that does not fit the phone says so, before anything is fetched")
    func storage() async {
        let engine = FileRunEngine(pendingMegabytes: 800)
        let outcome = await run(engine, freeBytes: 100_000_000)
        #expect(outcome.result == .failed(.notEnoughStorage))
        #expect(engine.prepares == 0)
    }

    @Test("a download allowed on cellular data goes ahead, and the engine is told it may")
    func allowed() async {
        let engine = FileRunEngine(pendingMegabytes: 800) { _, _ in [FileRunEngine.final("ok")] }
        let outcome = await run(engine, allowCellular: true, network: .cellular)
        #expect(engine.prepares == 1)
        #expect(engine.cellularAnswers == [true])
        guard case .finished = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
    }

    @Test("a model already on the phone needs no connection and no room")
    func alreadyThere() async {
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("ok")] }
        let outcome = await run(engine, network: .offline, freeBytes: 0)
        #expect(engine.prepares == 1)
        #expect(engine.cellularAnswers == [false])
        guard case .finished = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
    }

    @Test("what the engine says it could not do becomes a plain problem", arguments: [
        (EngineUnavailability.Kind.notEnoughStorage, FileTranscriptionProblem.notEnoughStorage),
        (.waitingForWiFi, .needsWiFi),
        (.modelLoadFailed, .modelFailed),
        (.modelDownloadFailed, .modelDownloadFailed),
        (.noInternet, .offline),
        (.permissionDenied, .unexpected),
        (.languageNotSupportedOnDevice, .unexpected),
        (.cloudKeyNeeded, .unexpected),
        (.cloudOutOfCredit, .unexpected),
        (.cloudLanguageNotCovered, .unexpected),
        (.homeServerUnreachable, .unexpected),
        (.homeServerRejected, .unexpected),
        (.temporarilyUnavailable, .unexpected),
        (.other, .unexpected),
    ])
    func unavailable(kind: EngineUnavailability.Kind, problem: FileTranscriptionProblem) async {
        let engine = FileRunEngine(availability: .unavailable(kind, "x"))
        let outcome = await run(engine)
        #expect(outcome.result == .failed(problem))
        #expect(FileTranscriptionProblem(EngineUnavailability(kind: kind, detail: "x")) == problem)
        #expect(engine.streamsOpened == 0)
    }

    @Test("a recording that cannot be opened, has no sound, or is cut off says which")
    func readProblems() async {
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("said")] }
        let unreadable = FileTranscriptionDriver(
            engine: engine, languageCode: "he", names: [], durationSeconds: nil, allowCellularDownload: false,
            score: { FileRunAudio.score($0) },
            environment: FileTranscriptionDriver.Environment(network: { .wifi }, freeBytes: { nil }, now: { 0 }), retryDelay: .zero
        )
        let cases: [(RecordingReadProblem, [Float])] = [(.unreadable, []), (.noAudio, []), (.stalled, audio)]
        var results: [FileTranscriptionDriver.Outcome.Result] = []
        for (problem, samples) in cases {
            let outcome = await unreadable.run(source: fileRunSource(samples, endingWith: problem), report: { _ in })
            results.append(outcome.result)
        }
        #expect(results[0] == .failed(.couldNotRead))
        #expect(results[1] == .failed(.noAudio))
        guard case .finished(let lines, let endMissing) = results[2] else {
            Issue.record("\(results[2])")
            return
        }
        #expect(lines.map(\.text) == ["said"])
        #expect(endMissing)
    }

    @Test("the stage shown follows the engine: fetching the model, then listening")
    func stages() async {
        let reports = FileRunReports()
        let engine = FileRunEngine(pendingMegabytes: 800) { _, _ in [FileRunEngine.final("ok")] }
        _ = await run(engine, allowCellular: true, reports: reports)
        let stages = reports.all.map(\.stage)
        #expect(stages.first == .downloading || stages.first == .loading)
        #expect(stages.contains(.downloading))
        #expect(stages.last == .transcribing)
        #expect(reports.all.first { $0.stage == .downloading }?.fraction == 0.5)
    }
}
