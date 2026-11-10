import Foundation

/// A sound heard while the caption screen wasn't in front of her, kept
/// for when she comes back.
///
/// Its notification is taken away as the app returns, and its banner lasts
/// seconds: someone who unlocked the phone to see what buzzed found
/// nothing. The sound is kept instead and, back on the screen, stays until
/// closed or until a new sound is heard. It is read to a screen reader
/// once, with no buzz and no flash: it isn't happening now.
///
/// One sound is kept: the most important heard while away, and of those
/// the newest, so a dog barking after the smoke alarm doesn't hide it.
public struct AwaySound: Sendable, Equatable {
    public struct Heard: Sendable, Equatable {
        public let alert: SoundAlert
        public let at: TimeInterval

        public init(alert: SoundAlert, at: TimeInterval) {
            self.alert = alert
            self.at = at
        }
    }

    private var waiting: Heard?
    /// What the caption screen shows now.
    public private(set) var shown: Heard?

    public init() {}

    /// A sound alert was raised. Heard on the screen, it is news of its own
    /// and takes the place of the one kept from before.
    public mutating func heard(_ alert: SoundAlert, at time: TimeInterval, appIsActive: Bool) {
        guard !appIsActive else {
            shown = nil
            return
        }
        if let waiting, waiting.alert.event.importance > alert.event.importance { return }
        waiting = Heard(alert: alert, at: time)
    }

    /// Back on the screen. A sound whose own banner is still up shows
    /// there as it would have, buzz and all, rather than here.
    public mutating func screenReturned(bannerStillUp: (SoundAlert) -> Bool) {
        defer { waiting = nil }
        guard let waiting, !bannerStillUp(waiting.alert) else { return }
        shown = waiting
    }

    public mutating func close() {
        shown = nil
    }

    public var waitingRecord: String? {
        waiting.map(AwaySoundRecord.encode)
    }

    public mutating func restore(_ record: String?, now: TimeInterval) {
        guard waiting == nil, let heard = AwaySoundRecord.decode(record, now: now) else { return }
        waiting = heard
    }
}
