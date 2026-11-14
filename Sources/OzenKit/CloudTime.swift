enum CloudTime {
    static let longestMs = 86_400_000

    static func milliseconds(fromSeconds seconds: Double) -> Int? {
        milliseconds(fromMs: seconds * 1_000)
    }

    static func milliseconds(fromMs ms: Double) -> Int? {
        guard ms.isFinite, ms >= 0, ms <= Double(longestMs) else { return nil }
        return Int(ms.rounded())
    }

    static func milliseconds(_ ms: Int?) -> Int? {
        guard let ms, ms >= 0, ms <= longestMs else { return nil }
        return ms
    }

    static func span(from start: Int, to end: Int?, wordStart: Int? = nil) -> Int {
        guard let latest = [end, wordStart].compactMap({ $0 }).max() else { return 0 }
        return latest.subtractingReportingOverflow(start).partialValue
    }
}
