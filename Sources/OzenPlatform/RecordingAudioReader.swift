import AVFoundation
import Foundation
import OzenKit

enum AudioFileSource {
    typealias Read = @Sendable (AVAudioFile, AVAudioPCMBuffer, AVAudioFrameCount) throws -> Void
}

public final class RecordingAudioReader: @unchecked Sendable {
    public static let sampleRate = 16_000.0
    public static let chunkSeconds = 30.0

    public let durationSeconds: Double? = nil

    public static func open(_ url: URL, chunkSeconds: Double = RecordingAudioReader.chunkSeconds) async throws -> RecordingAudioReader {
        throw RecordingReadProblem.unreadable
    }

    static func open(_ url: URL, chunkSeconds: Double, read: @escaping AudioFileSource.Read) async throws -> RecordingAudioReader {
        throw RecordingReadProblem.unreadable
    }

    public func nextChunk() throws -> [Float]? { nil }

    public var chunks: AsyncThrowingStream<[Float], any Error> {
        AsyncThrowingStream { $0.finish() }
    }
}
