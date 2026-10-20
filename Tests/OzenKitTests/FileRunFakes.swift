import Foundation
@testable import OzenKit

final class FileRunClock: @unchecked Sendable {
    private let lock = NSLock()
    private var seconds = 0.0

    var now: Double { lock.withLock { seconds } }

    func advance(_ by: Double) { lock.withLock { seconds += by } }
}

struct FileRunFailure: Error, Equatable {}

final class FileRunTaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var task: Task<FileTranscriptionDriver.Outcome, Never>?
    private var cancelRequested = false

    func set(_ task: Task<FileTranscriptionDriver.Outcome, Never>) {
        let cancelNow = lock.withLock { () -> Bool in
            self.task = task
            return cancelRequested
        }
        if cancelNow { task.cancel() }
    }

    func cancel() {
        let running = lock.withLock { () -> Task<FileTranscriptionDriver.Outcome, Never>? in
            cancelRequested = true
            return task
        }
        running?.cancel()
    }
}

final class FileRunReports: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [FileTranscriptionDriver.Progress] = []

    var all: [FileTranscriptionDriver.Progress] { lock.withLock { stored } }

    func add(_ progress: FileTranscriptionDriver.Progress) { lock.withLock { stored.append(progress) } }
}

final class FileRunEngine: TranscriptionEngine, @unchecked Sendable {
    typealias Reply = @Sendable (_ stream: Int, _ audio: [Float]) throws -> [TranscriptToken]

    let kind: TranscriptionEngineKind = .whisperKit
    private let lock = NSLock()
    private let reply: Reply
    private let availability: EngineAvailability
    private let download: Int?
    private var logged: [String] = []
    private var audioHeard: [[Float]] = []
    private var piecesHeard: [[Int]] = []
    private var cellularAsked: [Bool] = []
    private var vocabularySet: [[String]] = []

    init(availability: EngineAvailability = .available, pendingMegabytes: Int? = nil, reply: @escaping Reply = { _, _ in [] }) {
        self.availability = availability
        self.download = pendingMegabytes
        self.reply = reply
    }

    var events: [String] { lock.withLock { logged } }
    var windows: [[Float]] { lock.withLock { audioHeard } }
    var pieceSizes: [[Int]] { lock.withLock { piecesHeard } }
    var cellularAnswers: [Bool] { lock.withLock { cellularAsked } }
    var vocabularies: [[String]] { lock.withLock { vocabularySet } }
    var streamsOpened: Int { events.filter { $0 == "stream" }.count }
    var prepares: Int { events.filter { $0 == "prepare" }.count }

    func pendingDownloadMegabytes() async -> Int? { download }

    func setCellularDownloadAllowed(_ allowed: Bool) async {
        lock.withLock { cellularAsked.append(allowed) }
    }

    func setVocabulary(_ terms: [String]) async {
        lock.withLock {
            logged.append("vocabulary")
            vocabularySet.append(terms)
        }
    }

    func prepare(languageCode: String, progress: @escaping @Sendable (EnginePreparationProgress) -> Void) async -> EngineAvailability {
        lock.withLock { logged.append("prepare") }
        progress(EnginePreparationProgress(stage: .downloadingModel, fraction: 0.5))
        return availability
    }

    func stream(languageCode: String, audio: AsyncStream<[Float]>) -> AsyncThrowingStream<TranscriptToken, Error> {
        let number = lock.withLock { () -> Int in
            logged.append("stream")
            return logged.filter { $0 == "stream" }.count
        }
        return AsyncThrowingStream { continuation in
            Task {
                var samples: [Float] = []
                var sizes: [Int] = []
                for await piece in audio {
                    samples += piece
                    sizes.append(piece.count)
                }
                self.lock.withLock {
                    self.audioHeard.append(samples)
                    self.piecesHeard.append(sizes)
                }
                do {
                    for token in try self.reply(number, samples) { continuation.yield(token) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    static func final(_ text: String, id: UUID = UUID()) -> TranscriptToken {
        TranscriptToken(utteranceID: id, text: text, isFinal: true, timestamp: 0)
    }

    static func preview(_ text: String, id: UUID = UUID()) -> TranscriptToken {
        TranscriptToken(utteranceID: id, text: text, isFinal: false, timestamp: 0)
    }
}

enum FileRunAudio {
    static let rate = 16_000
    static let frame = Double(VoiceEvidence.chunkSamples) / 16_000

    static func tone(_ seconds: Double, from start: Int) -> [Float] {
        let count = Int(seconds * Double(rate))
        return (0..<count).map { Float(0.5 * sin(Double(start + $0) * 0.17)) }
    }

    static func quiet(_ seconds: Double) -> [Float] {
        [Float](repeating: 0, count: Int(seconds * Double(rate)))
    }

    static func timeline(_ parts: [(voiced: Bool, seconds: Double)]) -> [Float] {
        var samples: [Float] = []
        for part in parts {
            samples += part.voiced ? tone(part.seconds, from: samples.count) : quiet(part.seconds)
        }
        return samples
    }

    static func score(_ block: [Float]) -> Float? {
        block.suffix(VoiceEvidence.chunkSamples).contains { abs($0) > 0.1 } ? 0.9 : 0.05
    }
}

final class FileRunCursor: @unchecked Sendable {
    private let lock = NSLock()
    private var index = 0
    private let make: @Sendable (Int) -> [Float]?

    init(make: @escaping @Sendable (Int) -> [Float]?) { self.make = make }

    func next() -> (index: Int, piece: [Float]?) {
        let at = lock.withLock { () -> Int in
            defer { index += 1 }
            return index
        }
        return (at, make(at))
    }
}

func fileRunSource(
    _ samples: [Float],
    pieceSamples: Int = 16_000,
    onPull: @escaping @Sendable (Int) -> Void = { _ in },
    endingWith problem: (any Error)? = nil
) -> AsyncThrowingStream<[Float], any Error> {
    let cursor = FileRunCursor { index in
        let start = index * pieceSamples
        guard start < samples.count else { return nil }
        return Array(samples[start..<min(samples.count, start + pieceSamples)])
    }
    return AsyncThrowingStream(unfolding: {
        let step = cursor.next()
        onPull(step.index)
        if let piece = step.piece { return piece }
        if let problem { throw problem }
        return nil
    })
}

func fileRunSource(pieces: Int, make: @escaping @Sendable (Int) -> [Float]) -> AsyncThrowingStream<[Float], any Error> {
    let cursor = FileRunCursor { index in index < pieces ? make(index) : nil }
    return AsyncThrowingStream(unfolding: { cursor.next().piece })
}
