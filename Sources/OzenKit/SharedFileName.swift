import Foundation

public enum SharedFileName {
    private static let fallback = "recording"
    private static let longest = 80
    private static let mostBytes = 200
    private static let refused: Set<Unicode.Scalar> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
    private static let edge = CharacterSet(charactersIn: ". ").union(.whitespacesAndNewlines)

    public static func make(from name: String) -> String {
        var cleaned = String.UnicodeScalarView()
        for scalar in name.unicodeScalars where !isInvisibleDirection(scalar) {
            if refused.contains(scalar) || scalar.properties.generalCategory == .control {
                cleaned.append("_")
            } else {
                cleaned.append(scalar)
            }
        }
        var result = String(String(cleaned).trimmingCharacters(in: edge).prefix(longest))
        while result.utf8.count > mostBytes { result.removeLast() }
        result = result.trimmingCharacters(in: edge)
        return result.isEmpty ? fallback : result
    }

    private static func isInvisibleDirection(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x061C, 0x200E, 0x200F, 0x2028, 0x2029, 0xFEFF, 0x202A...0x202E, 0x2066...0x2069: return true
        default: return false
        }
    }
}
