package com.arbelonson.ozen.core

/** One caption line as the lock screen shows it. */
data class LockScreenCaptionLine(
    /** Set on the first line of a run by one named speaker. */
    val speaker: String?,
    val text: String,
    val isFinal: Boolean,
    /** When the line last changed. */
    val lastUpdate: Double = 0.0,
    /**
     * A line without a name that continues its speaker's run, as opposed to
     * one nobody was recognised on: a line drawn alone takes the name above
     * it only then.
     */
    val sameSpeakerAsAbove: Boolean = false,
)

/**
 * The newest caption lines, cut down to what fits on the lock screen, as on
 * the iPhone. The phone spends most of a conversation on the table or in a
 * hand with the screen locked, and unlocking it to read the last sentence is
 * one step too many while someone is talking.
 */
object LockScreenCaptions {
    /** Lines shown at once. */
    const val LINE_COUNT = 2

    /** Never cut a line shorter than this for a long speaker name. */
    const val MINIMUM_CHARACTERS = 20

    /**
     * From this long after the newest line, it says how long ago it was:
     * kept as if just said, a sentence from twenty minutes ago reads as the
     * latest thing someone said to her.
     */
    const val AGE_NOTE_AFTER_SECONDS = 60.0

    /** From this long after the newest line, no lines are shown. */
    const val CLEAR_AFTER_SECONDS = 15 * 60.0

    /**
     * The newest [count] lines with text, none from before a quiet stretch
     * (`CaptionLayout.startsAfterQuiet`). [name] gives the label for a
     * line's speaker, or null to show none. The first line shown always
     * carries its name, since on the lock screen there is nothing above it
     * to say who is talking; after that a name is kept only where the
     * speaker changes. An earlier line short enough to leave its own budget
     * unused hands the rest to the newest line.
     */
    fun lines(
        segments: List<TranscriptSegment>,
        count: Int = LINE_COUNT,
        textSize: LockScreenTextSize = LockScreenTextSize.Regular,
        name: (TranscriptSegment) -> String?,
    ): List<LockScreenCaptionLine> {
        val picked = ArrayDeque<TranscriptSegment>()
        for (segment in segments.asReversed()) {
            if (picked.size >= count) break
            if (segment.text.isBlank()) continue
            // The caption screen puts a clock time between lines a quiet
            // stretch apart; with no room for one here, the older line
            // would read as the one just before the words just said.
            val later = picked.firstOrNull()
            if (later != null && CaptionLayout.startsAfterQuiet(later, segment)) break
            picked.addFirst(segment)
        }
        val names = picked.map(name)
        val sameSpeakerAsAbove = names.indices.map { it > 0 && names[it] != null && names[it] == names[it - 1] }
        val speakers = names.indices.map { if (sameSpeakerAsAbove[it]) null else names[it] }
        // Room an earlier line leaves unused goes to the newest line, where
        // the words she needs are, instead of blank space above a short one.
        val bonus = picked.indices.filter { it != picked.lastIndex }.sumOf { offset ->
            maxOf(0, lineRoom(textSize.earlierLineMaximumCharacters, speakers[offset]) - characters(picked[offset].text.trim()))
        }
        return picked.mapIndexed { offset, segment ->
            val isNewest = offset == picked.lastIndex
            val budget = if (isNewest) textSize.newestLineMaximumCharacters + bonus else textSize.earlierLineMaximumCharacters
            var room = lineRoom(budget, speakers[offset])
            // The name counts even when hidden under the same speaker's line:
            // the line shown alone gets it back.
            if (isNewest) room = minOf(room, lineRoom(textSize.newestLineCapacity, names[offset]))
            LockScreenCaptionLine(
                speaker = speakers[offset],
                // Without the mark, a line ending in a Latin brand or
                // medication name reorders instead of reading right to left.
                text = CaptionLayout.directed(tail(segment.text, room)),
                isFinal = segment.isCommitted,
                lastUpdate = segment.lastUpdateTimestamp,
                sameSpeakerAsAbove = sameSpeakerAsAbove[offset],
            )
        }
    }

    /** A line's character budget once its speaker's name ("Speaker 2: ") has taken its share. */
    private fun lineRoom(budget: Int, speaker: String?): Int =
        maxOf(MINIMUM_CHARACTERS, budget - (speaker?.let { characters(it) + 2 } ?: 0))

    private fun characters(text: String) = text.codePointCount(0, text.length)

