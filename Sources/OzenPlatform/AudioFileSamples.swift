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
        guard let file = try? AVAudioFile(forReading: url),
              let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: file.processingFormat, to: target)
        else { return nil }
        let sourceRate = file.processingFormat.sampleRate
        let totalFrames = min(file.length, AVAudioFramePosition(maximumSeconds * sourceRate))
        guard totalFrames > 0 else { return nil }
        let chunkFrames = AVAudioFrameCount(max(1, min(Double(totalFrames), chunkSeconds * sourceRate)))
        guard let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: chunkFrames),
              let output = AVAudioPCMBuffer(
                pcmFormat: target,
                frameCapacity: AVAudioFrameCount(Double(chunkFrames) * sampleRate / sourceRate) + 1024
              )
        else { return nil }
        var samples: [Float] = []
        samples.reserveCapacity(Int(Double(totalFrames) * sampleRate / sourceRate) + 1024)

        func drain(handing block: AVAudioPCMBuffer?) -> Bool {
            var handedOver = false
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                guard let block else {
                    inputStatus.pointee = .endOfStream
                    return nil
                }
                if handedOver {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                handedOver = true
                inputStatus.pointee = .haveData
                return block
            }
            guard status != .error, error == nil, let channel = output.floatChannelData else { return false }
            samples.append(contentsOf: UnsafeBufferPointer(start: channel[0], count: Int(output.frameLength)))
            return true
        }

        var position: AVAudioFramePosition = 0
        while position < totalFrames {
            let count = AVAudioFrameCount(min(AVAudioFramePosition(chunkFrames), totalFrames - position))
            source.frameLength = 0
            guard (try? read(file, source, count)) != nil, source.frameLength > 0 else { return nil }
            position += AVAudioFramePosition(source.frameLength)
            guard drain(handing: source) else { return nil }
        }
        guard drain(handing: nil) else { return nil }
        return samples
    }
}
