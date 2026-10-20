import Foundation
import Testing
@testable import OzenKit

@Suite("Turning a recording into text, stretch by stretch")
struct FileTranscriptionDriverTests {
    private let frame = FileRunAudio.frame
    private let gigabytes: Int64 = 1_000_000_000

    private func driver(
        _ engine: FileRunEngine,
        names: [String] = [],
        duration: Double? = nil,
        allowCellular: Bool = true,
        network: NetworkConditions? = .wifi,
        freeBytes: Int64? = 50 * 1_000_000_000,
        clock: FileRunClock = FileRunClock(),
        score: @escaping FileTranscriptionDriver.Score = { FileRunAudio.score($0) }
    ) -> FileTranscriptionDriver {
        FileTranscriptionDriver(
            engine: engine,
            languageCode: "he",
            names: names,
            durationSeconds: duration,
            allowCellularDownload: allowCellular,
            score: score,
            environment: FileTranscriptionDriver.Environment(network: { network }, freeBytes: { freeBytes }, now: { clock.now }),
            retryDelay: .zero
        )
    }

    private let twoSentences = FileRunAudio.timeline([(false, 1), (true, 3), (false, 2), (true, 2), (false, 4)])

    @Test("each stretch gets a stream of its own, padded by a fifth of a second, fed in pieces of at most 4,096 samples")
    func oneStreamPerStretch() async {
        let engine = FileRunEngine { number, _ in [FileRunEngine.final("sentence \(number)")] }
        let outcome = await driver(engine).run(source: fileRunSource(twoSentences), report: { _ in })
        #expect(engine.streamsOpened == 2)
        #expect(engine.windows.count == 2)
        guard engine.windows.count == 2 else { return }
        #expect(engine.windows[0] == Array(twoSentences[9_088..<68_736]))
        #expect(engine.windows[1] == Array(twoSentences[91_008..<134_272]))
        for sizes in engine.pieceSizes {
            #expect(sizes.allSatisfy { $0 <= VoiceEvidence.chunkSamples })
            #expect(sizes.dropLast().allSatisfy { $0 == VoiceEvidence.chunkSamples })
        }
        let first = TranscribedLine(startSeconds: 3 * frame, endSeconds: 16 * frame, text: "sentence 1")
        let second = TranscribedLine(startSeconds: 23 * frame, endSeconds: 32 * frame, text: "sentence 2")
        #expect(outcome.result == .finished(lines: [first, second], endMissing: false))
    }

    @Test("only the final tokens count, several are joined in order, and an empty reply gives no line")
    func tokens() async {
        let engine = FileRunEngine { number, _ in
            switch number {
            case 1: return [FileRunEngine.preview("par"), FileRunEngine.final("part one"), FileRunEngine.preview("pa"), FileRunEngine.final("part two")]
            default: return [FileRunEngine.preview("only a preview")]
            }
        }
        let outcome = await driver(engine).run(source: fileRunSource(twoSentences), report: { _ in })
        #expect(engine.streamsOpened == 2)
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
        #expect(lines.map(\.text) == ["part one part two"])
    }

