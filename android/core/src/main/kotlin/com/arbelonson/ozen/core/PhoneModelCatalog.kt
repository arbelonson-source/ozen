package com.arbelonson.ozen.core

import java.net.URI

data class PhoneModel(
    val file: String,
    val variant: String,
    val nameText: () -> String,
    val noteText: () -> String,
    val source: PhoneModelSource,
    val isHebrewOnly: Boolean,
    val hebrewQuality: Int,
    val otherQuality: Int,
    val passGflop: Double,
) {
    val displayName: String get() = nameText()

    val note: String get() = noteText()

    val bytes: Long get() = source.bytes

    val megabytes: Int get() = ((source.bytes + 999_999) / 1_000_000).toInt()

    fun quality(language: UILanguage): Int = if (language == UILanguage.Hebrew) hebrewQuality else otherQuality
}

object PhoneModelCatalog {
    const val OZEN_HEBREW_FILE = "ggml-ozen-a3-q5_0.bin"
    const val GENERAL_FILE = "ggml-large-v3-turbo-q5_0.bin"

    private const val OZEN_MODELS = "https://github.com/arbelonson-source/ozen-models/releases/download"
    private const val WHISPER_CPP = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val IVRIT = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/2130c78e4a9cb4914cc4df91a1c3031407789705"

