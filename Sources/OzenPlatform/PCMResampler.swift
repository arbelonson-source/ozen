import AVFoundation

struct PCMResampler {
    private let converter: AVAudioConverter
    private let output: AVAudioPCMBuffer

    init?(from format: AVAudioFormat, sampleRate: Double, maxInputFrames: AVAudioFrameCount) {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: format, to: target),
              let output = AVAudioPCMBuffer(
                pcmFormat: target,
                frameCapacity: AVAudioFrameCount(Double(maxInputFrames) * sampleRate / format.sampleRate) + 1024
              )
        else { return nil }
        self.converter = converter
        self.output = output
    }

    func convert(_ block: AVAudioPCMBuffer?, into samples: inout [Float]) -> Bool {
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
}
