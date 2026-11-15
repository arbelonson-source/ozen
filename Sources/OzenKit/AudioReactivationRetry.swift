import Foundation

/// How long the iPhone keeps asking for the audio session back when the
/// system will not hand it over yet: right after a call, or while the audio
/// system itself is starting over, a request can fail for a second or two
/// and then work. The waits are short on purpose; past them the capture is
/// reported lost and the pipeline starts it again from scratch.
public struct AudioReactivationRetry: Sendable, Equatable {
    public let delays: [Double]

    public init(delays: [Double]) {
        self.delays = delays
    }

    public static let standard = AudioReactivationRetry(delays: [0.5, 1, 2, 4])

    /// The wait before the next try after `afterFailures` failures in a
    /// row, or nil when the tries have run out.
    public func delay(afterFailures: Int) -> Double? {
        guard afterFailures >= 1, afterFailures <= delays.count else { return nil }
        return delays[afterFailures - 1]
    }

    /// Runs `attempt` until it succeeds or the waits run out; `sleep` is the
    /// wait itself. A cancelled task gives up at once.
    public func run(sleep: (Double) async -> Void, attempt: () async -> Bool) async -> Bool {
        var failures = 0
        while !Task.isCancelled {
            if await attempt() { return true }
            failures += 1
            guard let wait = delay(afterFailures: failures) else { return false }
            await sleep(wait)
        }
        return false
    }
}
