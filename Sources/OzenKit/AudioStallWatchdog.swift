/// Notices a microphone that has gone silent in the technical sense: no
/// audio arriving at all while the screen still says "listening".
///
/// A quiet room still delivers audio (a tap sends a chunk of near-silence
/// every few dozen milliseconds), so no chunks at all means the capture
/// itself died: a Bluetooth hearing aid reconnecting left the tap bound to
/// a format that no longer exists, or the engine failed to restart after a
/// phone call. Without this, captions simply stop and the reader can't
/// tell a dead microphone from a quiet room.
///
/// It counts ticks of the pipeline's own timer instead of reading a clock,
/// so a main thread that is briefly busy (and delivering neither chunks
/// nor ticks) is never mistaken for a dead microphone.
public struct AudioStallWatchdog: Sendable, Equatable {
    /// How often the pipeline calls `tick`.
    public static let tickSeconds = 0.25
    /// The slower check sounds-only uses all night.
    public static let soundsOnlyTickSeconds = 2.0

    public let isEnabled: Bool
    /// Seconds between the calls to `tick` this watchdog counts in.
    public let tickSeconds: Double
    /// Consecutive ticks without a new chunk that count as a stall.
    public let stallTicks: Int
    private var lastChunkCount: Int?
    private var quietTicks = 0

    public init(stallSeconds: Double = 6) {
        self.init(stallSeconds: stallSeconds, tickSeconds: Self.tickSeconds)
    }

    private init(stallSeconds: Double, tickSeconds: Double) {
        isEnabled = true
        self.tickSeconds = tickSeconds
        stallTicks = max(1, Int((stallSeconds / tickSeconds).rounded(.up)))
    }

    private init(disabled: Void) {
        isEnabled = false
        tickSeconds = Self.tickSeconds
        stallTicks = .max
    }

    public static let disabled = AudioStallWatchdog(disabled: ())

    public var stallSeconds: Double { Double(stallTicks) * tickSeconds }

    /// The same stall limit in seconds, counted in ticks of another length.
    /// The limit never shrinks, and grows by less than one tick.
    public func checking(everySeconds seconds: Double) -> AudioStallWatchdog {
        guard isEnabled else { return self }
        return AudioStallWatchdog(stallSeconds: stallSeconds, tickSeconds: seconds)
    }

    /// Call once per timer tick with the running count of chunks received.
    /// Returns `true` exactly once per stall, on the tick that completes it.
    /// While the system holds the audio session (a phone call) nothing is
    /// expected to arrive, so the count starts over.
    public mutating func tick(chunksReceived: Int, systemInterrupted: Bool, elapsedTicks: Int = 1) -> Bool {
        guard isEnabled else { return false }
        if systemInterrupted || chunksReceived != lastChunkCount {
            lastChunkCount = chunksReceived
            quietTicks = 0
            return false
        }
        let before = quietTicks
        quietTicks += max(0, elapsedTicks)
        return before < stallTicks && quietTicks >= stallTicks
    }

    public func ticks(forElapsed seconds: Double) -> Int {
        guard seconds > 0 else { return 0 }
        return Int((seconds / tickSeconds + 1e-9).rounded(.down))
    }

    /// A new capture begins; nothing that happened before counts.
    public mutating func reset() {
        lastChunkCount = nil
        quietTicks = 0
    }
}
