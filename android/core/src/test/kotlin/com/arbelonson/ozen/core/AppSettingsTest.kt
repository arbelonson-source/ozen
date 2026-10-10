package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun decode(json: String): AppSettings = AppSettings.fromJson(json)

private fun roundTripped(settings: AppSettings): AppSettings = AppSettings.fromJson(settings.toJson())

private fun <T> withSettingsFolder(prefix: String, body: (File) -> T): T {
    val folder = File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID()}")
    try {
        return body(folder)
    } finally {
        folder.deleteRecursively()
    }
}

class AppSettingsTest {
    @Test
    fun `the display she starts with, large dark text, not bold, names shown, the screen kept on, every helper on`() {
        val display = AppSettings.default.display
        assertEquals(DisplayPreferences.default, display)
        assertEquals(30.0, display.fontSize)
        assertEquals(DisplayPreferences.Theme.Dark, display.theme)
        assertFalse(display.boldText)
        assertTrue(display.showSpeakerNames)
        assertTrue(display.keepScreenAwake)
        assertTrue(display.markUncertainLines)
        assertTrue(display.announceNewLines)
        assertTrue(display.emphasizeNumbers)
        assertTrue(display.lockScreenCaptions)
        assertTrue(display.autoHideControls)
    }

    @Test
    fun `default settings use Whisper, Hebrew, and the requested credit line`() {
        val settings = AppSettings.default
        assertEquals(TranscriptionEngineKind.WhisperKit, settings.engine)
        assertEquals("he", settings.languageCode)
        assertEquals("Arbel", settings.creditLine)
        assertTrue(settings.speakerProfiles.isEmpty())
    }

    @Test
    fun `the credit line is the name alone, without the words its Settings label already says`() {
        for (language in UILanguage.entries) {
            val label = tr("נוצר על ידי", "Made by", language)
            assertFalse(AppSettings.default.creditLine.contains(label, ignoreCase = true), "$language")
        }
        assertFalse(AppSettings.default.creditLine.contains("made by", ignoreCase = true))
    }

    @Test
    fun `settings round-trip through JSON without losing data`() {
        val original = AppSettings(
            engine = TranscriptionEngineKind.AppleSpeech,
            languageCode = "he",
            preferredInputUID = "airpods-123",
            speakerProfiles = listOf(SpeakerProfile(name = "סבתא", embedding = floatArrayOf(0.1f, 0.2f, 0.3f))),
            creditLine = "Arbel",
        )
        assertEquals(original, roundTripped(original))
    }

    @Test
    fun `a voice saved with the old loudness number keeps matching, that number is dropped on load`() {
        val oldPrint = floatArrayOf(-180f, 12f, -3f, 4f, -5f, 6f, -7f, 8f, -9f, 10f, -11f, 1.5f, -0.5f)
        val other = floatArrayOf(1f, 2f, 3f)
        val saved = AppSettings(
            engine = TranscriptionEngineKind.WhisperKit,
            languageCode = "he",
            preferredInputUID = null,
            speakerProfiles = listOf(
                SpeakerProfile(name = "old", embedding = oldPrint),
                SpeakerProfile(name = "current", embedding = oldPrint.copyOfRange(1, oldPrint.size)),
                SpeakerProfile(name = "other", embedding = other),
            ),
            creditLine = "Arbel",
        )
        val decoded = roundTripped(saved)
        assertEquals(
            listOf(oldPrint.copyOfRange(1, oldPrint.size).toList(), oldPrint.copyOfRange(1, oldPrint.size).toList(), other.toList()),
            decoded.speakerProfiles.map { it.embedding.toList() },
        )
        assertTrue(decoded.speakerProfiles.map { it.embedding.size }.take(2).all { it == SpeakerProfile.voicePrintLength })
    }

    @Test
    fun `loading with no file on disk yet returns defaults instead of throwing`() {
        withSettingsFolder("ozen-settings-missing") { folder ->
            val store = SettingsStore(File(folder, "ozen-settings.json"))
            assertEquals(AppSettings.default, store.load())
        }
    }

