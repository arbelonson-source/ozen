package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One person's saved voice profile: a reference embedding recorded during
 * enrollment, used to seed `EmbeddingClusterer` so their turns are labeled
 * by name from the very first utterance instead of only after the app
 * happens to cluster them correctly on its own.
 */
class SpeakerProfile(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    var embedding: FloatArray,
) {
    /**
     * A voice print that can be compared and written back to disk: JSON
     * has no NaN, so one non-finite number would make every later settings
     * save fail.
     */
    internal val isUsable: Boolean
        get() = embedding.isNotEmpty() && embedding.all { it.isFinite() }

    /**
     * This profile as the current embedder would have made it. Prints
     * saved by earlier builds had one more number in front, the loudness
     * of the recording, which made every voice look alike (see
     * `MFCCSpeakerEmbedder`). The rest of the print is exactly what the
     * embedder makes now, so dropping it keeps a saved voice working
     * instead of silently never matching anyone again.
     */
    internal val withCurrentVoicePrint: SpeakerProfile
        get() = if (embedding.size == voicePrintLength + 1) {
            SpeakerProfile(id, name, embedding.copyOfRange(1, embedding.size))
        } else {
            this
        }

    fun copy(id: UUID = this.id, name: String = this.name, embedding: FloatArray = this.embedding) =
        SpeakerProfile(id, name, embedding.copyOf())

    override fun equals(other: Any?): Boolean =
        other is SpeakerProfile && id == other.id && name == other.name && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = (id.hashCode() * 31 + name.hashCode()) * 31 + embedding.contentHashCode()

    override fun toString(): String = "SpeakerProfile($id, $name, ${embedding.toList()})"

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", settingsUuidText(id))
        put("name", name)
        put("embedding", JsonArray(embedding.map { settingsFloat(it) }))
    }

    companion object {
        /** How many numbers a voice print from the MFCC embedder has. */
        const val voicePrintLength = 12

        internal fun fromElement(element: JsonElement): SpeakerProfile? {
            val container = element as? JsonObject ?: return null
            val id = container["id"].settingsString()?.takeIf { settingsUuidPattern.matches(it) }?.let { UUID.fromString(it) }
                ?: return null
            val name = container["name"].settingsString() ?: return null
            val numbers = container["embedding"] as? JsonArray ?: return null
            val embedding = FloatArray(numbers.size)
            for ((index, number) in numbers.withIndex()) {
                embedding[index] = number.settingsFloat() ?: return null
            }
            return SpeakerProfile(id, name, embedding)
        }
    }
}

/**
 * One person in the saved speakers list, however many voice prints they
 * have. Naming a second voice with a name already saved (the same person
 * heard from across the room, or on a cold day) adds a print rather than
 * a person, so the list shows the name once, and renaming or deleting it
 * covers every print: renaming just one left the other to bring the old
 * name back on the next launch.
 */
data class SavedSpeaker(val name: String, val profileIDs: List<UUID>) {
    val id: String get() = name

    companion object {
        /** The profiles grouped by name, in the order each name was first saved. */
        fun grouping(profiles: List<SpeakerProfile>): List<SavedSpeaker> {
            val order = mutableListOf<String>()
            val ids = HashMap<String, MutableList<UUID>>()
            for (profile in profiles) {
                if (ids[profile.name] == null) order.add(profile.name)
                ids.getOrPut(profile.name) { mutableListOf() }.add(profile.id)
            }
            return order.map { SavedSpeaker(it, ids[it] ?: emptyList()) }
        }
    }
}

/**
 * How the caption screen looks. Every one of these exists because the
 * primary reader is an older person following a conversation in real
 * time: text size and contrast are accessibility controls here, not
 * cosmetics.
 */
