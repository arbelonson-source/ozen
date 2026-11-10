package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInSpeechRulesTest {
    private fun support(
        installed: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        supported: List<String> = emptyList(),
        online: List<String> = emptyList(),
    ) = BuiltInSupport(installed, pending, supported, online)

    private fun language(s: BuiltInSupport, code: String = "he") = BuiltInSpeechRules.resolve(34, true, s, code)

    @Test fun installedLanguage() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Installed), language(support(installed = listOf("en-US", "he-IL"))))

    @Test fun downloadableLanguage() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Downloadable), language(support(supported = listOf("he-IL"))))

    @Test fun pendingLanguage() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Downloading), language(support(pending = listOf("he-IL"), supported = listOf("he-IL"))))

    @Test fun onlineOnlyLanguage() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.OnlineOnly), language(support(online = listOf("he-IL"))))

    @Test fun unsupportedLanguage() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Unsupported), language(support(installed = listOf("en-US"), supported = listOf("fr-FR"))))

    @Test fun oldHebrewTagMatches() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Installed), language(support(installed = listOf("iw-IL"))))

    @Test fun oldHebrewSettingMatchesNewTag() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Installed), language(support(installed = listOf("he-IL")), "iw"))

    @Test fun similarPrefixIsNotHebrew() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Unsupported), language(support(installed = listOf("hi-IN", "hea-XX"))))

    @Test fun installedBeatsOnline() = assertEquals(BuiltInSpeech.Language(BuiltInLanguage.Installed), language(support(installed = listOf("he-IL"), online = listOf("he-IL"))))

    @Test fun beforeAndroid12() = assertEquals(BuiltInSpeech.NeedsAndroid12, BuiltInSpeechRules.resolve(30, false, null, "he"))

    @Test fun notAvailableOnDevice() = assertEquals(BuiltInSpeech.NotAvailable, BuiltInSpeechRules.resolve(33, false, null, "he"))

    @Test fun android12CannotCheckLanguage() = assertEquals(BuiltInSpeech.NeedsAndroid13, BuiltInSpeechRules.resolve(31, true, null, "he"))

    @Test fun noAnswer() = assertEquals(BuiltInSpeech.NoAnswer, BuiltInSpeechRules.resolve(34, true, null, "he"))

    @Test fun reportLine() {
        assertEquals(
            "built-in speech recognition: available: yes language he: downloadable",
            BuiltInSpeechRules.reportLine(BuiltInSpeech.Language(BuiltInLanguage.Downloadable), "he"),
        )
        assertEquals("built-in speech recognition: available: needs Android 12 language he: -", BuiltInSpeechRules.reportLine(BuiltInSpeech.NeedsAndroid12, "he"))
    }
}