    @Test
    fun `save then load returns exactly what was saved`() {
        withSettingsFolder("ozen-settings") { folder ->
            val store = SettingsStore(File(folder, "ozen-settings.json"))
            val settings = AppSettings.default
            settings.preferredInputUID = "usb-lav-1"
            settings.speakerProfiles = listOf(SpeakerProfile(name = "Chen", embedding = floatArrayOf(1f, 2f, 3f)))

            store.save(settings)
            val loaded = store.load()
            assertEquals(settings, loaded)
        }
    }

    @Test
    fun `a corrupted settings file falls back to defaults instead of crashing the app`() {
        withSettingsFolder("ozen-settings-corrupt") { folder ->
            folder.mkdirs()
            val file = File(folder, "ozen-settings.json")
            file.writeBytes("not valid json".toByteArray())
            val store = SettingsStore(file)
            assertEquals(AppSettings.default, store.load())
        }
    }

    @Test
    fun `a damaged settings file is kept aside, so saving the defaults doesn't destroy it`() {
        withSettingsFolder("ozen-settings") { folder ->
            folder.mkdirs()
            val file = File(folder, "ozen-settings.json")
            file.writeBytes("{\"speakerProfiles\": [trunc".toByteArray())
            val store = SettingsStore(file)

            val loaded = store.load()
            store.save(loaded)
            val keptAfterSave = store.damagedCopy.readText(Charsets.UTF_8)
            file.writeBytes("second damage".toByteArray())
            store.load()

            assertEquals(AppSettings.default, loaded)
            assertEquals("ozen-settings.damaged.json", store.damagedCopy.name)
            assertEquals("{\"speakerProfiles\": [trunc", keptAfterSave)
            val kept = store.damagedCopy.readText(Charsets.UTF_8)
            assertEquals("second damage", kept)
        }
    }

    @Test
    fun `a readable file, or no file at all, leaves nothing aside`() {
        withSettingsFolder("ozen-settings") { folder ->
            val store = SettingsStore(File(folder, "ozen-settings.json"))
            store.load()
            store.save(AppSettings.default)
            store.load()
            assertFalse(store.damagedCopy.exists())
        }
    }

    @Test
    fun `a settings file from an older build (missing every newer key) still loads, keeping its speaker profiles`() {
        val legacy = """
            {"engine":"appleSpeech","languageCode":"he","preferredInputUID":"airpods-1",
             "speakerProfiles":[{"id":"1E2B4D2A-6C5F-4F1B-9C3E-000000000001","name":"סבתא","embedding":[0.5,0.25]}],
             "creditLine":"whatever an old build wrote"}
        """.trimIndent()
        val decoded = decode(legacy)

        assertEquals(TranscriptionEngineKind.AppleSpeech, decoded.engine)
        assertEquals("airpods-1", decoded.preferredInputUID)
        assertEquals(listOf("סבתא"), decoded.speakerProfiles.map { it.name })
        assertEquals(AppSettings.default.whisperModelVariant, decoded.whisperModelVariant)
        assertEquals(false, decoded.allowServerFallbackForAppleSpeech)
        assertEquals(DisplayPreferences.default, decoded.display)
        assertEquals(true, decoded.hapticOnSpeechResume)
        assertEquals(0.45f, decoded.speakerSimilarityThreshold)
        assertTrue(decoded.keywordAlerts.isEmpty())
        assertEquals(SoundAlertPreferences.default, decoded.soundAlerts)
        assertEquals(true, decoded.saveHistory)
        assertEquals(AppSettings.defaultQuickPhrases, decoded.quickPhrases)
        assertEquals(0.45f, decoded.speechRate)
        assertTrue(decoded.vocabulary.isEmpty())
        assertEquals(false, decoded.hasCompletedOnboarding)
        assertEquals(true, decoded.notifyWhenInBackground)
        assertEquals("Arbel", decoded.creditLine)
    }

    @Test
    fun `an empty JSON object decodes to the defaults, but for the cloud service, which a file from before the choice meant as OpenRouter`() {
        val decoded = decode("{}")
        val expected = AppSettings.default
        expected.cloudProvider = CloudProvider.OpenRouter
        assertEquals(expected, decoded)
    }

