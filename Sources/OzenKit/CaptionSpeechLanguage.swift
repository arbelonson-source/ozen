import Foundation

public enum CaptionSpeechLanguage {
    private static let nonLatin: Set<String> = [
        "he", "iw", "yi", "ar", "fa", "ur", "ru", "uk", "bg", "sr", "el", "hi", "th", "zh", "ja", "ko", "ka", "hy",
    ]

    public static func bcp47(forLine line: String, languageCode: String) -> String {
        let setting = languageCode.trimmingCharacters(in: .whitespaces)
        let fallback = setting.isEmpty ? "en" : setting
        if line.unicodeScalars.contains(where: isHebrewLetter) { return "he" }
        guard line.unicodeScalars.contains(where: isLatinLetter) else { return fallback }
        let base = fallback.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map { $0.lowercased() } ?? ""
        let hasOtherLetters = line.unicodeScalars.contains { $0.properties.isAlphabetic && !isLatinLetter($0) }
        if hasOtherLetters { return fallback }
        return nonLatin.contains(base) ? "en" : fallback
    }

    private static func isHebrewLetter(_ scalar: Unicode.Scalar) -> Bool {
        (0x05D0...0x05EA).contains(scalar.value) || (0xFB1D...0xFB4F).contains(scalar.value)
    }

    private static func isLatinLetter(_ scalar: Unicode.Scalar) -> Bool {
        scalar.properties.isAlphabetic && (scalar.value < 0x250 || (0x1E00...0x1EFF).contains(scalar.value))
    }
}
