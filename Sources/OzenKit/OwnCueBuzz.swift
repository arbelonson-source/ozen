/// Which of the alerts that have no banner of their own gets a buzz: one
/// buzz for the most important of them, and none while another pattern is
/// still playing (a doorbell must not cut a smoke alarm's pattern short).
/// Every one of them is still announced.
public enum OwnCueBuzz {
    public static func pick(from alerts: [SoundAlert], buzzing: Bool) -> SoundAlert? {
        guard !buzzing else { return nil }
        return alerts.max { $0.event.importance < $1.event.importance }
    }
}
