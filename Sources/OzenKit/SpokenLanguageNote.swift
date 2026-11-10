public enum SpokenLanguageNote {
    public static func text(for language: UILanguage, engine: TranscriptionEngineKind) -> String? {
        guard language == .amharic, engine != .cloud else { return nil }
        return tr(
            "כתוביות באמהרית עדיין שגויות לעיתים קרובות. כדאי לוודא כל דבר חשוב מול מי שמדבר.",
            "Captions in Amharic are often wrong for now. Check anything important with the person speaking."
        )
    }
}
