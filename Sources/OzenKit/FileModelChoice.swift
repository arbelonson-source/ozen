import Foundation

public enum FileModelChoice {
    public static func installed(for language: UILanguage, variants: Set<String>) -> [WhisperModelOption] {
        ranked(WhisperModelCatalog.options.filter { variants.contains($0.variant) && (language == .hebrew || !$0.isHebrewOnly) })
    }

    public static func pick(for language: UILanguage, installedVariants: Set<String>) -> WhisperModelOption? {
        installed(for: language, variants: installedVariants).first
    }

    public static func variant(for language: UILanguage, installedVariants: Set<String>) -> String {
        pick(for: language, installedVariants: installedVariants)?.variant ?? WhisperModelCatalog.recommendedVariant(for: language)
    }

    public static func toDownload(for language: UILanguage) -> WhisperModelOption {
        WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant(for: language)) ?? WhisperModelCatalog.options[0]
    }

    static func ranked(_ options: [WhisperModelOption]) -> [WhisperModelOption] {
        options.enumerated().sorted { left, right in
            let (a, b) = (left.element, right.element)
            if (a.speed > 1) != (b.speed > 1) { return a.speed > 1 }
            if a.hebrewQuality != b.hebrewQuality { return a.hebrewQuality > b.hebrewQuality }
            if a.speed != b.speed { return a.speed > b.speed }
            return left.offset < right.offset
        }
        .map(\.element)
    }
}
