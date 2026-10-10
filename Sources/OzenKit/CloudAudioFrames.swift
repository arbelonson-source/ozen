struct CloudAudioFrames {
    static let shortest = CloudSpeechEngine.sampleRate / 20
    static let longest = CloudSpeechEngine.sampleRate

    private var held: [Float] = []

    mutating func add(_ samples: [Float]) -> [[Float]] {
        held += samples
        var frames: [[Float]] = []
        while held.count >= Self.shortest {
            let size = min(held.count, Self.longest)
            frames.append(Array(held.prefix(size)))
            held.removeFirst(size)
        }
        return frames
    }

    mutating func finish() -> [Float]? {
        guard !held.isEmpty else { return nil }
        defer { held = [] }
        return held + [Float](repeating: 0, count: max(0, Self.shortest - held.count))
    }
}
