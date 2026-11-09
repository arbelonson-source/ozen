import Foundation

public final class SpeechIntake: @unchecked Sendable {
    public struct Status: Sendable, Equatable {
        public var count: Int
        public var firstSpeechStart: Int?
        public var lastSpeechEnd: Int?
        public var finished: Bool
        public var pauses: [Int] = []
    }

    private let lock = NSLock()
    private var samples: [Float] = []
    private var speechStarts: [Int] = []
    private var lastSpeechEnd: Int?
    private var pauses: [Int] = []
    private var finished = false
    private var detector: EnergyVoiceDetector
    private let pauseSamples: Int?
    private var evidence: VoiceEvidence?

    public init(detector: EnergyVoiceDetector = EnergyVoiceDetector(), pauseSamples: Int? = nil, voiceScorer: VoiceEvidence.Scorer? = nil) {
        self.detector = detector
        self.pauseSamples = pauseSamples
        evidence = voiceScorer.map { VoiceEvidence(score: $0) }
    }

    public func append(_ chunk: [Float]) {
        lock.withLock {
            let start = samples.count
            samples.append(contentsOf: chunk)
            if detector.isSpeech(chunk) {
                if let pauseSamples, let end = lastSpeechEnd, start - end >= pauseSamples {
                    pauses.append(end)
                }
                speechStarts.append(start)
                lastSpeechEnd = samples.count
            }
            evidence?.append(chunk)
        }
    }

    public func hasVoice(upTo end: Int) -> Bool? {
        lock.withLock { evidence?.hasVoice(inFirst: end) }
    }

    public func voicedChunks(upTo end: Int) -> Int? {
        lock.withLock { evidence?.voicedChunks(inFirst: end) }
    }

    public func markFinished() {
        lock.withLock { finished = true }
    }

    public func status() -> Status {
        lock.withLock {
            Status(count: samples.count, firstSpeechStart: speechStarts.first, lastSpeechEnd: lastSpeechEnd, finished: finished, pauses: pauses)
        }
    }

    public func copySamples(upTo end: Int) -> [Float] {
        lock.withLock {
            Array(samples[0..<min(max(end, 0), samples.count)])
        }
    }

    public func drop(prefix count: Int) {
        lock.withLock {
            let dropped = min(max(count, 0), samples.count)
            samples.removeFirst(dropped)
            evidence?.drop(prefix: dropped)
            speechStarts = speechStarts.compactMap { $0 >= dropped ? $0 - dropped : nil }
            pauses = pauses.compactMap { $0 > dropped ? $0 - dropped : nil }
            if let end = lastSpeechEnd {
                lastSpeechEnd = end > dropped ? end - dropped : nil
            }
        }
    }
}
