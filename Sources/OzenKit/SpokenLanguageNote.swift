public enum SpokenLanguageNote {
    public static func text(for language: UILanguage) -> String? {
        guard language == .amharic else { return nil }
        return tr(
            "כתוביות באמהרית עדיין שגויות לעיתים קרובות. כדאי לוודא כל דבר חשוב מול מי שמדבר.",
            "Captions in Amharic are often wrong for now. Check anything important with the person speaking."
        )
    }
}