    @Test
    fun `the model shown for a conversation is the one its engine used`() {
        val settings = AppSettings.default
        settings.whisperModelVariant = "small"
        settings.cloudProvider = CloudProvider.OpenRouter
        settings.cloudModel = CloudSpeech.ACCURATE_MODEL
        settings.engine = TranscriptionEngineKind.WhisperKit
        val whisper = settings.modelDescription
        settings.engine = TranscriptionEngineKind.Cloud
        val cloud = settings.modelDescription
        settings.engine = TranscriptionEngineKind.AppleSpeech
        val apple = settings.modelDescription
        assertEquals("small", whisper)
        assertEquals(CloudSpeech.ACCURATE_MODEL, cloud)
        assertNull(apple)
    }

    @Test
    fun `an empty cloud model name falls back to the default model`() {
        val decoded = decode("""{"cloudModel":""}""")
        assertEquals(CloudSpeech.ACCURATE_MODEL, decoded.cloudModel)
        assertTrue(decoded.display.autoHideControls)
        assertEquals(AppLanguage.System, decoded.appLanguage)
    }

    @Test
    fun `settings from before the cloud service choice keep OpenRouter, a service this build doesn't know falls back to it, a chosen one is kept`() {
        val old = decode("""{"cloudModel":"google/gemini-3.1-flash-lite"}""")
        assertEquals(CloudProvider.OpenRouter, old.cloudProvider)
        assertEquals("google/gemini-3.1-flash-lite", old.chosenCloudModel)
        val unknown = decode("""{"cloudProvider":"someday"}""")
        assertEquals(CloudProvider.OpenRouter, unknown.cloudProvider)
        val settings = AppSettings.default
        settings.cloudProvider = CloudProvider.Deepgram
        assertEquals(CloudProvider.Deepgram, roundTripped(settings).cloudProvider)
    }

    @Test
    fun `a new install starts on the recommended cloud service, Soniox, saved settings without one keep OpenRouter, whose key they were set up with`() {
        assertEquals(CloudProvider.Soniox, AppSettings.default.cloudProvider)
        assertEquals(CloudProvider.Soniox, roundTripped(AppSettings.default).cloudProvider)
        val saved = decode("""{"engine":"cloud"}""")
        assertEquals(CloudProvider.OpenRouter, saved.cloudProvider)
    }

    @Test
    fun `a cloud model left from another service is never sent to the chosen one`() {
        val settings = AppSettings.default
        settings.cloudProvider = CloudProvider.OpenRouter
        settings.cloudModel = CloudSpeech.FAST_MODEL
        assertEquals(CloudSpeech.FAST_MODEL, settings.chosenCloudModel)
        settings.cloudProvider = CloudProvider.Deepgram
        assertEquals(CloudProvider.Deepgram.defaultModel, settings.chosenCloudModel)
        assertTrue(CloudProvider.Deepgram.models.contains(settings.chosenCloudModel))
        settings.engine = TranscriptionEngineKind.Cloud
        assertEquals(settings.chosenCloudModel, settings.modelDescription)
    }

    @Test
    fun `a language name from a newer build falls back to following the phone`() {
        val decoded = decode("""{"appLanguage":"klingon"}""")
        assertEquals(AppLanguage.System, decoded.appLanguage)
    }