    /**
     * The end of [text], at most [maximumCharacters] long, starting at a
     * word and marked with an ellipsis when something was cut: the newest
     * words are the ones she needs.
     */
    fun tail(text: String, maximumCharacters: Int): String {
        val trimmed = text.trim()
        if (characters(trimmed) <= maximumCharacters || maximumCharacters <= 1) return trimmed
        val cutPoint = trimmed.offsetByCodePoints(trimmed.length, -(maximumCharacters - 1))
        var kept = trimmed.substring(cutPoint)
        // Whether the cut landed inside a word is what matters: a cut right
        // after a space already starts at a real word, however short, and a
        // cut mid-word skips to the next word however long that takes.
        val cutMidWord = cutPoint > 0 && !trimmed[cutPoint - 1].isWhitespace()
        val space = kept.indexOfFirst { it.isWhitespace() }
        if (cutMidWord && space >= 0) kept = kept.substring(space + 1)
        return "…$kept"
    }

    /** How the lock screen treats lines when nobody has spoken for a while. */
    sealed interface Quiet {
        /** Said just now: shown as they are. */
        data object Recent : Quiet

        /** Said this many minutes ago: the newest line only, saying so. */
        data class MinutesAgo(val minutes: Int) : Quiet

        /** Long enough ago that it isn't the conversation any more: no lines. */
        data object Over : Quiet
    }

    fun quiet(newestLineAt: Double?, now: Double): Quiet {
        if (newestLineAt == null) return Quiet.Recent
        val age = now - newestLineAt
        return when {
            age >= CLEAR_AFTER_SECONDS -> Quiet.Over
            age >= AGE_NOTE_AFTER_SECONDS -> Quiet.MinutesAgo((age / 60).toInt())
            else -> Quiet.Recent
        }
    }

    /** "said 3 minutes ago", for the lock screen. */
    fun ageNote(minutes: Int): String = tr("נאמר %1", "said %1", listOf(HebrewTime.minutesAgo(minutes)))
}

/**
 * How big the lock screen's lines are, and so how much of each fits: someone
 * who reads the captions large gets larger lines there too, and fewer words
 * of the line before.
 */
enum class LockScreenTextSize(
    /** Characters kept from the end of the newest line, cut from the front so its newest words stay. */
    val newestLineMaximumCharacters: Int,
    /** Three rows of about 30 (or 23) characters: the room a short line above leaves can't make a fourth. */
    val newestLineCapacity: Int,
    /** Characters kept from the end of each earlier line. */
    val earlierLineMaximumCharacters: Int,
) {
    Regular(80, 90, 45),
    Large(60, 69, 22),
    ;

    companion object {
        /** Captions this size or larger in the app make the lock screen large. */
        const val LARGE_FROM_CAPTION_SIZE = 34.0

        fun of(captionSize: Double) = if (captionSize >= LARGE_FROM_CAPTION_SIZE) Large else Regular
    }
}

/** Everything the lock screen shows: the newest lines, and a word on why they stopped coming when they have. */
data class LockScreenCaptionContent(
    val lines: List<LockScreenCaptionLine>,
    /** Set while captions aren't running but will again by themselves or with a tap. */
    val status: String? = null,
    /** How long ago the newest line was said, once that is a while (`LockScreenCaptions.quiet`). */
    val ageNote: String? = null,
    val textSize: LockScreenTextSize = LockScreenTextSize.Regular,
    /** The lock screen's own words follow the app's language, so a switch counts as something new. */
    val language: UILanguage = Localization.language,
)

/**
 * How often the lock screen's lines are sent to the system, as on the
 * iPhone: captions change several times a second while someone talks, and
 * the system drops updates that come that fast. Changes go at most once per
 * [minimumInterval]; one arriving sooner is sent when the interval is up,
 * so the last words of a sentence never stay unsent. A changed status goes
 * at once.
 */
class LockScreenUpdateThrottle(var minimumInterval: Double = BACKGROUND_INTERVAL) {
    sealed interface Decision {
        data object Send : Decision

        /** Try again after this many seconds. */
        data class Wait(val seconds: Double) : Decision

        data object NothingNew : Decision
    }

    private var lastSentAt: Double? = null
    private var lastSent: LockScreenCaptionContent? = null

    fun decide(content: LockScreenCaptionContent, now: Double): Decision {
        if (content == lastSent) return Decision.NothingNew
        val at = lastSentAt
        if (at == null || now < at || content.status != lastSent?.status || content.language != lastSent?.language) return Decision.Send
        val elapsed = now - at
        return if (elapsed >= minimumInterval) Decision.Send else Decision.Wait(minimumInterval - elapsed)
    }

    fun sent(content: LockScreenCaptionContent, at: Double) {
        lastSent = content
        lastSentAt = at
    }

    /** Whatever comes next is sent straight away. */
    fun reset() {
        lastSent = null
        lastSentAt = null
    }

    companion object {
        /** With the app out of sight: the lock screen may be what she reads. */
        const val BACKGROUND_INTERVAL = 1.0
    }
}
