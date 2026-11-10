package com.arbelonson.ozen.core

import kotlin.math.ceil

object ByteSize {
    fun text(bytes: Long): String {
        val kilobytes = ceil(bytes.toDouble() / 1_000)
        if (kilobytes < 1_000) return joined(kilobytes.toInt().toString(), Scale.Kilo)
        val wholeMegabytes = Math.round(bytes.toDouble() / StorageSpaceGate.BYTES_PER_MEGABYTE)
        if (wholeMegabytes >= 1_000) return joined(NumberText.decimal(wholeMegabytes.toDouble() / 1_000, 1), Scale.Giga)
        return megabytes(wholeMegabytes.toInt())
    }

    fun megabytes(megabytes: Int): String = joined(megabytes.toString(), Scale.Mega)

    fun roundedUp(megabytes: Int): String {
        if (megabytes < 1_000) return megabytes(megabytes)
        val tenths = (megabytes + 99) / 100
        return joined(NumberText.decimal(tenths / 10.0, 1), Scale.Giga)
    }

    private enum class Scale { Kilo, Mega, Giga }

    private fun joined(number: String, scale: Scale): String {
        val (kilo, mega, giga) = units(Localization.language)
        val unit = when (scale) {
            Scale.Kilo -> kilo
            Scale.Mega -> mega
            Scale.Giga -> giga
        }
        return "$number $unit"
    }

    private fun units(language: UILanguage): Triple<String, String, String> = when (language) {
        UILanguage.French -> Triple("ko", "Mo", "Go")
        UILanguage.Russian, UILanguage.Ukrainian -> Triple("КБ", "МБ", "ГБ")
        UILanguage.Arabic -> Triple("كيلوبايت", "ميغابايت", "غيغابايت")
        UILanguage.Hebrew, UILanguage.English, UILanguage.German, UILanguage.Spanish, UILanguage.Portuguese, UILanguage.Hindi, UILanguage.Amharic, UILanguage.ChineseSimplified -> Triple("KB", "MB", "GB")
    }
}
