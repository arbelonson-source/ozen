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

    public init(
        frameSeconds: Double,
        capSeconds: Double,
        threshold: Float = VoiceEvidence.voiceThreshold,
        pauseSeconds: Double = 0.7,
        minSpeechSeconds: Double = 0.25,
        cutLookBackSeconds: Double = 4.0
    ) {
        self.frameSeconds = frameSeconds
        capFrames = 0
        lookBackFrames = 0
        pauseFrames = 0
        minSpeechFrames = 0
    }

    public var openStartSeconds: Double? { nil }

    public mutating func add(_ probabilities: [Float]) -> [SpeechStretch] { [] }

    public mutating func finish() -> [SpeechStretch] { [] }

    public static func plan(_ probabilities: [Float], frameSeconds: Double, capSeconds: Double) -> [SpeechStretch] { [] }
}