    @Test
    fun `newer settings round-trip through JSON intact`() {
        val settings = AppSettings.default
        settings.whisperModelVariant = "large-v3_turbo"
        settings.allowServerFallbackForAppleSpeech = true
        settings.cloudModel = CloudSpeech.ACCURATE_MODEL
        settings.appLanguage = AppLanguage.English
        settings.display = DisplayPreferences(
            fontSize = 44.0,
            theme = DisplayPreferences.Theme.HighContrast,
            boldText = true,
            showSpeakerNames = false,
            keepScreenAwake = false,
            autoHideControls = false,
        )
        settings.hapticOnSpeechResume = false
        settings.speakerSimilarityThreshold = 0.6f
        settings.homeServerBeam = 2
        settings.keywordAlerts = listOf(KeywordAlert(phrase = "סבתא"), KeywordAlert(phrase = "תרופה", isEnabled = false))
        settings.soundAlerts = SoundAlertPreferences(
            isEnabled = true,
            minimumImportance = SoundEvent.Importance.High,
            mutedIdentifiers = setOf("music"),
        )
        settings.saveHistory = false
        settings.quickPhrases = listOf("כן", "לא")
        settings.speechRate = 0.6f
        settings.vocabulary = listOf("אבי", "רותי")
        settings.hasCompletedOnboarding = true
        settings.notifyWhenInBackground = false

        assertEquals(settings, roundTripped(settings))
    }

    @Test
    fun `an out-of-range speech rate is clamped so the voice stays intelligible`() {
        val fast = decode("""{"speechRate":5}""")
        assertEquals(0.7f, fast.speechRate)
        val slow = decode("""{"speechRate":0}""")
        assertEquals(0.2f, slow.speechRate)
    }

    @Test
    fun `an out-of-range saved font size is clamped on load rather than rendering unreadable text`() {
        val tiny = DisplayPreferences.fromJson("""{"fontSize":4}""")
        assertEquals(DisplayPreferences.minimumFontSize, tiny.fontSize)
        val huge = DisplayPreferences.fromJson("""{"fontSize":400}""")
        assertEquals(DisplayPreferences.maximumFontSize, huge.fontSize)
    }

    @Test
    fun `the README's text size range is the one the slider and the pinch allow`() {
        val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
        val readme = File(root, "README.md").readText(Charsets.UTF_8)
        val low = DisplayPreferences.minimumFontSize.toInt()
        val high = DisplayPreferences.maximumFontSize.toInt()
        assertTrue(readme.contains("Text from $low to $high pt"))
        assertTrue(readme.contains("text size $low–$high pt"))
    }

    @Test
    fun `a pinch lands on a whole-point size inside the readable range`() {
        assertEquals(36.0, DisplayPreferences.fontSize(30.0, scaledBy = 1.2))
        assertEquals(31.0, DisplayPreferences.fontSize(30.0, scaledBy = 1.017))
        assertEquals(DisplayPreferences.maximumFontSize, DisplayPreferences.fontSize(30.0, scaledBy = 10.0))
        assertEquals(DisplayPreferences.minimumFontSize, DisplayPreferences.fontSize(30.0, scaledBy = 0.1))
        assertEquals(30.0, DisplayPreferences.fontSize(30.0, scaledBy = 0.0))
        assertEquals(30.0, DisplayPreferences.fontSize(30.0, scaledBy = Double.NaN))
    }

    @Test
    fun `a saved line is never smaller than the phone's own body text, nor than seven tenths of the captions`() {
        assertEquals(21.0, DisplayPreferences.savedLineSize(captionSize = 30.0, bodySize = 17.0))
        assertEquals(17.0, DisplayPreferences.savedLineSize(captionSize = 20.0, bodySize = 17.0))
        assertEquals(53.0, DisplayPreferences.savedLineSize(captionSize = 30.0, bodySize = 53.0))
        assertEquals(64 * 0.7, DisplayPreferences.savedLineSize(captionSize = 64.0, bodySize = 23.0))
    }
}

class AppSettingsFreshInstallTest {
    @Test
    fun `saving works even when the folder doesn't exist yet, and the walkthrough stays done`() {
        withSettingsFolder("ozen-fresh") { base ->
            val file = File(File(base, "Application Support"), "ozen-settings.json")
            val store = SettingsStore(file)

            val settings = store.load()
            settings.hasCompletedOnboarding = true
            store.save(settings)

            assertTrue(SettingsStore(file).load().hasCompletedOnboarding)
        }
    }
}

class BetterModelOfferTest {
    private val day = 86_400.0
    private val start = 1_800_000_000.0
    private val onSmall = """{"hasCompletedOnboarding":true,"whisperModelVariant":"small"}"""

