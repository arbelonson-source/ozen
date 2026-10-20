import Foundation

public struct TranscribedLine: Sendable, Equatable {
    public var startSeconds: Double
    public var endSeconds: Double
    public var text: String

    public init(startSeconds: Double, endSeconds: Double, text: String) {
        self.startSeconds = startSeconds
        self.endSeconds = endSeconds
        self.text = text
    }
}

public enum TranscriptFormats {
    public static func clock(_ seconds: Double) -> String { "" }

    public static func text(_ lines: [TranscribedLine], languageCode: String) -> String { "" }

    public static func srt(_ lines: [TranscribedLine], languageCode: String) -> String { "" }
}
