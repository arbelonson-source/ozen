import Foundation

public enum PhoneNumbers {
    public struct Match: Equatable, Sendable {
        public let range: Range<String.Index>
        public let dialable: String

        public var url: URL? { URL(string: "tel:" + dialable) }
    }

    public static func matches(in text: String) -> [Match] {
        guard let pattern, let servicePattern else { return [] }
        let read = asciiDigits(text)
        let whole = NSRange(read.startIndex..., in: read)
        let services: [Match] = servicePattern.matches(in: read, range: whole).compactMap { found in
            guard let range = Range(found.range, in: text) else { return nil }
            let written = text[range]
            return Match(range: range, dialable: digitsOf(written))
        }
        let phones: [Match] = pattern.matches(in: read, range: whole).compactMap { found in
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

    /// `text` with every digit of another script (Arabic-Indic, Persian,
    /// Devanagari, full-width) written as 0-9, so a number said in Arabic
    /// and written in Arabic-Indic digits is found like "050-1234567", not
    /// only when its first digits happen to be 0-9. Each such digit is one
    /// UTF-16 unit either way, so a match's place is the same in `text`.
    static func asciiDigits(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.map { scalar in
            guard scalar.value < 0x10000, scalar.properties.numericType == .decimal, let value = scalar.properties.numericValue,
                  let ascii = Unicode.Scalar(UInt32(48 + Int(value)))
            else { return scalar }
            return ascii
        }))
    }

    private static let pattern = try? NSRegularExpression(
        pattern: #"(?<![\d+])(?!\d{1,2}[-./]\d{1,2}[-./]\d{2,4})(?:\+972[-\x{2011}\x{00A0}\x{202F} ]?|0)(?:[2-489](?:[-\x{2011}\x{00A0}\x{202F} ]?\d){7}|[57](?:[-\x{2011}\x{00A0}\x{202F} ]?\d){8})(?![-\x{2011}\x{00A0}\x{202F}]?\d)"#
    )

    private static let servicePattern = try? NSRegularExpression(
        pattern: #"(?<![\d+*])1[-\x{2011}\x{00A0}\x{202F} ]?(?:700|800|801|599)(?:[-\x{2011}\x{00A0}\x{202F} ]?\d){6}(?![-\x{2011}\x{00A0}\x{202F}]?\d)"#
    )
}

private func digitsOf(_ written: Substring) -> String {
    var digits = ""
    for scalar in written.unicodeScalars where scalar.properties.numericType == .decimal {
        if let value = scalar.properties.numericValue { digits += String(Int(value)) }
    }
    return digits
}