    @Test("speech running to the very end of the file is still turned into text")
    func speechToTheEnd() async {
        let samples = FileRunAudio.timeline([(false, 1), (true, 2)])
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("to the end")] }
        let outcome = await driver(engine).run(source: fileRunSource(samples), report: { _ in })
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
        #expect(lines.map(\.text) == ["to the end"])
    }

    @Test("a recording without any speech in it says so")
    func noSpeech() async {
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("never")] }
        let outcome = await driver(engine).run(source: fileRunSource(FileRunAudio.quiet(5)), report: { _ in })
        #expect(outcome.result == .failed(.noSpeech))
        #expect(engine.streamsOpened == 0)
        let allEmpty = FileRunEngine { _, _ in [] }
        let empty = await driver(allEmpty).run(source: fileRunSource(twoSentences), report: { _ in })
        #expect(empty.result == .failed(.noSpeech))
    }

    @Test("a voice model that does not answer fails the run")
    func scorerGone() async {
        let engine = FileRunEngine()
        let outcome = await driver(engine, score: { _ in nil }).run(source: fileRunSource(twoSentences), report: { _ in })
        #expect(outcome.result == .failed(.modelFailed))
    }

    @Test("a stream that fails once is tried again, and one that fails twice ends the run")
    func retry() async {
        let flaky = FileRunEngine { number, _ in
            if number == 1 { throw FileRunFailure() }
            return [FileRunEngine.final("second try \(number)")]
        }
        let samples = FileRunAudio.timeline([(false, 1), (true, 2), (false, 2)])
        let outcome = await driver(flaky).run(source: fileRunSource(samples), report: { _ in })
        #expect(flaky.streamsOpened == 2)
        guard case .finished(let lines, _) = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
        #expect(lines.map(\.text) == ["second try 2"])
        let broken = FileRunEngine { _, _ in throw FileRunFailure() }
        let failed = await driver(broken).run(source: fileRunSource(samples), report: { _ in })
        #expect(failed.result == .failed(.unexpected))
        #expect(broken.streamsOpened == 2)
    }

    @Test("stopping after the second stretch opens no third stream and reports it was cancelled")
    func cancel() async {
        let samples = FileRunAudio.timeline([(false, 1), (true, 2), (false, 2), (true, 2), (false, 2), (true, 2), (false, 2), (true, 2), (false, 2)])
        let box = FileRunTaskBox()
        let engine = FileRunEngine { number, _ in
            if number == 2 { box.cancel() }
            return [FileRunEngine.final("line \(number)")]
        }
        let run = driver(engine)
        let task = Task { await run.run(source: fileRunSource(samples), report: { _ in }) }
        box.set(task)
        let outcome = await task.value
        #expect(outcome.result == .cancelled)
        #expect(engine.streamsOpened == 2)
    }

    @Test("the audio kept in memory stays near one stretch however long the recording is")
    func memory() async {
        let cap = WhisperKitDecodeRoom.longestLineSeconds(promptTokens: 0, upTo: 28)
        let engine = FileRunEngine { _, _ in [FileRunEngine.final("x")] }
        let longRun = 45
        let source = fileRunSource(pieces: 600) { second in
            let phase = second % 60
            let voiced = phase < 5 || (phase >= 10 && phase < 10 + longRun && second / 60 == 3) || (phase >= 20 && phase < 28)
            return voiced ? FileRunAudio.tone(1, from: second * FileRunAudio.rate) : FileRunAudio.quiet(1)
        }
        let outcome = await driver(engine).run(source: source, report: { _ in })
        let bound = Int((cap + 2 * 0.2) * Double(FileRunAudio.rate)) + FileRunAudio.rate
        #expect(outcome.peakBufferedSamples > Int(cap * Double(FileRunAudio.rate)) / 2)
        #expect(outcome.peakBufferedSamples < bound, "\(outcome.peakBufferedSamples) of \(bound)")
        #expect(engine.streamsOpened > 18)
        guard case .finished = outcome.result else {
            Issue.record("\(outcome.result)")
            return
        }
    }

    @Test("the names reach the engine before the first stream, and leave less room for each stretch")
    func names() async {
        let samples = FileRunAudio.timeline([(true, 60), (false, 2)])
        let plain = FileRunEngine { _, _ in [FileRunEngine.final("x")] }
        _ = await driver(plain).run(source: fileRunSource(samples), report: { _ in })
        let named = FileRunEngine { _, _ in [FileRunEngine.final("x")] }
        _ = await driver(named, names: ["Dana", "Omer"]).run(source: fileRunSource(samples), report: { _ in })
        #expect(!plain.events.contains("vocabulary"))
        #expect(named.vocabularies == [["Dana", "Omer"]])
        #expect((named.events.firstIndex(of: "vocabulary") ?? .max) < (named.events.firstIndex(of: "stream") ?? 0))
        let rate = Double(FileRunAudio.rate)
        let shortCap = WhisperKitDecodeRoom.longestLineSeconds(promptTokens: WhisperKitDecodeRoom.maxPromptTokens, upTo: 28)
        let longCap = WhisperKitDecodeRoom.longestLineSeconds(promptTokens: 0, upTo: 28)
        #expect(named.windows.map(\.count).max() ?? .max <= Int((shortCap + 0.4) * rate))
        #expect(plain.windows.map(\.count).max() ?? 0 > Int((shortCap + 0.4) * rate))
        #expect(plain.windows.map(\.count).max() ?? .max <= Int((longCap + 0.4) * rate))
        #expect(named.streamsOpened > plain.streamsOpened)
    }
}
