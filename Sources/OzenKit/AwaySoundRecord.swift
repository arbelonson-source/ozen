import Foundation

public protocol AwaySoundRecordStore: Sendable {
    func read() -> String?
    func write(_ value: String?)
}

public enum AwaySoundRecord {
    public static let keptSeconds: TimeInterval = 12 * 3_600

    public static func encode(_ heard: AwaySound.Heard) -> String {
        ["v1", heard.alert.event.identifier, String(heard.at), heard.alert.id.uuidString].joined(separator: "\t")
    }

    public static func decode(_ record: String?, now: TimeInterval) -> AwaySound.Heard? {
        guard let parts = record?.components(separatedBy: "\t"), parts.count == 4, parts[0] == "v1",
              let event = SoundEventCatalog.event(for: parts[1]),
              let at = TimeInterval(parts[2]), at.isFinite,
              let id = UUID(uuidString: parts[3]),
              at <= now + 300, now - at <= keptSeconds
        else { return nil }
        return AwaySound.Heard(alert: SoundAlert(id: id, event: event, confidence: 1, timestamp: at), at: at)
    }
}

extension SoundAlert {
    public var inCurrentLanguage: SoundAlert {
        SoundEventCatalog.event(for: event.identifier).map { SoundAlert(id: id, event: $0, confidence: confidence, timestamp: timestamp) } ?? self
    }
}