    @Test
    fun `offered to a phone still on Small from before it stopped being the default, until taken`() {
        val small = decode(onSmall)
        assertTrue(small.offersBetterModel())
        assertTrue(small.runsWeakerModel)

        val switched = small.copy()
        switched.whisperModelVariant = WhisperModelCatalog.RECOMMENDED_VARIANT
        assertEquals(false, switched.offersBetterModel())
        assertEquals(false, switched.runsWeakerModel)
    }

    @Test
    fun `Not now puts the offer away for three days, then two weeks, then two months, never for good`() {
        val settings = decode(onSmall)

        settings.snoozeBetterModelOffer(start)
        val saved = roundTripped(settings)
        assertEquals(false, saved.offersBetterModel(start + 2.9 * day))
        assertTrue(saved.offersBetterModel(start + 3.1 * day))

        settings.snoozeBetterModelOffer(start)
        assertEquals(false, settings.offersBetterModel(start + 13 * day))
        assertTrue(settings.offersBetterModel(start + 15 * day))

        settings.snoozeBetterModelOffer(start)
        settings.snoozeBetterModelOffer(start)
        assertEquals(false, settings.offersBetterModel(start + 59 * day))
        assertTrue(settings.offersBetterModel(start + 61 * day))
    }

    @Test
    fun `the count of Not now survives a relaunch, so the next one still waits two weeks`() {
        val settings = decode(onSmall)
        settings.snoozeBetterModelOffer(start)

        val relaunched = roundTripped(settings)
        assertEquals(1, relaunched.betterModelOfferDeclines)
        relaunched.snoozeBetterModelOffer(start)
        assertEquals(false, relaunched.offersBetterModel(start + 13 * day))

        val damaged = decode("""{"hasCompletedOnboarding":true,"whisperModelVariant":"small","betterModelOfferDeclines":-4}""")
        assertEquals(0, damaged.betterModelOfferDeclines)
    }

    @Test
    fun `a phone that turned the offer down for good in an older build is asked once more`() {
        val old = decode("""{"hasCompletedOnboarding":true,"whisperModelVariant":"small","betterModelOfferDismissed":true}""")
        assertTrue(old.offersBetterModel())
    }

    @Test
    fun `not offered to a fresh install, to a phone still in the walkthrough, or to one on another engine`() {
        assertEquals(false, AppSettings.default.offersBetterModel())

        val inWalkthrough = decode("""{"whisperModelVariant":"small"}""")
        assertEquals(false, inWalkthrough.offersBetterModel())

        val onApple = decode(onSmall)
        onApple.engine = TranscriptionEngineKind.AppleSpeech
        assertEquals(false, onApple.offersBetterModel())

        val onCloud = onApple.copy()
        onCloud.engine = TranscriptionEngineKind.Cloud
        assertEquals(false, onCloud.offersBetterModel())
    }
}

class NameAlertOfferTest {
    @Test
    fun `offered to a phone set up before the walkthrough asked, until a word is added or it's turned down`() {
        val fromOlderBuild = decode("""{"hasCompletedOnboarding":true}""")
        assertEquals(false, fromOlderBuild.nameAlertOfferDismissed)
        assertTrue(fromOlderBuild.offersNameAlert)

        val withName = fromOlderBuild.copy()
        withName.keywordAlerts = listOf(KeywordAlert(phrase = "רותי"))
        assertEquals(false, withName.offersNameAlert)

        val turnedDown = fromOlderBuild.copy()
        turnedDown.nameAlertOfferDismissed = true
        assertEquals(false, roundTripped(turnedDown).offersNameAlert)

        assertEquals(false, AppSettings.default.offersNameAlert)
    }
}

class SavedSpeakerTest {
    @Test
    fun `a name saved for several voices is one person, listed where it was first saved`() {
        val first = SpeakerProfile(name = "Dana", embedding = floatArrayOf(1f))
        val other = SpeakerProfile(name = "Avi", embedding = floatArrayOf(2f))
        val second = SpeakerProfile(name = "Dana", embedding = floatArrayOf(3f))
        val speakers = SavedSpeaker.grouping(listOf(first, other, second))
        assertEquals(listOf("Dana", "Avi"), speakers.map { it.name })
        assertEquals(listOf(first.id, second.id), speakers.first().profileIDs)
        assertEquals(listOf(other.id), speakers.last().profileIDs)
        assertTrue(SavedSpeaker.grouping(emptyList()).isEmpty())
    }

