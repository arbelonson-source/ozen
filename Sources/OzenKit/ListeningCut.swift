import Foundation

public enum ListeningCut {
    public static let leastGapSeconds: Double = 120
    private static let tag = "listening"

    public struct Record: Sendable, Equatable {
        public var startedAt: Double
        public var aliveAt: Double
        public var version: Int

        public init(startedAt: Double, aliveAt: Double, version: Int) {
            self.startedAt = startedAt
            self.aliveAt = aliveAt
            self.version = version
        }
    }

    public struct Notice: Sendable, Equatable {
        public let stoppedAt: Double
        public let offSeconds: Double

        public init(stoppedAt: Double, offSeconds: Double) {
            self.stoppedAt = stoppedAt
            self.offSeconds = offSeconds
        }

        public func text(timeZone: TimeZone = .current) -> String {
            let style = Date.FormatStyle(date: .omitted, time: .shortened, locale: Localization.locale, timeZone: timeZone)
            let clock = Date(timeIntervalSince1970: stoppedAt).formatted(style)
            return tr(
                "ההאזנה נעצרה בערך ב־%1 והייתה כבויה במשך %2. בזמן הזה לא יכלו להגיע התראות.",
                "Listening stopped at about %1 and was off for %2. No alerts could come through during that time.",
                args: ["\(clock)", "\(ConversationStats.minutesText(offSeconds))"]
            )
        }
    }

    public static func encode(_ record: Record) -> String {
        "\(tag) \(record.startedAt) \(record.aliveAt) \(record.version)"
    }

    public static func parse(_ raw: String?) -> Record? {
        guard let raw else { return nil }
        let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 4, parts[0] == tag,
              let started = Double(parts[1]), let alive = Double(parts[2]), let version = Int(parts[3]),
              started.isFinite, alive.isFinite, started >= 0, alive >= started
        else { return nil }
        return Record(startedAt: started, aliveAt: alive, version: version)
    }

    public static func notice(_ record: Record?, now: Double, version: Int) -> Notice? {
        guard let record, record.version == version else { return nil }
        let gap = now - record.aliveAt
        guard gap.isFinite, gap > leastGapSeconds else { return nil }
        return Notice(stoppedAt: record.aliveAt, offSeconds: gap)
    }

    public static func versionNumber(shortVersion: String?, build: String?) -> Int {
        func part(_ text: Substring?) -> Int { min(max(text.flatMap { Int($0) } ?? 0, 0), 999) }
        let pieces = (shortVersion ?? "").split(separator: ".")
        let numbers = (0..<3).map { part($0 < pieces.count ? pieces[$0] : nil) }
        let built = part(build.map { Substring($0) })
        return (((numbers[0] * 1_000 + numbers[1]) * 1_000) + numbers[2]) * 1_000 + built
    }
}