    val all: List<PhoneModel> = listOf(
        PhoneModel(
            file = OZEN_HEBREW_FILE,
            variant = "ozen-turbo-hebrew-a3-8bit",
            nameText = { tr("טורבו עברית של אוזן", "Ozen Turbo Hebrew") },
            noteText = { tr("אומן בנוסף על דיבור מרחוק, ברעש ומול טלוויזיה. הכי מדויק בעברית.", "Trained further on speech from across a room, in noise and over a TV. The most accurate in Hebrew.") },
            source = PhoneModelSource(URI("$OZEN_MODELS/model-ozen-turbo-hebrew-a3-ggml-q5_0-1/$OZEN_HEBREW_FILE"), 574_041_195, "c74b66691f8f97b4a38d43b5d8ee4ec1b32b3951a99520250f36883822c910af"),
            isHebrewOnly = true, hebrewQuality = 5, otherQuality = 0, passGflop = 470.0,
        ),
        PhoneModel(
            file = "ggml-ivrit-large-v3-turbo.bin",
            variant = "ivrit-large-v3-turbo-8bit",
            nameText = { tr("טורבו עברית של ivrit.ai", "Turbo Hebrew by ivrit.ai") },
            noteText = { tr("אומן על 5,000 שעות עברית. הקובץ המלא, בלי דחיסה: הורדה גדולה וכבד לטלפון.", "Trained on 5,000 hours of Hebrew. The full file, not compressed: a big download and heavy for a phone.") },
            source = PhoneModelSource(URI("$IVRIT/ggml-model.bin"), 1_624_555_275, "c8090411113357097bfafc2b8e228ec1639fa7f5fe4ecb5d054ac0ccef8641b1"),
            isHebrewOnly = true, hebrewQuality = 5, otherQuality = 0, passGflop = 470.0,
        ),
        PhoneModel(
            file = GENERAL_FILE,
            variant = "large-v3-v20240930_626MB",
            nameText = { tr("טורבו", "Turbo") },
            noteText = { tr("המודל הכללי המדויק ביותר, לכל השפות. בעברית טוב, אבל פחות ממודלי העברית.", "The most accurate general model, for every language. Good in Hebrew, but behind the Hebrew models.") },
            source = PhoneModelSource(URI("$WHISPER_CPP/$GENERAL_FILE"), 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
            isHebrewOnly = false, hebrewQuality = 4, otherQuality = 5, passGflop = 470.0,
        ),
        PhoneModel(
            file = "ggml-small-q5_1.bin",
            variant = "small",
            nameText = { tr("קטן", "Small") },
            noteText = { tr("קל יותר לטלפון איטי. מבין, עם טעויות. בעברית חלש יותר.", "Lighter for a slower phone. Understandable, with mistakes. Weaker in Hebrew.") },
            source = PhoneModelSource(URI("$WHISPER_CPP/ggml-small-q5_1.bin"), 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            isHebrewOnly = false, hebrewQuality = 2, otherQuality = 3, passGflop = 76.0,
        ),
        PhoneModel(
            file = "ggml-base-q5_1.bin",
            variant = "base",
            nameText = { tr("בסיסי", "Base") },
            noteText = { tr("הכי קל ומהיר, לטלפון ישן. בעברית רוב המילים שגויות.", "The lightest and fastest, for an old phone. In Hebrew most words come out wrong.") },
            source = PhoneModelSource(URI("$WHISPER_CPP/ggml-base-q5_1.bin"), 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            isHebrewOnly = false, hebrewQuality = 1, otherQuality = 2, passGflop = 17.0,
        ),
    )

    fun byFile(file: String): PhoneModel? = all.firstOrNull { it.file == file }

    fun recommended(language: UILanguage): PhoneModel = byFile(if (language == UILanguage.Hebrew) OZEN_HEBREW_FILE else GENERAL_FILE)!!

    fun offered(language: UILanguage): List<PhoneModel> {
        val usable = all.filter { language == UILanguage.Hebrew || !it.isHebrewOnly }
        val first = recommended(language)
        return listOf(first) + usable.filter { it != first }.sortedByDescending { it.quality(language) }
    }

    fun effective(language: UILanguage, chosenFile: String?): PhoneModel =
        offered(language).firstOrNull { it.file == chosenFile } ?: recommended(language)

    val managedFiles: Set<String> get() = all.map { it.file }.toSet()
}

enum class PhoneModelFit { KeepsUp, ALittleBehind, TooSlow }

object PhoneModelEstimate {
    const val REAL_PASS_SHARE = 0.95
    const val KEEPS_UP_SECONDS = 1.5
    const val BEHIND_SECONDS = 3.0

    fun passSeconds(model: PhoneModel, measuredGflops: Double): Double =
        model.passGflop / (measuredGflops * REAL_PASS_SHARE)

    fun fit(model: PhoneModel, measuredGflops: Double): PhoneModelFit {
        val seconds = passSeconds(model, measuredGflops)
        return when {
            !(measuredGflops > 0) -> PhoneModelFit.TooSlow
            seconds <= KEEPS_UP_SECONDS -> PhoneModelFit.KeepsUp
            seconds <= BEHIND_SECONDS -> PhoneModelFit.ALittleBehind
            else -> PhoneModelFit.TooSlow
        }
    }

    /**
     * Only a model that keeps up is recommended; one that is a little behind
     * is picked only when none keeps up. A real mid-range phone (an octa-core
     * Snapdragon 7s Gen 3 class chip, one A720 at 2.5 GHz, three at 2.4 GHz
     * and four A520, with dotprod/i8mm) needed about a minute for a line, or
     * never answered, with the Turbo-sized model. A phone at half the speed
     * of a Snapdragon 8 Gen 3 (about 125 GFLOPS here) must therefore land
     * on Small or Base, not on Turbo.
     */
    fun recommend(language: UILanguage, measuredGflops: Double?): PhoneModel {
        if (measuredGflops == null || !(measuredGflops > 0)) return PhoneModelCatalog.recommended(language)
        val offered = PhoneModelCatalog.offered(language)
        for (wanted in listOf(PhoneModelFit.KeepsUp, PhoneModelFit.ALittleBehind)) {
            offered.filter { fit(it, measuredGflops) == wanted }
                .maxWithOrNull(compareBy<PhoneModel> { it.quality(language) }.thenBy { -it.passGflop })
                ?.let { return it }
        }
        return offered.minBy { it.passGflop }
    }
}