    @Test
    fun `every interface language has its own ready-made phrases, same count as Hebrew, and an untouched list switches to them`() {
        val hebrew = AppSettings.defaultQuickPhrases
        val seen = HashSet<List<String>>()
        for (language in UILanguage.entries) {
            val phrases = AppSettings.defaultQuickPhrases(language)
            assertEquals(hebrew.size, phrases.size, "$language")
            assertFalse(phrases.any { it.isEmpty() }, "$language")
            seen.add(phrases)
        }
        assertEquals(UILanguage.entries.size, seen.size)
        val arabic = AppSettings.defaultQuickPhrases(UILanguage.Arabic)
        assertEquals(arabic, AppSettings.displayedQuickPhrases(hebrew, UILanguage.Arabic))
        assertEquals(
            AppSettings.defaultQuickPhrases(UILanguage.Russian),
            AppSettings.displayedQuickPhrases(arabic, UILanguage.Russian),
        )
        assertEquals(hebrew, AppSettings.displayedQuickPhrases(arabic, UILanguage.Hebrew))
    }

    @Test
    fun `the untouched ready-made phrases follow the app's language, an edited list does not`() {
        val hebrew = AppSettings.defaultQuickPhrases
        val english = AppSettings.defaultQuickPhrasesEnglish
        assertEquals(english.size, hebrew.size)
        assertEquals(english, AppSettings.displayedQuickPhrases(hebrew, UILanguage.English))
        assertEquals(hebrew, AppSettings.displayedQuickPhrases(hebrew, UILanguage.Hebrew))
        assertEquals(hebrew, AppSettings.displayedQuickPhrases(english, UILanguage.Hebrew))
        val edited = hebrew + listOf("תודה רבה")
        assertEquals(edited, AppSettings.displayedQuickPhrases(edited, UILanguage.English))
        assertTrue(AppSettings.displayedQuickPhrases(emptyList(), UILanguage.English).isEmpty())
    }

    @Test
    fun `the home computer's beam defaults to 5, and a file outside 1 to 7 gets the nearest edge`() {
        assertEquals(5, AppSettings.default.homeServerBeam)
        assertEquals(5, decode("{}").homeServerBeam)
        assertEquals(3, decode("""{"homeServerBeam":3}""").homeServerBeam)
        assertEquals(7, decode("""{"homeServerBeam":7}""").homeServerBeam)
        assertEquals(7, decode("""{"homeServerBeam":9}""").homeServerBeam)
        assertEquals(1, decode("""{"homeServerBeam":-2}""").homeServerBeam)
        assertEquals(5, decode("""{"homeServerBeam":"fast"}""").homeServerBeam)
    }

    @Test
    fun `the match-the-phone look is saved and read back, and a look this build doesn't know falls back to the default`() {
        val display = DisplayPreferences.default.copy(theme = DisplayPreferences.Theme.MatchPhone)
        val json = display.toJson()
        assertEquals(DisplayPreferences.Theme.MatchPhone, DisplayPreferences.fromJson(json).theme)
        val unknown = json.replace("matchPhone", "sepia")
        assertEquals(DisplayPreferences.default.theme, DisplayPreferences.fromJson(unknown).theme)
    }
}

class AppSettingsThresholdDecodingTest {
    @Test
    fun `a threshold outside the slider's range is brought back to its nearest edge`() {
        fun threshold(value: String): Float = decode("""{"speakerSimilarityThreshold":$value}""").speakerSimilarityThreshold
        assertEquals(0.2f, threshold("0"))
        assertEquals(0.2f, threshold("-3"))
        assertEquals(0.95f, threshold("7"))
        assertEquals(0.8f, threshold("0.8"))
    }

