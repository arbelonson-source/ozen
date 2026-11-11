package com.arbelonson.ozen.core

import kotlin.math.roundToInt

object SliderSpeech {
    fun speechRate(rate: Double, language: UILanguage = Localization.language): String {
        val step = (rate * 20).roundToInt()
        val number = NumberText.decimal(step / 20.0, 2, language)
        val word = when {
            step < 8 -> tr("איטי", "Slow", language)
            step > 11 -> tr("מהיר", "Fast", language)
            else -> tr("רגיל", "Normal", language)
        }
        return "$word, $number"
    }

    fun textSize(size: Double, language: UILanguage = Localization.language): String =
        tr("נקודות: %1", "Points: %1", listOf("${size.toInt()}"), language)

    fun speakerSeparation(threshold: Double, language: UILanguage = Localization.language): String {
        val usual = AppSettings.default.speakerSimilarityThreshold.toDouble()
        val word = when {
            threshold < usual - 0.005 -> tr("מאחד יותר", "More merging", language)
            threshold > usual + 0.005 -> tr("מפריד יותר", "More separating", language)
            else -> tr("רגיל", "Normal", language)
        }
        return "$word, ${NumberText.decimal(threshold, 2, language)}"
    }
}
