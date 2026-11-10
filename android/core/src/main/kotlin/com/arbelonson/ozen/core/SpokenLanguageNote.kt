package com.arbelonson.ozen.core

object SpokenLanguageNote {
    fun text(language: UILanguage, engine: TranscriptionEngineKind): String? =
        if (language == UILanguage.Amharic && engine != TranscriptionEngineKind.Cloud) {
            tr(
                "כתוביות באמהרית עדיין שגויות לעיתים קרובות. כדאי לוודא כל דבר חשוב מול מי שמדבר.",
                "Captions in Amharic are often wrong for now. Check anything important with the person speaking.",
            )
        } else {
            null
        }
}