    @Test
    fun `an old file's untouched 0_75 becomes today's default, a choice made since is kept`() {
        assertEquals(AppSettings.default.speakerSimilarityThreshold, decode("""{"speakerSimilarityThreshold":0.75}""").speakerSimilarityThreshold)
        assertEquals(0.6f, decode("""{"speakerSimilarityThreshold":0.6}""").speakerSimilarityThreshold)

        val chosen = AppSettings.default
        chosen.speakerSimilarityThreshold = 0.75f
        assertEquals(0.75f, roundTripped(chosen).speakerSimilarityThreshold)
    }
}

class DisplayPreferencesSettingTest {
    @Test
    fun `the numbers setting is on by default, survives older settings files, and can be turned off`() {
        assertTrue(DisplayPreferences.default.emphasizeNumbers)
        assertTrue(DisplayPreferences.fromJson("""{"fontSize":30}""").emphasizeNumbers)
        val off = DisplayPreferences.default.copy(emphasizeNumbers = false)
        assertEquals(false, DisplayPreferences.fromJson(off.toJson()).emphasizeNumbers)
    }

    @Test
    fun `announcing is on by default and survives settings files from before it existed`() {
        assertTrue(DisplayPreferences.default.announceNewLines)
        assertTrue(DisplayPreferences.fromJson("""{"fontSize":40}""").announceNewLines)
        val off = DisplayPreferences.default.copy(announceNewLines = false)
        assertEquals(false, DisplayPreferences.fromJson(off.toJson()).announceNewLines)
    }

    @Test
    fun `marking unsure lines is on by default and survives older settings files`() {
        assertTrue(DisplayPreferences.default.markUncertainLines)
        assertTrue(DisplayPreferences.fromJson("""{"fontSize":30}""").markUncertainLines)
    }

    @Test
    fun `the lock screen setting is on by default, survives older settings files, and can be turned off`() {
        assertTrue(DisplayPreferences.default.lockScreenCaptions)
        assertTrue(DisplayPreferences.fromJson("""{"fontSize":30}""").lockScreenCaptions)
        val off = DisplayPreferences.default.copy(lockScreenCaptions = false)
        assertEquals(false, DisplayPreferences.fromJson(off.toJson()).lockScreenCaptions)
    }
}

class OtherSettingsDecodingTest {
    @Test
    fun `the cellular download setting defaults to off and survives older settings files`() {
        assertEquals(false, AppSettings.default.allowCellularModelDownload)
        val old = decode("""{"engine":"whisperKit"}""")
        assertEquals(false, old.allowCellularModelDownload)
        val settings = AppSettings.default
        settings.allowCellularModelDownload = true
        assertTrue(roundTripped(settings).allowCellularModelDownload)
    }

    @Test
    fun `AppSettings cleans the vocabulary on construction so callers cannot store junk`() {
        val settings = AppSettings.default
        settings.vocabulary = listOf("x")
        val built = AppSettings(
            engine = TranscriptionEngineKind.WhisperKit,
            languageCode = "he",
            preferredInputUID = null,
            speakerProfiles = emptyList(),
            creditLine = "",
            vocabulary = listOf(" a ", "a"),
        )
        assertEquals(listOf("a"), built.vocabulary)
        assertEquals(listOf("x"), settings.vocabulary)
    }

    @Test
    fun `the address survives a save, and settings saved before it existed load with none`() {
        val settings = AppSettings.default
        settings.homeServerAddress = "grandma-pc:8765"
        val json = settings.toJson()
        assertEquals("grandma-pc:8765", decode(json).homeServerAddress)
        val old = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val withoutAddress = kotlinx.serialization.json.JsonObject(old.filterKeys { it != "homeServerAddress" })
        assertEquals("", decode(withoutAddress.toString()).homeServerAddress)
    }

    @Test
    fun `every language's ready-made phrases are read by that language's voice`() {
        for (language in UILanguage.entries) {
            for (phrase in AppSettings.defaultQuickPhrases(language)) {
                assertEquals(language, UILanguage.forSpeaking(phrase, otherwise = language), "$language: $phrase")
            }
        }
    }
}
