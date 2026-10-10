package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * A household or safety sound the app can recognise and flash on screen.
 * For someone who can't hear the doorbell, the kettle, or (in Israel) the
 * civil-defence siren, this is arguably as important as the captions.
 * [identifier] is the label string of the platform's sound classifier; the
 * platform layer additionally verifies each one against what the classifier
 * knows before enabling it. [systemImage] is the name of the symbol the iOS
 * app draws; an Android layer maps it to its own icon.
 */
class SoundEvent(
    val identifier: String,
    val name: String,
    val importance: Importance,
    val systemImage: String,
    sameSoundAs: String? = null,
) {
    enum class Importance(val rawValue: Int) {
        Low(0), Medium(1), High(2), Critical(3);

        companion object {
            fun fromRawValue(rawValue: Int): Importance? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /**
     * Two labels for one physical sound ("ringtone" and
     * "telephone_bell_ringing") share this, and with it one cooldown. The
     * shown name can't serve: in English the two read differently.
     */
    val cooldownKey: String = sameSoundAs ?: identifier

    val id: String get() = identifier

    override fun equals(other: Any?): Boolean =
        other is SoundEvent && identifier == other.identifier && name == other.name &&
            importance == other.importance && systemImage == other.systemImage && cooldownKey == other.cooldownKey

    override fun hashCode(): Int = listOf(identifier, name, importance, systemImage, cooldownKey).hashCode()

    override fun toString(): String = "SoundEvent($identifier, $name, $importance)"
}

/**
 * The curated subset of the classifier's ~300 labels that matter to a
 * hard-of-hearing person at home. Speech-like labels are deliberately
 * absent: speech is what the captions are for.
 */
object SoundEventCatalog {
    val events: List<SoundEvent>
        get() = listOf(
            // Safety first. `civil_defense_siren` is the rocket-alert siren in
            // Israel, which is why it sits at the top.
            SoundEvent("civil_defense_siren", tr("אזעקה", "Air raid siren"), SoundEvent.Importance.Critical, "light.beacon.max.fill"),
            SoundEvent("smoke_detector", tr("גלאי עשן", "Smoke detector"), SoundEvent.Importance.Critical, "flame.fill"),
            SoundEvent("fire", tr("אש", "Fire"), SoundEvent.Importance.Critical, "flame"),
            SoundEvent("siren", tr("סירנה", "Siren"), SoundEvent.Importance.Critical, "light.beacon.max"),
            SoundEvent("ambulance_siren", tr("סירנת אמבולנס", "Ambulance siren"), SoundEvent.Importance.Critical, "cross.case.fill"),
            SoundEvent("police_siren", tr("סירנת משטרה", "Police siren"), SoundEvent.Importance.Critical, "shield.fill"),
            SoundEvent("fire_engine_siren", tr("סירנת כבאית", "Fire engine siren"), SoundEvent.Importance.Critical, "flame.circle.fill"),
            SoundEvent("gunshot_gunfire", tr("ירי", "Gunfire"), SoundEvent.Importance.Critical, "exclamationmark.triangle.fill"),
            SoundEvent("artillery_fire", tr("פיצוץ", "Explosion"), SoundEvent.Importance.Critical, "exclamationmark.triangle.fill"),
            SoundEvent("glass_breaking", tr("זכוכית נשברת", "Glass breaking"), SoundEvent.Importance.Critical, "exclamationmark.triangle"),
            SoundEvent("screaming", tr("צרחה", "Scream"), SoundEvent.Importance.Critical, "person.wave.2.fill"),
            SoundEvent("car_horn", tr("צפירת רכב", "Car horn"), SoundEvent.Importance.High, "car.fill"),
            SoundEvent("reverse_beeps", tr("רכב ברוורס", "Car reversing"), SoundEvent.Importance.High, "car"),
            SoundEvent("shout", tr("צעקה", "Shout"), SoundEvent.Importance.High, "person.wave.2"),
            SoundEvent("yell", tr("צעקה", "Yell"), SoundEvent.Importance.High, "person.wave.2", sameSoundAs = "shout"),
            SoundEvent("children_shouting", tr("ילדים צועקים", "Children shouting"), SoundEvent.Importance.High, "figure.and.child.holdinghands"),
            SoundEvent("crying_sobbing", tr("בכי", "Crying"), SoundEvent.Importance.High, "drop.fill"),
            SoundEvent("baby_crying", tr("תינוק בוכה", "Baby crying"), SoundEvent.Importance.High, "figure.child"),
            SoundEvent("door_bell", tr("פעמון דלת", "Doorbell"), SoundEvent.Importance.High, "bell.fill"),
            SoundEvent("knock", tr("דפיקה בדלת", "Knock at the door"), SoundEvent.Importance.High, "hand.raised.fill"),
            SoundEvent("telephone_bell_ringing", tr("טלפון מצלצל", "Phone ringing"), SoundEvent.Importance.High, "phone.fill"),
            SoundEvent("ringtone", tr("טלפון מצלצל", "Ringtone"), SoundEvent.Importance.High, "phone.fill", sameSoundAs = "telephone_bell_ringing"),
            SoundEvent("alarm_clock", tr("שעון מעורר", "Alarm clock"), SoundEvent.Importance.High, "alarm.fill"),
            SoundEvent("dog_bark", tr("כלב נובח", "Dog barking"), SoundEvent.Importance.High, "dog.fill"),
            SoundEvent("dog_growl", tr("כלב נוהם", "Dog growling"), SoundEvent.Importance.High, "dog"),
            // A kettle can rumble ("boiling") or whistle ("whistling") --
            // two different classifier labels for the same stove hazard, so
            // they share a name and, in `SoundEventPolicy`, a cooldown, the
            // same way "telephone_bell_ringing" and "ringtone" do above.
            // Left at `.medium` this was silenced under "Important and
            // above", the default a family is likely to pick.
            SoundEvent("boiling", tr("מים רותחים", "Boiling water"), SoundEvent.Importance.High, "drop.triangle.fill"),
            SoundEvent("whistling", tr("מים רותחים", "Kettle whistling"), SoundEvent.Importance.High, "drop.triangle.fill", sameSoundAs = "boiling"),
            SoundEvent("dog_howl", tr("כלב מיילל", "Dog howling"), SoundEvent.Importance.Medium, "dog"),
            SoundEvent("thunder", tr("רעם", "Thunder"), SoundEvent.Importance.Medium, "cloud.bolt.fill"),
            SoundEvent("thunderstorm", tr("סופת רעמים", "Thunderstorm"), SoundEvent.Importance.Medium, "cloud.bolt.rain.fill"),
            SoundEvent("fireworks", tr("זיקוקים", "Fireworks"), SoundEvent.Importance.Medium, "sparkles"),
            SoundEvent("firecracker", tr("נפצים", "Firecrackers"), SoundEvent.Importance.Medium, "sparkles"),
            SoundEvent("door", tr("דלת", "Door"), SoundEvent.Importance.Medium, "door.left.hand.open"),
            SoundEvent("door_slam", tr("טריקת דלת", "Door slamming"), SoundEvent.Importance.Medium, "door.left.hand.closed"),
            SoundEvent("telephone", tr("טלפון", "Phone"), SoundEvent.Importance.Medium, "phone"),
            SoundEvent("beep", tr("צפצוף", "Beep"), SoundEvent.Importance.Medium, "waveform.path"),
            SoundEvent("microwave_oven", tr("מיקרוגל", "Microwave"), SoundEvent.Importance.Medium, "microwave.fill"),
            SoundEvent("water_tap_faucet", tr("ברז פתוח", "Running tap"), SoundEvent.Importance.Medium, "drop"),
            SoundEvent("dog_whimper", tr("כלב מייבב", "Dog whimpering"), SoundEvent.Importance.Low, "dog"),
            SoundEvent("door_sliding", tr("דלת הזזה", "Sliding door"), SoundEvent.Importance.Low, "door.sliding.left.hand.open"),
            SoundEvent("toilet_flush", tr("הדחת אסלה", "Toilet flush"), SoundEvent.Importance.Low, "toilet"),
            SoundEvent("sink_filling_washing", tr("כיור", "Sink"), SoundEvent.Importance.Low, "sink"),
            SoundEvent("vacuum_cleaner", tr("שואב אבק", "Vacuum cleaner"), SoundEvent.Importance.Low, "fan.fill"),
            SoundEvent("cat_meow", tr("חתול מיילל", "Cat meowing"), SoundEvent.Importance.Low, "cat.fill"),
            SoundEvent("cat", tr("חתול", "Cat"), SoundEvent.Importance.Low, "cat"),
            SoundEvent("cough", tr("שיעול", "Cough"), SoundEvent.Importance.Low, "lungs.fill"),
            SoundEvent("sneeze", tr("עיטוש", "Sneeze"), SoundEvent.Importance.Low, "wind"),
            SoundEvent("laughter", tr("צחוק", "Laughter"), SoundEvent.Importance.Low, "face.smiling"),
            SoundEvent("applause", tr("מחיאות כפיים", "Applause"), SoundEvent.Importance.Low, "hands.clap.fill"),
            SoundEvent("music", tr("מוזיקה", "Music"), SoundEvent.Importance.Low, "music.note"),
        )

    /**
     * What the phone's own vibration on a hard surface can be heard as: a
     * ring, a buzzer or alarm clock, a beep, a knock-like tap, an appliance
     * hum. Never a safety sound, never the doorbell.
     */
    val vibrationLookalikes: Set<String> = setOf(
        "telephone_bell_ringing", "ringtone", "telephone", "alarm_clock",
        "beep", "knock", "microwave_oven", "vacuum_cleaner",
    )

    /**
     * One entry per sound, for the Settings list: two labels for one
     * sound are switched together, and in Hebrew they read the same, so
     * they were two identical rows. The first label stands for both.
     */
    val listed: List<SoundEvent> get() = events.filter { it.identifier == it.cooldownKey }

    fun event(identifier: String): SoundEvent? = events.firstOrNull { it.identifier == identifier }

    /** Every label for the same sound as [identifier], itself included. */
    fun sameSound(identifier: String): Set<String> {
        val key = event(identifier)?.cooldownKey ?: return setOf(identifier)
        return events.filter { it.cooldownKey == key }.map { it.identifier }.toSet()
    }

    val identifiers: Set<String> get() = events.map { it.identifier }.toSet()

    /**
     * Turns one classifier window's raw candidates (identifier to
     * confidence) into [SoundObservation]s, keeping every one this app
     * tracks regardless of where it landed in the window's ranking. The
     * classifier reports ~300 labels; when family is talking or the TV is
     * on, speech, music and chatter occupy the top of that ranking, and a
     * doorbell or kettle heard at the same time can fall to rank four or
     * lower. Filtering by catalog membership instead of by rank means it is
     * still reported.
     *
     * The most important sound comes first, then the most confident: the
     * caption screen takes up the first of a reading and lets a weaker
     * label of the same reading go, so a smoke alarm the classifier also
     * scored higher as an alarm clock sends one notification, the smoke
     * alarm's, instead of both.
     */
    fun matchingObservations(
        candidates: Iterable<Pair<String, Double>>,
        minimumConfidence: Double,
        timestamp: Double,
    ): List<SoundObservation> {
        val known = identifiers
        return candidates
            .filter { it.first in known && it.second >= minimumConfidence }
            .map { SoundObservation(it.first, it.second, timestamp) }
            .sortedWith { lhs, rhs ->
                val left = event(lhs.identifier)?.importance ?: SoundEvent.Importance.Low
                val right = event(rhs.identifier)?.importance ?: SoundEvent.Importance.Low
                if (left != right) right.compareTo(left) else rhs.confidence.compareTo(lhs.confidence)
            }
    }
}

/** One classifier reading: "this window sounds like X with confidence c". */
data class SoundObservation(val identifier: String, val confidence: Double, val timestamp: Double)

/** An alert that made it through [SoundEventPolicy] and should be shown. */
data class SoundAlert(
    val id: UUID = UUID.randomUUID(),
    val event: SoundEvent,
    val confidence: Double,
    val timestamp: Double,
) {
    /**
     * How long the banner stays up. A critical one outlasts the policy's
     * cooldown, so a siren still sounding raises its next alert while the
     * banner is up: the screen never goes blank mid-siren as if it stopped.
     */
    val bannerSeconds: Double
        get() = if (event.importance == SoundEvent.Importance.Critical) SoundEventPolicy.DEFAULT_COOLDOWN_SECONDS + 4 else 8.0

    /**
     * Whether this alert's banner replaces the one on screen: a kettle
     * heard during a smoke alarm doesn't take its banner away. It still
     * buzzes and is read out.
     */
    fun takesBanner(shown: SoundAlert?): Boolean {
        if (shown == null) return true
        return event.importance >= shown.event.importance
    }
}

/**
 * The user's choices about sound alerts. Immutable: [withMuted] and
 * [withSensitive] return the changed copy.
 */
class SoundAlertPreferences(
    val isEnabled: Boolean = true,
    val minimumImportance: SoundEvent.Importance = SoundEvent.Importance.Medium,
    val mutedIdentifiers: Set<String> = emptySet(),
    /**
     * Sounds to alert on even when heard faintly: set from a near-miss the
     * reader noticed kept not alerting, e.g. a doorbell that's always heard
     * around 45% against the usual 60% floor.
     */
    val sensitiveIdentifiers: Set<String> = emptySet(),
) {
    // Two classifier labels the catalog shows as one sound ("Phone ringing"
    // as telephone_bell_ringing and ringtone) follow one switch: muting one
    // row left the same ring alerting under the other label, with the
    // switch showing off.
    fun isMuted(identifier: String): Boolean =
        mutedIdentifiers.any { it in SoundEventCatalog.sameSound(identifier) }

    fun withMuted(identifier: String, muted: Boolean): SoundAlertPreferences {
        val labels = SoundEventCatalog.sameSound(identifier)
        return copy(mutedIdentifiers = if (muted) mutedIdentifiers + labels else mutedIdentifiers - labels)
    }

    fun isSensitive(identifier: String): Boolean =
        sensitiveIdentifiers.any { it in SoundEventCatalog.sameSound(identifier) }

    fun withSensitive(identifier: String, sensitive: Boolean): SoundAlertPreferences {
        val labels = SoundEventCatalog.sameSound(identifier)
        return copy(sensitiveIdentifiers = if (sensitive) sensitiveIdentifiers + labels else sensitiveIdentifiers - labels)
    }

    fun copy(
        isEnabled: Boolean = this.isEnabled,
        minimumImportance: SoundEvent.Importance = this.minimumImportance,
        mutedIdentifiers: Set<String> = this.mutedIdentifiers,
        sensitiveIdentifiers: Set<String> = this.sensitiveIdentifiers,
    ) = SoundAlertPreferences(isEnabled, minimumImportance, mutedIdentifiers, sensitiveIdentifiers)

    fun toJson(): String = buildJsonObject {
        put("isEnabled", isEnabled)
        put("minimumImportance", minimumImportance.rawValue)
        putJsonArray("mutedIdentifiers") { mutedIdentifiers.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("sensitiveIdentifiers") { sensitiveIdentifiers.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    override fun equals(other: Any?): Boolean =
        other is SoundAlertPreferences && isEnabled == other.isEnabled &&
            minimumImportance == other.minimumImportance && mutedIdentifiers == other.mutedIdentifiers &&
            sensitiveIdentifiers == other.sensitiveIdentifiers

    override fun hashCode(): Int = listOf(isEnabled, minimumImportance, mutedIdentifiers, sensitiveIdentifiers).hashCode()

    override fun toString(): String =
        "SoundAlertPreferences($isEnabled, $minimumImportance, $mutedIdentifiers, $sensitiveIdentifiers)"

    companion object {
        val default = SoundAlertPreferences()

        /** A missing or unreadable key falls back to its default, key by key. */
        fun fromJson(json: String): SoundAlertPreferences {
            val container = Json.parseToJsonElement(json).jsonObject
            return SoundAlertPreferences(
                isEnabled = container.lenientBoolean("isEnabled") ?: default.isEnabled,
                minimumImportance = container.lenientInt("minimumImportance")
                    ?.let { SoundEvent.Importance.fromRawValue(it) } ?: default.minimumImportance,
                mutedIdentifiers = container.lenientStrings("mutedIdentifiers") ?: default.mutedIdentifiers,
                sensitiveIdentifiers = container.lenientStrings("sensitiveIdentifiers") ?: default.sensitiveIdentifiers,
            )
        }
    }
}

private fun JsonObject.lenientBoolean(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) null else value.content.toBooleanStrictOrNull()
}

private fun JsonObject.lenientInt(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) null else value.content.toIntOrNull()
}

private fun JsonObject.lenientStrings(key: String): Set<String>? {
    val array = this[key] as? JsonArray ?: return null
    val strings = array.map { it as? JsonPrimitive }
    if (strings.any { it == null || !it.isString }) return null
    return strings.map { it!!.content }.toSet()
}

/**
 * Decides which classifier readings become alerts. The classifier fires
 * on every ~0.75 s window, so without a per-sound cooldown a ringing
 * phone would produce a new banner every window for as long as it rings;
 * and a reading below the confidence floor, an unlisted label, a muted
 * sound, or one below the importance the user asked for is dropped.
 */
class SoundEventPolicy(
    var preferences: SoundAlertPreferences = SoundAlertPreferences.default,
    var minimumConfidence: Double = 0.6,
    /**
     * The floor for a sound in [SoundAlertPreferences.sensitiveIdentifiers].
     * Kept well above pure noise but below [minimumConfidence], trading more
     * false alarms on that one sound against never hearing it at all.
     */
    var sensitiveConfidence: Double = 0.4,
    var cooldownSeconds: Double = DEFAULT_COOLDOWN_SECONDS,
    /**
     * Above this confidence, a continuous sound ([sustainedIdentifiers])
     * alerts on the very first window, the same as any other sound: an
     * unmistakable siren or smoke alarm should never wait through a second
     * ~0.75 s window to be confirmed.
     */
    var emergencyConfidence: Double = 0.85,
    /**
     * How long a first, unconfirmed window of a continuous sound is
     * remembered while waiting for a second one to confirm it wasn't a
     * spike from the TV or kitchen clatter.
     */
    var persistenceWindowSeconds: Double = 2.0,
) {
    private val lastAlertAt = mutableMapOf<String, Double>()
    private val pendingSince = mutableMapOf<String, Double>()

    /**
     * The confidence [identifier] needs to raise an alert right now. Never
     * stricter than [minimumConfidence], even if a future setting ever
     * lowered it below the default [sensitiveConfidence].
     */
    fun requiredConfidence(identifier: String): Double =
        if (preferences.isSensitive(identifier)) minOf(sensitiveConfidence, minimumConfidence) else minimumConfidence

    /** The alert to show for this reading, or null. */
    fun evaluate(observation: SoundObservation): SoundAlert? {
        if (!preferences.isEnabled) return null
        if (observation.confidence < requiredConfidence(observation.identifier)) return null
        val event = SoundEventCatalog.event(observation.identifier) ?: return null
        if (event.importance < preferences.minimumImportance) return null
        if (preferences.isMuted(event.identifier)) return null
        // A continuous sound needs a second confirming window within
        // `persistenceWindowSeconds`, unless it's already confident enough
        // to be sure on its own: a real siren or a kettle at a rolling boil
        // keeps sounding, so waiting under a second for the next window
        // costs nothing, while a single TV or clatter spike never gets a
        // second confirmation and never raises the banner.
        if (event.identifier in sustainedIdentifiers && observation.confidence < emergencyConfidence) {
            // By the sound, not the label: a kettle the classifier hears as
            // "boiling" in one window and "whistling" in the next is one
            // kettle confirming itself, not two sounds each starting over.
            // A later window only: both labels in one reading share its
            // timestamp, and one clatter scored as both is still one spike.
            val pendingAt = pendingSince[event.cooldownKey]
            if (pendingAt == null ||
                observation.timestamp <= pendingAt ||
                observation.timestamp - pendingAt > persistenceWindowSeconds
            ) {
                pendingSince[event.cooldownKey] = observation.timestamp
                return null
            }
            pendingSince.remove(event.cooldownKey)
        }
        // Keyed by the sound, not the identifier: two classifier labels the
        // catalog shows as the very same sound ("telephone_bell_ringing" and
        // "ringtone" both read "Phone ringing") must share one cooldown, or
        // a ring the classifier flips between the two labels on defeats the
        // cooldown entirely: two banners and two buzzes for what the user
        // heard as one ring. Wall-clock time can go backward (daylight
        // saving ending, an NTP sync); `>= last` keeps a jump from holding
        // back a new siren for as long as the jump.
        val last = lastAlertAt[event.cooldownKey]
        if (last != null && observation.timestamp >= last && observation.timestamp - last < cooldownSeconds) return null
        lastAlertAt[event.cooldownKey] = observation.timestamp
        return SoundAlert(event = event, confidence = observation.confidence, timestamp = observation.timestamp)
    }

    fun resetCooldowns() {
        lastAlertAt.clear()
        pendingSince.clear()
    }

    companion object {
        const val DEFAULT_COOLDOWN_SECONDS: Double = 20.0

        /**
         * Sounds that are continuous by nature (a siren, a running tap, an
         * alarm that keeps sounding), as opposed to a one-shot sound like a
         * knock or a gunshot, or one whose "continuous" sounding is really a
         * train of separate beeps with silent gaps between them.
         * `smoke_detector` is deliberately not here: its beep pattern (three
         * short beeps, then several seconds of silence, repeating) can leave
         * no two beeps inside the same short persistence window, so requiring
         * a second confirming window could delay or even miss a real fire
         * rather than just filter a TV spike.
         */
        internal val sustainedIdentifiers: Set<String> = setOf(
            "civil_defense_siren", "siren", "boiling", "whistling", "water_tap_faucet",
        )
    }
}
