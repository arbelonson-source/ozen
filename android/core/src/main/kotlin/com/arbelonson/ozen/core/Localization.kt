package com.arbelonson.ozen.core

import java.util.Locale

enum class UILanguage(
    val rawValue: String,
    val languageCode: String,
    val nativeName: String,
    val speechVoiceCode: String,
) {
    Hebrew("hebrew", "he", "עברית", "he-IL"),
    English("english", "en", "English", "en-US"),
    Arabic("arabic", "ar", "العربية", "ar-SA"),
    Russian("russian", "ru", "Русский", "ru-RU"),
    Amharic("amharic", "am", "አማርኛ", "am-ET"),
    French("french", "fr", "Français", "fr-FR"),
    Spanish("spanish", "es", "Español", "es-ES"),
    Ukrainian("ukrainian", "uk", "Українська", "uk-UA"),
    German("german", "de", "Deutsch", "de-DE"),
    Portuguese("portuguese", "pt", "Português", "pt-PT"),
    ChineseSimplified("chineseSimplified", "zh", "简体中文", "zh-CN"),
    Hindi("hindi", "hi", "हिन्दी", "hi-IN");

    val isRightToLeft: Boolean get() = this == Hebrew || this == Arabic

    internal val writesInLatinLetters: Boolean
        get() = this == English || this == French || this == Spanish || this == German || this == Portuguese

    /** Simplified Chinese also needs the "Hans" script, since "zh" alone is ambiguous between scripts. */
    val script: String? get() = if (this == ChineseSimplified) "Hans" else null

    /** Dates and weekdays in this language, however the phone is set. */
    val formattingLocale: Locale get() = Locale.forLanguageTag(if (this == ChineseSimplified) "zh-Hans" else languageCode)

    fun locale(keepingRegionOf: Locale): Locale {
        val builder = Locale.Builder().setLanguage(languageCode)
        script?.let { builder.setScript(it) }
        if (keepingRegionOf.country.isNotEmpty()) builder.setRegion(keepingRegionOf.country)
        return builder.build()
    }

    companion object {
        fun fromRawValue(rawValue: String): UILanguage? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * Which voice should read [text] aloud, from its letters. A script
         * only one app language uses decides by itself; Cyrillic picks
         * Ukrainian when it has letters Russian lacks; Latin letters keep the
         * app's language when that is written in Latin letters (a French
         * phrase gets the French voice), and are English otherwise. Text with
         * no letters at all (a time, a number) follows the app's language.
         */
        fun forSpeaking(text: String, otherwise: UILanguage): UILanguage {
            val values = text.codePoints().toArray()
            fun has(vararg ranges: IntRange) = values.any { value -> ranges.any { value in it } }
            if (has(0x0590..0x05FF)) return Hebrew
            // Not U+FEFF at the end of that block: an invisible byte order
            // mark pasted along with a time is no Arabic letter.
            if (has(0x0600..0x06FF, 0x0750..0x077F, 0xFB50..0xFDFF, 0xFE70..0xFEFC)) return Arabic
            if (has(0x1200..0x139F, 0x2D80..0x2DDF)) return Amharic
            if (has(0x0900..0x097F)) return Hindi
            if (has(0x3400..0x4DBF, 0x4E00..0x9FFF)) return ChineseSimplified
            if (has(0x0400..0x04FF)) {
                if (otherwise == Ukrainian || text.any { it in "іїєґІЇЄҐ" }) return Ukrainian
                return Russian
            }
            // Letters only: × and ÷ sit among the accented Latin letters, and
            // read "3 × 4" in English to a Hebrew speaker.
            if (values.any { value ->
                    value in 0x41..0x5A || value in 0x61..0x7A ||
                        (value in 0xC0..0x24F && Character.isAlphabetic(value))
                }
            ) {
                return if (otherwise.writesInLatinLetters) otherwise else English
            }
            return otherwise
        }

        /**
         * Matches a phone preferred-language tag ("pt-BR", "zh-Hans-US",
         * "iw"...) to a supported language by its leading subtag, ignoring
         * script and region. `null` when nothing supported matches.
         */
        fun match(languageTag: String): UILanguage? = when (languageTag.lowercase().split("-").first()) {
            "he", "iw" -> Hebrew
            "en" -> English
            "ar" -> Arabic
            "ru" -> Russian
            "am" -> Amharic
            "fr" -> French
            "es" -> Spanish
            "uk" -> Ukrainian
            "de" -> German
            "pt" -> Portuguese
            "zh" -> ChineseSimplified
            "hi" -> Hindi
            else -> null
        }
    }
}

