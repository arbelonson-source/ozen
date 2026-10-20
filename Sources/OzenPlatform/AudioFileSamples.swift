import AVFoundation

public enum AudioFileSamples {
    public static let maximumSeconds = 600.0

    public static func load(_ url: URL, sampleRate: Double = 16_000) -> [Float]? {
        load(url, sampleRate: sampleRate, chunkSeconds: 1) { file, buffer, count in
            try file.read(into: buffer, frameCount: count)
        }
    }

    static func load(
        _ url: URL,
        sampleRate: Double,
        chunkSeconds: Double,
        read: (AVAudioFile, AVAudioPCMBuffer, AVAudioFrameCount) throws -> Void
    ) -> [Float]? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let sourceRate = file.processingFormat.sampleRate
        let totalFrames = min(file.length, AVAudioFramePosition(maximumSeconds * sourceRate))
        guard totalFrames > 0 else { return nil }
        let chunkFrames = AVAudioFrameCount(max(1, min(Double(totalFrames), chunkSeconds * sourceRate)))
        guard let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: chunkFrames),
              let resampler = PCMResampler(from: file.processingFormat, sampleRate: sampleRate, maxInputFrames: chunkFrames)
        else { return nil }
        var samples: [Float] = []
        samples.reserveCapacity(Int(Double(totalFrames) * sampleRate / sourceRate) + 1024)

        var position: AVAudioFramePosition = 0
        while position < totalFrames {
            let count = AVAudioFrameCount(min(AVAudioFramePosition(chunkFrames), totalFrames - position))
            source.frameLength = 0
            guard (try? read(file, source, count)) != nil, source.frameLength > 0 else { return nil }
            position += AVAudioFramePosition(source.frameLength)
            guard resampler.convert(source, into: &samples) else { return nil }
        }
        guard resampler.convert(nil, into: &samples) else { return nil }
        return samples
    }
}
