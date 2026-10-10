package com.arbelonson.ozen.core

val AppLanguage.bundleLocalization: String?
    get() = when (this) {
        AppLanguage.System -> null
        AppLanguage.Hebrew -> "he"
        AppLanguage.English -> "en"
        AppLanguage.Arabic -> "ar"
        AppLanguage.Russian -> "ru"
        AppLanguage.Amharic -> "am"
        AppLanguage.French -> "fr"
        AppLanguage.Spanish -> "es"
        AppLanguage.Ukrainian -> "uk"
        AppLanguage.German -> "de"
        AppLanguage.Portuguese -> "pt-PT"
        AppLanguage.ChineseSimplified -> "zh-Hans"
        AppLanguage.Hindi -> "hi"
    }

interface SystemLanguageDefaults {
    fun setStringList(key: String, value: List<String>)
    fun setBoolean(key: String, value: Boolean)
    fun getBoolean(key: String): Boolean
    fun remove(key: String)
}

object SystemLanguage {
    const val KEY = "AppleLanguages"
    internal const val WRITTEN_KEY = "OzenWroteAppleLanguages"

    fun apply(setting: AppLanguage, defaults: SystemLanguageDefaults) {
        val code = setting.bundleLocalization
        if (code != null) {
            defaults.setStringList(KEY, listOf(code))
            defaults.setBoolean(WRITTEN_KEY, true)
        } else if (defaults.getBoolean(WRITTEN_KEY)) {
            defaults.remove(KEY)
            defaults.remove(WRITTEN_KEY)
        }
    }
}