enum class AppLanguage(val rawValue: String, private val fixed: UILanguage?) {
    System("system", null),
    Hebrew("hebrew", UILanguage.Hebrew),
    English("english", UILanguage.English),
    Arabic("arabic", UILanguage.Arabic),
    Russian("russian", UILanguage.Russian),
    Amharic("amharic", UILanguage.Amharic),
    French("french", UILanguage.French),
    Spanish("spanish", UILanguage.Spanish),
    Ukrainian("ukrainian", UILanguage.Ukrainian),
    German("german", UILanguage.German),
    Portuguese("portuguese", UILanguage.Portuguese),
    ChineseSimplified("chineseSimplified", UILanguage.ChineseSimplified),
    Hindi("hindi", UILanguage.Hindi);

    /**
     * [System] walks the phone's preferred languages in order and uses the
     * first one a supported language matches; Hebrew is the fallback when
     * none of them do.
     */
    fun resolved(preferredLanguages: List<String>): UILanguage =
        fixed ?: preferredLanguages.firstNotNullOfOrNull { UILanguage.match(it) } ?: UILanguage.Hebrew

    companion object {
        fun fromRawValue(rawValue: String): AppLanguage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

object Localization {
    private val override = ThreadLocal<UILanguage?>()

    @Volatile
    private var stored = UILanguage.Hebrew

    var language: UILanguage
        get() = override.get() ?: stored
        set(value) {
            stored = value
        }

    val overridden: UILanguage? get() = override.get()

    val locale: Locale get() = language.locale(keepingRegionOf = Locale.getDefault())

    /** Runs [block] with [language] in use on this thread only, the way a test or a preview needs. */
    fun <T> withLanguage(language: UILanguage, block: () -> T): T {
        val previous = override.get()
        override.set(language)
        try {
            return block()
        } finally {
            if (previous == null) override.remove() else override.set(previous)
        }
    }
}

fun tr(hebrew: String, english: String): String = tr(hebrew, english, Localization.language)

fun tr(hebrew: String, english: String, language: UILanguage): String = readingInOrder(
    when (language) {
        UILanguage.Hebrew -> hebrew
        UILanguage.English -> english
        else -> TranslationTable.lookup(english, language) ?: english
    },
    language,
)

/**
 * For a string built from values: [hebrewTemplate] and [englishTemplate]
 * hold `%1`, `%2`... in place of each value, and [args] gives those values in
 * that order. The translation table is keyed by [englishTemplate], so a
 * translated template can reorder its placeholders for its own grammar.
 */
fun tr(hebrewTemplate: String, englishTemplate: String, args: List<String>): String =
    tr(hebrewTemplate, englishTemplate, args, Localization.language)

fun tr(hebrewTemplate: String, englishTemplate: String, args: List<String>, language: UILanguage): String {
    val template = when (language) {
        UILanguage.Hebrew -> hebrewTemplate
        UILanguage.English -> englishTemplate
        else -> TranslationTable.lookup(englishTemplate, language) ?: englishTemplate
    }
    return readingInOrder(substitutingPlaceholders(template, args), language)
}

/**
 * A line is laid out in the direction of its first letter, so a Hebrew or
 * Arabic line opening with "VoiceOver", "Turbo" or "812 MB" came out left to
 * right, and English opening with a Hebrew name was turned around the same
 * way. A direction mark in front keeps the line in its language's direction.
 */
private fun readingInOrder(text: String, language: UILanguage): String {
    if (text.startsWith(TextDirection.LEFT_TO_RIGHT_MARK) || text.startsWith(TextDirection.RIGHT_TO_LEFT_MARK)) return text
    val opensLeftToRight = TextDirection.opensLeftToRight(text)
    if (language.isRightToLeft) {
        return if (opensLeftToRight) TextDirection.RIGHT_TO_LEFT_MARK + text else text
    }
    val hasLetter = text.codePoints().anyMatch { Character.isAlphabetic(it) }
    return if (hasLetter && !opensLeftToRight) TextDirection.LEFT_TO_RIGHT_MARK + text else text
}

/**
 * Replaces `%1`, `%2`... with `args[0]`, `args[1]`... in one pass from the
 * left, so text already put in is never read again: a caption saying "50%1"
 * stays as it is. The longest number that has an argument wins, so `%10`
 * isn't read as `%1` when there are ten values.
 */
private fun substitutingPlaceholders(template: String, args: List<String>): String {
    if (args.isEmpty()) return template
    val result = StringBuilder()
    var index = 0
    while (index < template.length) {
        val percent = template.indexOf('%', index)
        if (percent < 0) break
        result.append(template, index, percent)
        val afterPercent = percent + 1
        var end = afterPercent
        while (end < template.length && template[end] in '0'..'9') end++
        var digits = template.substring(afterPercent, end)
        while (digits.length > 1) {
            val number = digits.toIntOrNull() ?: break
            if (number <= args.size) break
            digits = digits.dropLast(1)
        }
        val number = digits.toIntOrNull()
        if (number != null && number in 1..args.size) {
            result.append(args[number - 1])
            index = afterPercent + digits.length
        } else {
            result.append('%')
            index = afterPercent
        }
    }
    result.append(template, index, template.length)
    return result.toString()
}
