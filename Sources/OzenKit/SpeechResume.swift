import Foundation

public struct SpeechResume: Sendable {
    public var quietSeconds: TimeInterval
    private var lastTalk: TimeInterval?
    private var count = 0
    private var newestID: UUID?
    private var newestText: String?

    public init(quietSeconds: TimeInterval = 8) {
        self.quietSeconds = quietSeconds
    }

    public mutating func noteCaptions(_ lines: [TranscriptSegment], now: TimeInterval) -> Bool {
        let latest = lines.last
        if lines.count == count && latest?.id == newestID && latest?.text == newestText { return false }
        let quiet = lastTalk.map { now - $0 }
        count = lines.count
        newestID = latest?.id
        newestText = latest?.text
        lastTalk = now
        guard !lines.isEmpty, let quiet else { return false }
        return quiet > quietSeconds
    }
}
