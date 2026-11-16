import Foundation

public enum SharedFileName {
    private static let fallback = "recording"
    private static let longest = 80
    private static let mostBytes = 200
    private static let refused: Set<Unicode.Scalar> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
    private static let edge = CharacterSet(charactersIn: ".\u{200C} ").union(.whitespacesAndNewlines)

    public static func make(from name: String) -> String {
        var cleaned = String.UnicodeScalarView()
        for scalar in withoutInvisible(name) {
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
        case 0x061C, 0x200B, 0x200E, 0x200F, 0x2028, 0x2029, 0xFEFF, 0x202A...0x202E, 0x2066...0x2069: return true
        default: return false
        }
    }

    private static func isTag(_ scalar: Unicode.Scalar) -> Bool {
        (0xE0020...0xE007F).contains(scalar.value)
    }

    private static func isPictographic(_ scalar: Unicode.Scalar) -> Bool {
        (0x1F000...0x1FAFF).contains(scalar.value) || (0x2190...0x2BFF).contains(scalar.value) || scalar.value == 0xFE0F
    }

    private static func canJoin(_ scalar: Unicode.Scalar) -> Bool {
        if isPictographic(scalar) { return true }
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    /// Direction overrides, line separators and zero-width spaces are left
    /// out. A joiner stays only between two letters, marks or pictures.
    /// Emoji tag characters go too, except in a flag's tag sequence.
    private static func withoutInvisible(_ text: String) -> [Unicode.Scalar] {
        let scalars = Array(text.unicodeScalars.filter { !isInvisibleDirection($0) })
        var out: [Unicode.Scalar] = []
        var index = 0
        while index < scalars.count {
            let scalar = scalars[index]
            var next = index + 1
            if scalar.value == 0x1F3F4 {
                var end = next
                while end < scalars.count, isTag(scalars[end]), scalars[end].value != 0xE007F { end += 1 }
                if end > next, end < scalars.count, scalars[end].value == 0xE007F { next = end + 1 }
                out.append(contentsOf: scalars[index..<next])
            } else if scalar.value == 0x200D {
                if let before = out.last, next < scalars.count, canJoin(before), canJoin(scalars[next]) { out.append(scalar) }
            } else if !isTag(scalar) {
                out.append(scalar)
            }
            index = next
        }
        return out
    }
}
