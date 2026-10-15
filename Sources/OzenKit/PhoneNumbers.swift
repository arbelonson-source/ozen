import Foundation

public enum PhoneNumbers {
    public struct Match: Equatable, Sendable {
        public let range: Range<String.Index>
        public let dialable: String

        public var url: URL? { URL(string: "tel:" + dialable) }
    }

    public static func matches(in text: String) -> [Match] {
        guard let pattern, let servicePattern else { return [] }
        let whole = NSRange(text.startIndex..., in: text)
        let services: [Match] = servicePattern.matches(in: text, range: whole).compactMap { found in
            guard let range = Range(found.range, in: text) else { return nil }
            let written = text[range]
            return Match(range: range, dialable: digitsOf(written))
        }
        let phones: [Match] = pattern.matches(in: text, range: whole).compactMap { found in
            guard let range = Range(found.range, in: text) else { return nil }
            let written = text[range]
            let digits = digitsOf(written)
            if written.hasPrefix("+") {
                guard digits.hasPrefix("972"), (11...12).contains(digits.count) else { return nil }
                return Match(range: range, dialable: "+" + digits)
            }
            guard (9...10).contains(digits.count) else { return nil }
            return Match(range: range, dialable: String(digits))
        }
        return (services + phones).sorted { $0.range.lowerBound < $1.range.lowerBound }
    }

    private static let pattern = try? NSRegularExpression(
        pattern: #"(?<![\d+])(?!\d{1,2}[-./]\d{1,2}[-./]\d{2,4})(?:\+972[-\x{2011}\x{00A0} ]?|0)(?:[2-489](?:[-\x{2011}\x{00A0} ]?\d){7}|[57](?:[-\x{2011}\x{00A0} ]?\d){8})(?![-\x{2011}\x{00A0}]?\d)"#
    )

    private static let servicePattern = try? NSRegularExpression(
        pattern: #"(?<![\d+*])1[-\x{2011}\x{00A0} ]?(?:700|800|801|599)(?:[-\x{2011}\x{00A0} ]?\d){6}(?![-\x{2011}\x{00A0}]?\d)"#
    )
}

private func digitsOf(_ written: Substring) -> String {
    var digits = ""
    for scalar in written.unicodeScalars where scalar.properties.numericType == .decimal {
        if let value = scalar.properties.numericValue { digits += String(Int(value)) }
    }
    return digits
}
