import Foundation

public enum FileModelChoice {
    public static func installed(for language: UILanguage, variants: Set<String>) -> [WhisperModelOption] { [] }

    public static func pick(for language: UILanguage, installedVariants: Set<String>) -> WhisperModelOption? { nil }

    public static func variant(for language: UILanguage, installedVariants: Set<String>) -> String { "" }

    public static func toDownload(for language: UILanguage) -> WhisperModelOption { WhisperModelCatalog.options[0] }
}

extension FileModelChoice {
    static func ranked(_ options: [WhisperModelOption]) -> [WhisperModelOption] { options }
}
