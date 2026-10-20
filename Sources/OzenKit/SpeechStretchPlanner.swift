import Foundation

public struct SpeechStretch: Sendable, Equatable {
    public var startSeconds: Double
    public var endSeconds: Double
    public var voicedFrames: Int

    public init(startSeconds: Double, endSeconds: Double, voicedFrames: Int = 0) {
        self.startSeconds = startSeconds
        self.endSeconds = endSeconds
        self.voicedFrames = voicedFrames
    }
}

public struct SpeechStretchPlanner: Sendable {
    public let frameSeconds: Double
    public let capFrames: Int
    public let lookBackFrames: Int
    public let pauseFrames: Int
    public let minSpeechFrames: Int
    private let threshold: Float
    private var seen = 0
    private var open: Int?
    private var scores: [Float] = []
    private var silentRun = 0

    public init(
        frameSeconds: Double,
        capSeconds: Double,
        threshold: Float = VoiceEvidence.voiceThreshold,
        pauseSeconds: Double = 0.7,
        minSpeechSeconds: Double = 0.25,
        cutLookBackSeconds: Double = 4.0
    ) {
        self.frameSeconds = frameSeconds
        self.threshold = threshold
        capFrames = max(2, Int((capSeconds / frameSeconds + 1e-9).rounded(.down)))
        pauseFrames = Self.frames(pauseSeconds, frameSeconds)
        minSpeechFrames = Self.frames(minSpeechSeconds, frameSeconds)
        lookBackFrames = max(1, min(Self.frames(cutLookBackSeconds, frameSeconds), capFrames / 2))
    }

    public var openStartSeconds: Double? { open.map { Double($0) * frameSeconds } }

    public mutating func add(_ probabilities: [Float]) -> [SpeechStretch] {
        var done: [SpeechStretch] = []
        for score in probabilities { step(score, &done) }
        return done
    }

    public mutating func finish() -> [SpeechStretch] {
        var done: [SpeechStretch] = []
        close(scores.count - silentRun, &done)
        open = nil
        silentRun = 0
        scores.removeAll()
        return done
    }

    public static func plan(_ probabilities: [Float], frameSeconds: Double, capSeconds: Double) -> [SpeechStretch] {
        var planner = SpeechStretchPlanner(frameSeconds: frameSeconds, capSeconds: capSeconds)
        let first = planner.add(probabilities)
        return first + planner.finish()
    }

    private static func frames(_ seconds: Double, _ frameSeconds: Double) -> Int {
        max(1, Int((seconds / frameSeconds).rounded()))
    }

    private mutating func step(_ score: Float, _ done: inout [SpeechStretch]) {
        let speech = score >= threshold
        seen += 1
        if open == nil {
            guard speech else { return }
            open = seen - 1
            scores.removeAll(keepingCapacity: true)
            silentRun = 0
        }
        scores.append(score)
        silentRun = speech ? 0 : silentRun + 1
        if silentRun >= pauseFrames {
            close(scores.count - silentRun, &done)
            open = nil
            scores.removeAll(keepingCapacity: true)
        } else if scores.count >= capFrames {
            cut(&done)
        }
    }

    private mutating func cut(_ done: inout [SpeechStretch]) {
        var at = scores.count - 1
        for index in stride(from: scores.count - 1, through: scores.count - lookBackFrames, by: -1) where scores[index] < scores[at] {
            at = index
        }
        let start = open ?? 0
        close(trimmed(at), &done)
        let rest = Array(scores[at...])
        scores.removeAll(keepingCapacity: true)
        guard let first = rest.firstIndex(where: { $0 >= threshold }), let last = rest.lastIndex(where: { $0 >= threshold }) else {
            open = nil
            return
        }
        open = start + at + first
        scores = Array(rest[first...])
        silentRun = rest.count - 1 - last
    }

    private func trimmed(_ length: Int) -> Int {
        var end = length
        while end > 0, scores[end - 1] < threshold { end -= 1 }
        return end
    }

    private func close(_ length: Int, _ done: inout [SpeechStretch]) {
        guard let start = open, length > 0 else { return }
        let voiced = scores[0..<length].filter { $0 >= threshold }.count
        guard voiced >= minSpeechFrames else { return }
        done.append(SpeechStretch(startSeconds: Double(start) * frameSeconds, endSeconds: Double(start + length) * frameSeconds, voicedFrames: voiced))
    }
}