data class DisplayPreferences(
    val fontSize: Double = 30.0,
    val theme: Theme = Theme.Dark,
    val boldText: Boolean = false,
    val showSpeakerNames: Boolean = true,
    val keepScreenAwake: Boolean = true,
    /** A small question mark on finished lines the engine was unsure of. */
    val markUncertainLines: Boolean = true,
    /**
     * With a screen reader on, finished lines are read out (or sent to a braille
     * display) as they arrive. See `CaptionAnnouncer`.
     */
    val announceNewLines: Boolean = true,
    /**
     * Numbers (times, amounts, phone numbers) drawn heavier and in their
     * own colour. See `NumberEmphasis`.
     */
    val emphasizeNumbers: Boolean = true,
    /**
     * The newest lines on the lock screen while captions run. See
     * `LockScreenCaptions`.
     */
    val lockScreenCaptions: Boolean = true,
    /**
     * The buttons at the bottom slide away while captions run on their
     * own, so they don't cover the newest line. See `ControlBarAutoHide`.
     */
    val autoHideControls: Boolean = true,
) {
    enum class Theme(val rawValue: String) {
        /**
         * White text on black, the default, easiest on the eyes for a
         * whole conversation.
         */
        Dark("dark"),

        /**
         * Yellow text on black, the classic high-contrast caption look,
         * noticeably easier for many readers with reduced vision.
         */
        HighContrast("highContrast"),

        /** Black text on white, for bright rooms. */
        Light("light"),

        /**
         * White on black or black on white, following the phone's own
         * light/dark setting, so it changes with the room when the phone is
         * set to switch automatically at sunset.
         */
        MatchPhone("matchPhone");

        companion object {
            fun fromRawValue(rawValue: String): Theme? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    fun toJson(): String = toJsonObject().toString()

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("fontSize", settingsDouble(fontSize))
        put("theme", theme.rawValue)
        put("boldText", boldText)
        put("showSpeakerNames", showSpeakerNames)
        put("keepScreenAwake", keepScreenAwake)
        put("markUncertainLines", markUncertainLines)
        put("announceNewLines", announceNewLines)
        put("emphasizeNumbers", emphasizeNumbers)
        put("lockScreenCaptions", lockScreenCaptions)
        put("autoHideControls", autoHideControls)
    }

    companion object {
        const val minimumFontSize: Double = 20.0
        const val maximumFontSize: Double = 64.0

        val default = DisplayPreferences()

        /**
         * The text size a pinch of [scaledBy] lands on: clamped to the readable
         * range and rounded to whole points, so a pinch never leaves the
         * setting at 31.847 or pushes it off either end.
         */
        fun fontSize(base: Double, scaledBy: Double): Double {
            if (!scaledBy.isFinite() || !(scaledBy > 0)) return minOf(maxOf(base, minimumFontSize), maximumFontSize)
            return minOf(maxOf(roundedAwayFromZero(base * scaledBy), minimumFontSize), maximumFontSize)
        }

        /**
         * A line in History or Starred lines: seven tenths of the captions,
         * but never below the phone's body text (17 points until she raises
         * it in the system settings). Held at 17 instead, at the largest text
         * sizes the time and name above each line came out bigger than the
         * line itself.
         */
        fun savedLineSize(captionSize: Double, bodySize: Double): Double = maxOf(bodySize, captionSize * 0.7)

        fun fromJson(json: String): DisplayPreferences = fromElement(Json.parseToJsonElement(json))

        internal fun fromElement(element: JsonElement): DisplayPreferences {
            val container = element as? JsonObject ?: throw IllegalArgumentException("display preferences are not an object")
            val defaults = default
            val size = container.lenientDouble("fontSize") ?: defaults.fontSize
            return DisplayPreferences(
                fontSize = minOf(maxOf(size, minimumFontSize), maximumFontSize),
                theme = container.lenientString("theme")?.let { Theme.fromRawValue(it) } ?: defaults.theme,
                boldText = container.lenientBoolean("boldText") ?: defaults.boldText,
                showSpeakerNames = container.lenientBoolean("showSpeakerNames") ?: defaults.showSpeakerNames,
                keepScreenAwake = container.lenientBoolean("keepScreenAwake") ?: defaults.keepScreenAwake,
                markUncertainLines = container.lenientBoolean("markUncertainLines") ?: defaults.markUncertainLines,
                announceNewLines = container.lenientBoolean("announceNewLines") ?: defaults.announceNewLines,
                emphasizeNumbers = container.lenientBoolean("emphasizeNumbers") ?: defaults.emphasizeNumbers,
                lockScreenCaptions = container.lenientBoolean("lockScreenCaptions") ?: defaults.lockScreenCaptions,
                autoHideControls = container.lenientBoolean("autoHideControls") ?: defaults.autoHideControls,
            )
        }
    }
}

/**
 * Everything Ozen remembers between launches. Small enough that a JSON
 * file (see `SettingsStore`) is the right amount of persistence machinery:
 * no database needed for a single-user app.
 *
 * Decoding is tolerant of missing keys on purpose: every new setting added
 * in a later build must not throw away the enrolled speaker profiles a
 * previous build saved, so anything absent from the file on disk takes
 * its default instead of failing the whole decode.
 *
 * A value: its properties are assignable and [copy] gives an independent
 * settings object, the way Swift's struct copies on assignment.
 */
class AppSettings(
    var engine: TranscriptionEngineKind,
    var languageCode: String,
    var preferredInputUID: String?,
    var speakerProfiles: List<SpeakerProfile>,
    var creditLine: String,
    /**
     * WhisperKit model variant, e.g. "small" or "large-v3_turbo". See
     * `WhisperModelCatalog` for the options actually offered.
     */
    var whisperModelVariant: String = WhisperModelCatalog.DEFAULT_VARIANT,
    /**
     * Off by default and only relevant to the Apple engine: iOS may not
     * ship an on-device Hebrew model on every version, and the only way
     * to use Apple's recognizer then is to let it send audio to Apple's
     * servers. That's a privacy decision the user makes explicitly, never
     * a silent fallback.
     */
    var allowServerFallbackForAppleSpeech: Boolean = false,
    /**
     * The model cloud captions use; one of `cloudProvider`'s models, or
     * see `chosenCloudModel`. The key itself is kept in the platform's
     * secret store, never in this file.
     */
    var cloudModel: String = CloudSpeech.ACCURATE_MODEL,
    /**
     * Which service cloud captions go to: Soniox, the recommended one,
     * on a new install. Settings from before there was a choice meant
     * OpenRouter, whose key they were set up with.
     */
    var cloudProvider: CloudProvider = CloudProvider.Soniox,
    /**
     * Where the home server is ("192.168.1.20", "pc.example:8765",
     * "wss://..."); the pairing code is kept in the secret store, not here.
     */
    var homeServerAddress: String = "",
    homeServerBeam: Int = 5,
    var display: DisplayPreferences = DisplayPreferences.default,
    /**
     * A short buzz when speech resumes after a quiet stretch; the reader
     * may have looked away from the screen.
     */
    var hapticOnSpeechResume: Boolean = true,
    /**
     * Cosine-similarity threshold for the speaker clusterer; lower merges
     * more aggressively (fewer phantom "Speaker 3"s), higher splits more.
     * 0.45 is tuned for the CAM++ neural embedder, whose similarity scores
     * run much lower between the same speaker than the old MFCC embedder's
     * did: on LibriSpeech, CAM++ clusters correctly 96.8% of the time at
     * 0.45, against MFCC's 45.8% at the old default of 0.75.
     */
    var speakerSimilarityThreshold: Float = 0.45f,
    /** Words and names that buzz and highlight when spoken. */
    var keywordAlerts: List<KeywordAlert> = emptyList(),
    /** Doorbell, siren, kettle... shown as banners; see `SoundEventCatalog`. */
    var soundAlerts: SoundAlertPreferences = SoundAlertPreferences.default,
    /** Keep past conversations on the phone for later reading and search. */
    var saveHistory: Boolean = true,
    /**
     * Type-to-speak: ready-made replies the reader can tap instead of
     * typing, and how fast the phone reads them out.
     */
    var quickPhrases: List<String> = defaultQuickPhrases,
    /** 0...1 as the speech synthesizer understands it; 0.45 is a calm pace. */
    var speechRate: Float = 0.45f,
    vocabulary: List<String> = emptyList(),
    /**
     * The first-launch walkthrough (what the app does, engine choice,
     * microphone permission) has been seen once.
     */
    var hasCompletedOnboarding: Boolean = false,
    /**
     * The language of the app's own words (see `Localization`). Captions
     * stay in the language people speak.
     */
    var appLanguage: AppLanguage = AppLanguage.System,
    /**
     * Doorbell, siren or her name while the app isn't on screen (pocket,
     * locked phone) also becomes a phone notification.
     */
    var notifyWhenInBackground: Boolean = true,
    /**
     * Speech models may download over cellular data or in Low Data Mode.
     * Off by default: a model is hundreds of megabytes.
     */
    var allowCellularModelDownload: Boolean = false,
    /**
     * Saved conversations delete themselves after this long; see
     * `HistoryRetention`.
     */
    var historyRetention: HistoryRetention = HistoryRetention.Forever,
    /**
     * A daily window in which only a critical sound still notifies in
     * the background; see `QuietHours`.
     */
    var quietHours: QuietHours = QuietHours.default,
    /**
     * "Not now" was tapped on the caption screen's offer to set up the
     * alert for her name; see `offersNameAlert`.
     */
    var nameAlertOfferDismissed: Boolean = false,
    /**
     * How many times "Not now" was tapped on the caption screen's offer of
     * the recommended Whisper model, and when the offer may come back.
     * A single slip of the finger used to hide the offer for good, leaving
     * the phone on a model that gets most Hebrew words wrong with nothing
     * on screen saying a better one exists; each "Not now" now only puts
     * the offer away for longer (see `betterModelOfferSnoozeDays`).
     */
    var betterModelOfferDeclines: Int = 0,
    var betterModelOfferSnoozedUntil: Double? = null,
) {
    var homeServerBeam: Int = minOf(maxOf(homeServerBeam, homeServerBeamRange.first), homeServerBeamRange.last)

    var vocabulary: List<String> = VocabularyHints.normalized(vocabulary)

    /**
     * Whether the caption screen should offer to set up the alert for her
     * name. The walkthrough asks for it, but only on a fresh install;
     * phones set up before it did never had the name asked for, and
     * without it the buzz for her name never comes.
     */
    val offersNameAlert: Boolean
        get() = hasCompletedOnboarding && keywordAlerts.isEmpty() && !nameAlertOfferDismissed

    /**
     * Whether the phone runs a Whisper model clearly worse in Hebrew than
     * the recommended one. Settings says so for as long as it is true.
     */
    val runsWeakerModel: Boolean
        get() = engine == TranscriptionEngineKind.WhisperKit && WhisperModelCatalog.recommendedImproves(whisperModelVariant)

    /**
     * Whether the caption screen should offer the recommended Whisper
     * model. Phones set up when Small was the default still run it, and
     * Small gets most Hebrew words wrong; the walkthrough now preselects
     * the recommended model, so a fresh install is never asked. [now] is
     * seconds since 1970.
     */
    fun offersBetterModel(now: Double = System.currentTimeMillis() / 1000.0): Boolean {
        if (!hasCompletedOnboarding || !runsWeakerModel) return false
        val until = betterModelOfferSnoozedUntil ?: return true
        return now >= until
    }

    fun snoozeBetterModelOffer(now: Double = System.currentTimeMillis() / 1000.0) {
        val days = betterModelOfferSnoozeDays[minOf(betterModelOfferDeclines, betterModelOfferSnoozeDays.size - 1)]
        betterModelOfferDeclines += 1
        betterModelOfferSnoozedUntil = now + days.toDouble() * 86_400
    }

    /**
     * The model behind the engine in use, for saved conversations and
     * diagnostics: the Whisper size or the cloud model. Apple's has none.
     */
    val modelDescription: String? get() = modelDescription(engine)

    /**
     * [cloudModel] when the chosen service has it, else that service's
     * own default: a model name left from another service means nothing
     * to this one.
     */
    val chosenCloudModel: String
        get() = if (cloudProvider.models.contains(cloudModel)) cloudModel else cloudProvider.defaultModel

    /**
     * The model [engine] runs with these settings, which need not be the
     * chosen engine: the phone's own Whisper covers for the cloud.
     */
    fun modelDescription(engine: TranscriptionEngineKind): String? = when (engine) {
        TranscriptionEngineKind.WhisperKit -> whisperModelVariant
        TranscriptionEngineKind.Cloud -> chosenCloudModel
        TranscriptionEngineKind.HomeServer -> "home server"
        TranscriptionEngineKind.AppleSpeech -> null
    }

    fun copy(): AppSettings {
        val other = AppSettings(
            engine = engine,
            languageCode = languageCode,
            preferredInputUID = preferredInputUID,
            speakerProfiles = speakerProfiles.map { it.copy() },
            creditLine = creditLine,
            whisperModelVariant = whisperModelVariant,
            allowServerFallbackForAppleSpeech = allowServerFallbackForAppleSpeech,
            cloudModel = cloudModel,
            cloudProvider = cloudProvider,
            homeServerAddress = homeServerAddress,
            display = display,
            hapticOnSpeechResume = hapticOnSpeechResume,
            speakerSimilarityThreshold = speakerSimilarityThreshold,
            keywordAlerts = keywordAlerts.map { it.copy() },
            soundAlerts = soundAlerts,
            saveHistory = saveHistory,
            quickPhrases = quickPhrases,
            speechRate = speechRate,
            hasCompletedOnboarding = hasCompletedOnboarding,
            appLanguage = appLanguage,
            notifyWhenInBackground = notifyWhenInBackground,
            allowCellularModelDownload = allowCellularModelDownload,
            historyRetention = historyRetention,
            quietHours = quietHours,
            nameAlertOfferDismissed = nameAlertOfferDismissed,
            betterModelOfferDeclines = betterModelOfferDeclines,
            betterModelOfferSnoozedUntil = betterModelOfferSnoozedUntil,
        )
        other.homeServerBeam = homeServerBeam
        other.vocabulary = vocabulary
        return other
    }

    private fun fields(): List<Any?> = listOf(
        engine, languageCode, preferredInputUID, speakerProfiles, creditLine, whisperModelVariant,
        allowServerFallbackForAppleSpeech, cloudModel, cloudProvider, homeServerAddress, homeServerBeam, display,
        hapticOnSpeechResume, speakerSimilarityThreshold, keywordAlerts, soundAlerts, saveHistory, quickPhrases,
        speechRate, vocabulary, hasCompletedOnboarding, appLanguage, notifyWhenInBackground,
        allowCellularModelDownload, historyRetention, quietHours, nameAlertOfferDismissed, betterModelOfferDeclines,
        betterModelOfferSnoozedUntil,
    )

    override fun equals(other: Any?): Boolean = other is AppSettings && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "AppSettings(${fields()})"

    /**
     * The JSON written to disk. Throws when a number in it is not finite
     * (JSON has no NaN), as an encoder does: such a save must fail loudly
     * instead of writing a file nothing can read.
     */
    fun toJson(): String = buildJsonObject {
        put("engine", engine.rawValue)
        put("languageCode", languageCode)
        preferredInputUID?.let { put("preferredInputUID", it) }
        put("speakerProfiles", JsonArray(speakerProfiles.map { it.toJsonObject() }))
        put("creditLine", creditLine)
        put("whisperModelVariant", whisperModelVariant)
        put("allowServerFallbackForAppleSpeech", allowServerFallbackForAppleSpeech)
        put("cloudModel", cloudModel)
        put("cloudProvider", cloudProvider.rawValue)
        put("homeServerAddress", homeServerAddress)
        put("homeServerBeam", homeServerBeam)
        put("display", display.toJsonObject())
        put("hapticOnSpeechResume", hapticOnSpeechResume)
        put("speakerSimilarityThreshold", settingsFloat(speakerSimilarityThreshold))
        put("speakerThresholdScale", SPEAKER_THRESHOLD_SCALE)
        put("keywordAlerts", JsonArray(keywordAlerts.map { Json.parseToJsonElement(it.toJson()) }))
        put("soundAlerts", Json.parseToJsonElement(soundAlerts.toJson()))
        put("saveHistory", saveHistory)
        put("quickPhrases", JsonArray(quickPhrases.map { JsonPrimitive(it) }))
        put("speechRate", settingsFloat(speechRate))
        put("vocabulary", JsonArray(vocabulary.map { JsonPrimitive(it) }))
        put("hasCompletedOnboarding", hasCompletedOnboarding)
        put("appLanguage", appLanguage.rawValue)
        put("notifyWhenInBackground", notifyWhenInBackground)
        put("allowCellularModelDownload", allowCellularModelDownload)
        put("historyRetention", historyRetention.rawValue)
        put("quietHours", Json.parseToJsonElement(quietHours.toJson()))
        put("nameAlertOfferDismissed", nameAlertOfferDismissed)
        put("betterModelOfferDeclines", betterModelOfferDeclines)
        betterModelOfferSnoozedUntil?.let { put("betterModelOfferSnoozedUntil", settingsDouble(it)) }
    }.toString()

    companion object {
        /**
         * Which scale `speakerSimilarityThreshold` was saved on: 1 for files
         * written before the CAM++ embedder, whose untouched 0.75 was the old
         * default rather than a choice.
         */
        private const val SPEAKER_THRESHOLD_SCALE = 2

        val homeServerBeamRange = 1..7

        /**
         * How many days each successive "Not now" puts the offer of the
         * recommended model away for.
         */
        val betterModelOfferSnoozeDays = listOf(3, 14, 60)

        /**
         * The phrases a hard-of-hearing person needs most often in
         * conversation, ready before she has typed anything.
         */
        val defaultQuickPhrases: List<String> = listOf(
            "רגע, לא הבנתי",
            "אפשר לחזור על זה?",
            "לאט יותר בבקשה",
            "דברו קרוב יותר לטלפון, בבקשה",
            "אני קוראת את הכתוביות, תנו לי רגע",
            "כן",
            "לא",
            "תודה",
            "בואו נדבר אחד אחד",
        )

        /**
         * The same ready-made phrases in English, for someone who has the app
         * in English. Same order and meaning as [defaultQuickPhrases].
         */
        val defaultQuickPhrasesEnglish: List<String> = listOf(
            "Wait, I didn't catch that",
            "Could you say that again?",
            "Slower, please",
            "Please speak closer to the phone",
            "I'm reading the captions, give me a moment",
            "Yes",
            "No",
            "Thank you",
            "Let's talk one at a time",
        )

        /**
         * The ready-made phrases in the other interface languages: same order
         * and meaning, and the Say screen speaks them aloud, so they are
         * written as a person would say them, not as button labels. Where a
         * language marks the speaker's gender it follows the Hebrew list's
         * woman speaker.
         */
        internal val otherQuickPhrases: Map<UILanguage, List<String>> = mapOf(
        UILanguage.Arabic to listOf("لحظة، لم أفهم", "ممكن تعيد ذلك؟", "أبطأ من فضلك", "تكلّم أقرب إلى الهاتف من فضلك", "أنا أقرأ الترجمة النصية، أعطني لحظة", "نعم", "لا", "شكرًا", "لنتكلم واحدًا تلو الآخر"),
        UILanguage.Russian to listOf("Подождите, я не поняла", "Можете повторить?", "Помедленнее, пожалуйста", "Говорите ближе к телефону, пожалуйста", "Я читаю субтитры, дайте мне минутку", "Да", "Нет", "Спасибо", "Давайте говорить по одному"),
        UILanguage.Amharic to listOf("ይቅርታ፣ አልገባኝም", "እባክዎ እንደገና ይድገሙት", "እባክዎ ቀስ ብለው ይናገሩ", "እባክዎ ወደ ስልኩ ቀርበው ይናገሩ", "ካፕሽኑን እያነበብኩ ነው፣ ትንሽ ይጠብቁኝ", "አዎ", "አይ", "አመሰግናለሁ", "አንድ በአንድ እንነጋገር"),
        UILanguage.French to listOf("Attendez, je n’ai pas compris", "Vous pouvez répéter ?", "Plus lentement, s’il vous plaît", "Parlez plus près du téléphone, s’il vous plaît", "Je lis les sous-titres, laissez-moi un instant", "Oui", "Non", "Merci", "Parlons chacun à notre tour"),
        UILanguage.Spanish to listOf("Espera, no entendí", "¿Puedes repetirlo?", "Más despacio, por favor", "Habla más cerca del teléfono, por favor", "Estoy leyendo los subtítulos, dame un momento", "Sí", "No", "Gracias", "Hablemos de uno en uno"),
        UILanguage.Ukrainian to listOf("Зачекайте, я не зрозуміла", "Можете повторити?", "Повільніше, будь ласка", "Говоріть ближче до телефону, будь ласка", "Я читаю субтитри, дайте мені хвилинку", "Так", "Ні", "Дякую", "Давайте говорити по одному"),
        UILanguage.German to listOf("Moment, das habe ich nicht verstanden", "Können Sie das wiederholen?", "Langsamer, bitte", "Bitte sprechen Sie näher am Telefon", "Ich lese die Untertitel, einen Moment bitte", "Ja", "Nein", "Danke", "Bitte nacheinander sprechen"),
        UILanguage.Portuguese to listOf("Espere, não percebi", "Pode repetir?", "Mais devagar, por favor", "Fale mais perto do telemóvel, por favor", "Estou a ler as legendas, dê-me um momento", "Sim", "Não", "Obrigada", "Vamos falar um de cada vez"),
        UILanguage.ChineseSimplified to listOf("等一下，我没听懂", "可以再说一遍吗？", "请说慢一点", "请靠近手机说话", "我在看字幕，请稍等", "是的", "不是", "谢谢", "我们一个一个说吧"),
        UILanguage.Hindi to listOf("रुकिए, मैं समझी नहीं", "क्या आप दोबारा कह सकते हैं?", "कृपया धीरे बोलिए", "कृपया फ़ोन के पास आकर बोलिए", "मैं कैप्शन पढ़ रही हूँ, मुझे एक पल दीजिए", "हाँ", "नहीं", "धन्यवाद", "चलिए, एक-एक करके बोलें"),
        )

        fun defaultQuickPhrases(language: UILanguage): List<String> = when (language) {
            UILanguage.Hebrew -> defaultQuickPhrases
            UILanguage.English -> defaultQuickPhrasesEnglish
            else -> otherQuickPhrases[language] ?: defaultQuickPhrasesEnglish
        }

        /**
         * What the Say screen lists. A list nobody has edited is one of the
         * built-in lists, and follows the app's language: it is stored as
         * Hebrew before anyone chooses otherwise, so without this, switching
         * the app to another language left every ready-made phrase in Hebrew.
         * A list she has changed in any way is hers and is shown as it is.
         */
        fun displayedQuickPhrases(stored: List<String>, language: UILanguage): List<String> =
            if (UILanguage.entries.any { defaultQuickPhrases(it) == stored }) defaultQuickPhrases(language) else stored

        val default: AppSettings
            get() = AppSettings(
                engine = TranscriptionEngineKind.WhisperKit,
                languageCode = "he",
                preferredInputUID = null,
                speakerProfiles = emptyList(),
                creditLine = "Arbel",
            )

        /**
         * Reads settings leniently one value at a time: a single value a
         * build doesn't understand (an engine name from a newer version, a
         * damaged field) falls back to its default instead of failing the
         * whole file. Failing the whole file meant starting from defaults,
         * and the next save then overwrote the enrolled voices, vocabulary
         * and alerts that were still perfectly readable. Throws only when
         * [json] is not a JSON object at all.
         */
        fun fromJson(json: String): AppSettings {
            val container = Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("settings are not a JSON object")
            val defaults = default
            val settings = AppSettings(
                engine = container.lenientString("engine")?.let { TranscriptionEngineKind.fromRawValue(it) } ?: defaults.engine,
                languageCode = container.lenientString("languageCode") ?: defaults.languageCode,
                preferredInputUID = container.lenientString("preferredInputUID"),
                speakerProfiles = container.lenientArray("speakerProfiles") { SpeakerProfile.fromElement(it) }
                    ?.filter { it.isUsable }
                    ?.map { it.withCurrentVoicePrint } ?: emptyList(),
                // The credit line is not user-editable; whatever an old file says,
                // the current build's text wins.
                creditLine = defaults.creditLine,
                whisperModelVariant = container.lenientString("whisperModelVariant") ?: defaults.whisperModelVariant,
                allowServerFallbackForAppleSpeech = container.lenientBoolean("allowServerFallbackForAppleSpeech")
                    ?: defaults.allowServerFallbackForAppleSpeech,
                cloudModel = container.lenientString("cloudModel")?.takeIf { it.isNotEmpty() } ?: defaults.cloudModel,
                cloudProvider = container.lenientString("cloudProvider")?.let { raw -> CloudProvider.entries.firstOrNull { it.rawValue == raw } }
                    ?: CloudProvider.OpenRouter,
                homeServerAddress = container.lenientString("homeServerAddress") ?: defaults.homeServerAddress,
                homeServerBeam = container.lenientInt("homeServerBeam")
                    ?.let { minOf(maxOf(it, homeServerBeamRange.first), homeServerBeamRange.last) } ?: defaults.homeServerBeam,
                display = container.lenientObject("display") { DisplayPreferences.fromElement(it) } ?: defaults.display,
                hapticOnSpeechResume = container.lenientBoolean("hapticOnSpeechResume") ?: defaults.hapticOnSpeechResume,
                keywordAlerts = container.lenientArray("keywordAlerts") { element ->
                    try {
                        KeywordAlert.fromJson(element.toString())
                    } catch (_: Exception) {
                        null
                    }
                } ?: defaults.keywordAlerts,
                soundAlerts = container.lenientObject("soundAlerts") { SoundAlertPreferences.fromJson(it.toString()) } ?: defaults.soundAlerts,
                saveHistory = container.lenientBoolean("saveHistory") ?: defaults.saveHistory,
                quickPhrases = container.lenientStrings("quickPhrases") ?: defaults.quickPhrases,
                vocabulary = container.lenientStrings("vocabulary") ?: emptyList(),
                hasCompletedOnboarding = container.lenientBoolean("hasCompletedOnboarding") ?: false,
                appLanguage = container.lenientString("appLanguage")?.let { AppLanguage.fromRawValue(it) } ?: defaults.appLanguage,
                notifyWhenInBackground = container.lenientBoolean("notifyWhenInBackground") ?: defaults.notifyWhenInBackground,
                allowCellularModelDownload = container.lenientBoolean("allowCellularModelDownload")
                    ?: defaults.allowCellularModelDownload,
                historyRetention = container.lenientString("historyRetention")?.let { HistoryRetention.fromRawValue(it) }
                    ?: defaults.historyRetention,
                quietHours = container.lenientObject("quietHours") { QuietHours.fromJson(it.toString()) } ?: defaults.quietHours,
                nameAlertOfferDismissed = container.lenientBoolean("nameAlertOfferDismissed") ?: defaults.nameAlertOfferDismissed,
                betterModelOfferDeclines = maxOf(0, container.lenientInt("betterModelOfferDeclines") ?: 0),
                betterModelOfferSnoozedUntil = container.lenientDouble("betterModelOfferSnoozedUntil"),
            )
            var threshold = container.lenientFloat("speakerSimilarityThreshold") ?: defaults.speakerSimilarityThreshold
            // The Settings slider's range; a file saying otherwise gets the nearest edge.
            threshold = if (threshold.isFinite()) minOf(maxOf(threshold, 0.2f), 0.95f) else defaults.speakerSimilarityThreshold
            // Files from before 2026-09-14 saved the old default, 0.75, even when
            // nobody touched the slider; read as a choice, it kept CAM++ from
            // telling voices apart (it wants about 0.45).
            if ((container.lenientInt("speakerThresholdScale") ?: 1) < 2 && threshold == 0.75f) {
                threshold = defaults.speakerSimilarityThreshold
            }
            settings.speakerSimilarityThreshold = threshold
            settings.speechRate = minOf(maxOf(container.lenientFloat("speechRate") ?: defaults.speechRate, 0.2f), 0.7f)
            return settings
        }
    }
}

/**
 * Loads and saves [AppSettings] as JSON at a caller-supplied file. Kept
 * separate from `AppSettings` itself, and taking the file as a parameter
 * rather than reaching for the app's folders internally, purely so tests
 * can point it at a temp file instead of touching real app storage.
 */
class SettingsStore(private val file: File) {
    /**
     * The saved settings, or the defaults when there are none or the file
     * can't be read as settings at all. A damaged file is moved aside
     * first (see [damagedCopy]): the next save would otherwise write
     * the defaults over the only copy of recorded voices, names and alert
     * words, which take real effort to make again.
     */
    fun load(): AppSettings {
        val data = try {
            file.readBytes()
        } catch (_: Exception) {
            return AppSettings.default
        }
        try {
            return AppSettings.fromJson(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
        }
        try {
            damagedCopy.delete()
            Files.move(file.toPath(), damagedCopy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
        }
        return AppSettings.default
    }

    /**
     * Where a settings file that couldn't be read is kept, next to it:
     * "ozen-settings.json" becomes "ozen-settings.damaged.json". Only the
     * latest one is kept.
     */
    val damagedCopy: File
        get() = File(file.absoluteFile.parentFile, "${file.nameWithoutExtension}.damaged.json")

    /**
     * Creates the folder first: on a fresh install the app's folder may
     * not exist yet, and without this the very first saves (finishing the
     * walkthrough, say) fail and are forgotten on relaunch.
     */
    fun save(settings: AppSettings) {
        val data = settings.toJson().toByteArray(Charsets.UTF_8)
        PrivateFileWrites.write(file, data)
    }
}

private val settingsUuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

private fun settingsUuidText(id: UUID): String = id.toString().uppercase(Locale.ROOT)

private fun roundedAwayFromZero(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

private fun settingsDouble(value: Double): JsonPrimitive {
    require(value.isFinite()) { "JSON has no place for $value" }
    return JsonPrimitive(value)
}

private fun settingsFloat(value: Float): JsonPrimitive {
    require(value.isFinite()) { "JSON has no place for $value" }
    return JsonPrimitive(value)
}

private fun JsonElement?.settingsString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.settingsNumberText(): String? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content

private fun JsonElement?.settingsFloat(): Float? = settingsNumberText()?.toFloatOrNull()?.takeIf { it.isFinite() }

private fun JsonObject.lenientString(key: String): String? = this[key].settingsString()

private fun JsonObject.lenientBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.lenientInt(key: String): Int? = this[key].settingsNumberText()?.toIntOrNull()

private fun JsonObject.lenientDouble(key: String): Double? =
    this[key].settingsNumberText()?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun JsonObject.lenientFloat(key: String): Float? = this[key].settingsFloat()

private fun JsonObject.lenientStrings(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    val strings = array.map { it.settingsString() }
    return if (strings.any { it == null }) null else strings.filterNotNull()
}

private fun <T : Any> JsonObject.lenientArray(key: String, read: (JsonElement) -> T?): List<T>? {
    val array = this[key] as? JsonArray ?: return null
    return array.mapNotNull {
        try {
            read(it)
        } catch (_: Exception) {
            null
        }
    }
}

private fun <T : Any> JsonObject.lenientObject(key: String, read: (JsonElement) -> T): T? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return try {
        read(element)
    } catch (_: Exception) {
        null
    }
}
