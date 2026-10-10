package com.arbelonson.ozen.core

import kotlin.math.roundToInt

/** One person's part in a saved conversation. */
data class SpeakerShare(
    val name: String,
    /** The cluster the name was first seen with, for the speaker's colour. */
    val clusterID: Int?,
    val words: Int,
    /** Stretches of consecutive lines by this speaker. */
    val turns: Int,
) {
    val id: String get() = name
}

/** The longest uninterrupted stretch by one speaker. */
data class LongestTurn(val speakerName: String, val words: Int)

/**
 * A short, exact summary of a saved conversation: how long, how many
 * words, who said how much. Saved lines carry only their start time, so
 * nothing here pretends to know how long anyone actually spoke; every
 * number is a count or a span between real timestamps.
 */
data class ConversationStats(
    val totalWords: Int,
    val totalTurns: Int,
    val durationSeconds: Double,
    /** Sorted by words, most first; ties by name. */
    val speakers: List<SpeakerShare>,
    val longestTurn: LongestTurn?,
) {
    val wordsPerMinute: Double
        get() = if (durationSeconds >= 1) totalWords / (durationSeconds / 60) else 0.0

    fun wordFraction(of: SpeakerShare): Double =
        if (totalWords > 0) of.words.toDouble() / totalWords else 0.0

    /**
     * "12 dakot · 3 dovrim · 840 milim" ("12 minutes · 3 speakers ·
     * 840 words"), with Hebrew's special forms for one and two.
     */
    val hebrewSummary: String
        get() {
            val parts = mutableListOf(minutesText(durationSeconds))
            if (speakers.isNotEmpty()) parts.add(speakersText(speakers.size))
            parts.add(wordsText(totalWords))
            return parts.joinToString(" · ")
        }

    companion object {
        val unknownSpeakerName: String get() = tr("דובר לא ידוע", "Unknown speaker")

        fun compute(record: TranscriptSessionRecord): ConversationStats =
            compute(record.segments, record.startedAt, record.endedAt)

        /**
         * Duration is the recorded session span when the session ended
         * cleanly, otherwise first line to last line.
         */
        fun compute(
            segments: List<SavedSegment>,
            startedAt: Double? = null,
            endedAt: Double? = null,
        ): ConversationStats {
            val wordsBySpeaker = HashMap<String, Int>()
            val turnsBySpeaker = HashMap<String, Int>()
            val clusterBySpeaker = HashMap<String, Int>()
            val order = ArrayList<String>()
            var totalWords = 0
            var totalTurns = 0
            var longest: LongestTurn? = null
            var currentSpeaker: String? = null
            var currentTurnWords = 0

            fun closeTurn() {
                val speaker = currentSpeaker ?: return
                if (currentTurnWords > (longest?.words ?: 0)) longest = LongestTurn(speaker, currentTurnWords)
            }

            for (segment in segments) {
                val name = speakerName(segment)
                val words = HebrewText.words(segment.text).size
                if (name !in wordsBySpeaker) {
                    order.add(name)
                    wordsBySpeaker[name] = 0
                    turnsBySpeaker[name] = 0
                }
                val cluster = segment.speakerClusterID
                if (name !in clusterBySpeaker && cluster != null) clusterBySpeaker[name] = cluster
                wordsBySpeaker[name] = wordsBySpeaker.getValue(name) + words
                totalWords += words

                if (name != currentSpeaker) {
                    closeTurn()
                    currentSpeaker = name
                    currentTurnWords = 0
                    turnsBySpeaker[name] = turnsBySpeaker.getValue(name) + 1
                    totalTurns += 1
                }
                currentTurnWords += words
            }
            closeTurn()

            val speakers = order
                .map { SpeakerShare(it, clusterBySpeaker[it], wordsBySpeaker[it] ?: 0, turnsBySpeaker[it] ?: 0) }
                .sortedWith(compareByDescending<SpeakerShare> { it.words }.thenBy { it.name })

            val duration = when {
                startedAt != null && endedAt != null && endedAt > startedAt -> endedAt - startedAt
                segments.isNotEmpty() -> maxOf(0.0, segments.last().startTimestamp - segments.first().startTimestamp)
                else -> 0.0
            }
            return ConversationStats(totalWords, totalTurns, duration, speakers, longest)
        }

        private fun speakerName(segment: SavedSegment): String {
            // A line saved while the app was in another language keeps that
            // language's "Unknown speaker", which is still nobody in particular.
            val name = segment.speakerName?.trim()
            if (name.isNullOrEmpty() || TranscriptSessionSummary.isUnknownSpeakerLabel(name)) return unknownSpeakerName
            return name
        }

        /**
         * How long, in words: "12 dakot", and from an hour on the way people
         * say it, "sha'a va-reva" ("an hour and a quarter"), "sha'atayim
         * va-chetzi" ("two and a half hours"). Every other language keeps a
         * plain "1 hour 15 minutes" style instead of Hebrew's quarter/half
         * phrasing.
         */
        fun minutesText(seconds: Double): String {
            when (val language = Localization.language) {
                UILanguage.English -> return englishMinutesText(seconds)
                UILanguage.Hebrew -> Unit
                else -> return genericMinutesText(seconds, language)
            }
            val minutes = (seconds / 60).roundToInt()
            when {
                minutes < 1 -> return "פחות מדקה"
                minutes == 1 -> return "דקה אחת"
                minutes == 2 -> return "שתי דקות"
                minutes < 60 -> return "$minutes דקות"
            }
            val hoursText = when (val hours = minutes / 60) {
                1 -> "שעה"
                2 -> "שעתיים"
                else -> "$hours שעות"
            }
            return when (val rest = minutes % 60) {
                0 -> hoursText
                1 -> "$hoursText ודקה"
                2 -> "$hoursText ושתי דקות"
                15 -> "$hoursText ורבע"
                30 -> "$hoursText וחצי"
                45 -> "$hoursText ושלושה רבעים"
                else -> "$hoursText ו-$rest דקות"
            }
        }

        private fun englishMinutesText(seconds: Double): String {
            val minutes = (seconds / 60).roundToInt()
            if (minutes < 1) return "less than a minute"
            if (minutes < 60) return "$minutes minute${if (minutes == 1) "" else "s"}"
            val hours = minutes / 60
            val rest = minutes % 60
            val hoursText = "$hours hour${if (hours == 1) "" else "s"}"
            return if (rest == 0) hoursText else "$hoursText $rest minute${if (rest == 1) "" else "s"}"
        }

        fun speakersText(count: Int): String {
            when (val language = Localization.language) {
                UILanguage.English -> return if (count == 1) "1 speaker" else "$count speakers"
                UILanguage.Hebrew -> Unit
                else -> return genericSpeakersText(count, language)
            }
            return when (count) {
                1 -> "דובר אחד"
                2 -> "שני דוברים"
                else -> "$count דוברים"
            }
        }

        /**
         * "one line", "two lines" and "7 lines", with [adjective] after the
         * noun as Hebrew puts it ("starred"), in its singular and plural.
         * [englishAdjective], when given, sits before the noun instead
         * ("3 starred lines"), as English puts it. Every other language
         * carries its own agreement for "starred" and "new"; an adjective
         * this file doesn't know falls back to the English wording rather
         * than guessing at it.
         */
        fun linesText(
            count: Int,
            adjective: Pair<String, String>? = null,
            englishAdjective: String? = null,
        ): String {
            when (val language = Localization.language) {
                UILanguage.English -> return englishLinesText(count, englishAdjective)
                UILanguage.Hebrew -> Unit
                else -> return genericLinesText(count, englishAdjective, language)
            }
            val singular = adjective?.let { " " + it.first } ?: ""
            val plural = adjective?.let { " " + it.second } ?: ""
            return when (count) {
                1 -> "שורה$singular אחת"
                2 -> "שתי שורות$plural"
                else -> "$count שורות$plural"
            }
        }

        private fun englishLinesText(count: Int, adjective: String?): String {
            val prefix = adjective?.let { "$it " } ?: ""
            return if (count == 1) "1 ${prefix}line" else "$count ${prefix}lines"
        }

        fun wordsText(count: Int): String {
            when (val language = Localization.language) {
                UILanguage.English -> return englishWordsText(count)
                UILanguage.Hebrew -> Unit
                else -> return genericWordsText(count, language)
            }
            return when (count) {
                0 -> "אין מילים"
                1 -> "מילה אחת"
                2 -> "שתי מילים"
                else -> "$count מילים"
            }
        }

        /**
         * "About 21 minutes left" for a download (3 to 59 minutes). Russian
         * "около" and Ukrainian "близько" take the genitive, singular after
         * 21, 31, 41, 51; Arabic counts 3 to 10 in the plural. The table has
         * one wording per language, so only the noun is changed here.
         */
        fun aboutMinutesLeftText(minutes: Int): String {
            val text = tr("עוד כ-%1 דקות", "About %1 minutes left", listOf("$minutes"))
            val endsInOne = minutes % 10 == 1 && minutes % 100 != 11
            return when (Localization.language) {
                UILanguage.Russian -> if (endsInOne) text.replace(" минут", " минуты") else text
                UILanguage.Ukrainian -> if (endsInOne) text.replace(" хвилин", " хвилини") else text
                UILanguage.Arabic -> if (minutes in 3..10) text.replace("دقيقة", "دقائق") else text
                else -> text
            }
        }

        /**
         * "12 old conversations will be deleted now" (3 or more). The table
         * has one wording per language, the form for 5 and up in Russian and
         * Ukrainian and for 3 to 10 in Arabic; the phrase is changed here for
         * the numbers that take another (the same forms as the table's own
         * "1" and "2" lines).
         */
        fun oldConversationsDeletedText(count: Int): String {
            val text = tr("%1 שיחות ישנות יימחקו עכשיו", "%1 old conversations will be deleted now", listOf("$count"))
            val language = Localization.language
            val category = pluralCategory(count, language)
            return when {
                language == UILanguage.Russian && category == PluralCategory.One ->
                    text.replace("старых разговоров будут удалены", "старый разговор будет удалён")
                language == UILanguage.Russian && category == PluralCategory.Few ->
                    text.replace("старых разговоров", "старых разговора")
                language == UILanguage.Ukrainian && category == PluralCategory.One ->
                    text.replace("старих розмов", "стару розмову")
                language == UILanguage.Ukrainian && category == PluralCategory.Few ->
                    text.replace("старих розмов", "старі розмови")
                language == UILanguage.Arabic && (category == PluralCategory.Many || category == PluralCategory.Other) ->
                    text.replace("محادثات قديمة", "محادثة قديمة")
                else -> text
            }
        }

        /**
         * "one second", "two seconds", "7 seconds", as TalkBack reads a
         * voice sample's recording progress.
         */
        fun secondsText(count: Int): String {
            when (val language = Localization.language) {
                UILanguage.English -> return if (count == 1) "1 second" else "$count seconds"
                UILanguage.Hebrew -> Unit
                else -> {
                    val category = pluralCategory(count, language)
                    return countedPhrase(count, TimeUnitWord.second(category, language), omitsNumeral(category, language))
                }
            }
            return when (count) {
                1 -> "שנייה אחת"
                2 -> "שתי שניות"
                else -> "$count שניות"
            }
        }

        fun paceText(wordsPerMinute: Double): String =
            tr("%1 לדקה", "%1 per minute", listOf(wordsText(wordsPerMinute.roundToInt())))

        private fun englishWordsText(count: Int): String = when (count) {
            0 -> "no words"
            1 -> "1 word"
            else -> "$count words"
        }

        // The other ten languages.

        private fun genericMinutesText(seconds: Double, language: UILanguage): String {
            val minutes = (seconds / 60).roundToInt()
            if (minutes < 1) return TimeUnitWord.lessThanAMinute(language)
            if (minutes < 60) return countedTime(minutes, hour = false, language)
            val hoursPhrase = countedTime(minutes / 60, hour = true, language)
            val rest = minutes % 60
            if (rest == 0) return hoursPhrase
            val minutesPhrase = countedTime(rest, hour = false, language)
            // Arabic writes one and two as the word alone ("ساعة", "دقيقتان"):
            // without its "and" (و, joined to the next word) two such words
            // side by side don't read as a duration at all.
            return if (language == UILanguage.Arabic) "$hoursPhrase و$minutesPhrase" else "$hoursPhrase $minutesPhrase"
        }

        private fun countedTime(n: Int, hour: Boolean, language: UILanguage): String {
            val category = pluralCategory(n, language)
            val word = if (hour) TimeUnitWord.hour(category, language) else TimeUnitWord.minute(category, language)
            return countedPhrase(n, word, omitsNumeral(category, language))
        }

        private fun genericSpeakersText(count: Int, language: UILanguage): String {
            val category = pluralCategory(count, language)
            return countedPhrase(count, speakerWord(category, language), omitsNumeral(category, language))
        }

        private fun speakerWord(category: PluralCategory, language: UILanguage): String = when (language) {
            UILanguage.Russian -> when (category) {
                PluralCategory.One -> "собеседник"
                PluralCategory.Few -> "собеседника"
                else -> "собеседников"
            }
            UILanguage.Ukrainian -> when (category) {
                PluralCategory.One -> "співрозмовник"
                PluralCategory.Few -> "співрозмовники"
                else -> "співрозмовників"
            }
            UILanguage.Arabic -> when (category) {
                PluralCategory.One -> "متحدث"
                PluralCategory.Two -> "متحدثان"
                PluralCategory.Few -> "متحدثين"
                else -> "متحدث"
            }
            UILanguage.French -> if (category == PluralCategory.One) "intervenant" else "intervenants"
            UILanguage.Spanish -> if (category == PluralCategory.One) "interlocutor" else "interlocutores"
            UILanguage.German -> "Sprecher"
            UILanguage.Portuguese -> if (category == PluralCategory.One) "interlocutor" else "interlocutores"
            UILanguage.Hindi -> "वक्ता"
            UILanguage.Amharic -> if (category == PluralCategory.One) "ተናጋሪ" else "ተናጋሪዎች"
            UILanguage.ChineseSimplified -> "位发言人"
            UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "speaker" else "speakers"
        }

        private fun genericWordsText(count: Int, language: UILanguage): String {
            if (count == 0) return noWordsPhrase(language)
            val category = pluralCategory(count, language)
            return countedPhrase(count, wordWord(category, language), omitsNumeral(category, language))
        }

        private fun noWordsPhrase(language: UILanguage): String = when (language) {
            UILanguage.Russian -> "нет слов"
            UILanguage.Ukrainian -> "немає слів"
            UILanguage.Arabic -> "لا كلمات"
            UILanguage.French -> "aucun mot"
            UILanguage.Spanish -> "sin palabras"
            UILanguage.German -> "keine Wörter"
            UILanguage.Portuguese -> "sem palavras"
            UILanguage.Hindi -> "कोई शब्द नहीं"
            UILanguage.Amharic -> "ምንም ቃላት የሉም"
            UILanguage.ChineseSimplified -> "没有字"
            UILanguage.Hebrew, UILanguage.English -> "no words"
        }

        private fun wordWord(category: PluralCategory, language: UILanguage): String = when (language) {
            UILanguage.Russian -> when (category) {
                PluralCategory.One -> "слово"
                PluralCategory.Few -> "слова"
                else -> "слов"
            }
            UILanguage.Ukrainian -> when (category) {
                PluralCategory.One -> "слово"
                PluralCategory.Few -> "слова"
                else -> "слів"
            }
            UILanguage.Arabic -> when (category) {
                PluralCategory.One -> "كلمة"
                PluralCategory.Two -> "كلمتان"
                PluralCategory.Few -> "كلمات"
                else -> "كلمة"
            }
            UILanguage.French -> if (category == PluralCategory.One) "mot" else "mots"
            UILanguage.Spanish -> if (category == PluralCategory.One) "palabra" else "palabras"
            UILanguage.German -> if (category == PluralCategory.One) "Wort" else "Wörter"
            UILanguage.Portuguese -> if (category == PluralCategory.One) "palavra" else "palavras"
            UILanguage.Hindi -> "शब्द"
            UILanguage.Amharic -> if (category == PluralCategory.One) "ቃል" else "ቃላት"
            UILanguage.ChineseSimplified -> "字"
            UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "word" else "words"
        }

        private fun genericLinesText(count: Int, englishAdjective: String?, language: UILanguage): String {
            val category = pluralCategory(count, language)
            if (englishAdjective == null) {
                return countedPhrase(count, lineWord(category, language), omitsNumeral(category, language))
            }
            return adjectiveLinePhrase(count, category, englishAdjective, language) ?: englishLinesText(count, englishAdjective)
        }

        private fun lineWord(category: PluralCategory, language: UILanguage): String = when (language) {
            UILanguage.Russian -> when (category) {
                PluralCategory.One -> "строка"
                PluralCategory.Few -> "строки"
                else -> "строк"
            }
            UILanguage.Ukrainian -> when (category) {
                PluralCategory.One -> "рядок"
                PluralCategory.Few -> "рядки"
                else -> "рядків"
            }
            UILanguage.Arabic -> when (category) {
                PluralCategory.One -> "سطر"
                PluralCategory.Two -> "سطران"
                PluralCategory.Few -> "أسطر"
                else -> "سطر"
            }
            UILanguage.French -> if (category == PluralCategory.One) "ligne" else "lignes"
            UILanguage.Spanish -> if (category == PluralCategory.One) "línea" else "líneas"
            UILanguage.German -> if (category == PluralCategory.One) "Zeile" else "Zeilen"
            UILanguage.Portuguese -> if (category == PluralCategory.One) "linha" else "linhas"
            UILanguage.Hindi -> if (category == PluralCategory.One) "पंक्ति" else "पंक्तियाँ"
            UILanguage.Amharic -> if (category == PluralCategory.One) "መስመር" else "መስመሮች"
            UILanguage.ChineseSimplified -> "行"
            UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "line" else "lines"
        }

        /**
         * Null when [adjective] isn't one this file has agreement forms
         * for, so the caller can fall back to English rather than Hebrew.
         */
        private fun adjectiveLinePhrase(count: Int, category: PluralCategory, adjective: String, language: UILanguage): String? {
            val noun = lineWord(category, language)
            val one = category == PluralCategory.One
            val few = category == PluralCategory.Few
            val word = when (language to adjective) {
                UILanguage.Russian to "new" -> "${if (one) "новая" else if (few) "новые" else "новых"} $noun"
                UILanguage.Russian to "starred" -> "${if (one) "избранная" else if (few) "избранные" else "избранных"} $noun"
                UILanguage.Ukrainian to "new" -> "${if (one) "новий" else if (few) "нові" else "нових"} $noun"
                UILanguage.Ukrainian to "starred" -> "${if (one) "позначений" else if (few) "позначені" else "позначених"} $noun"
                UILanguage.Arabic to "new" -> "$noun ${arabicForm(category, "جديد", "جديدان", "جديدة")}"
                UILanguage.Arabic to "starred" -> "$noun ${arabicForm(category, "مفضل", "مفضلان", "مفضلة")}"
                UILanguage.French to "new" -> "${if (one) "nouvelle" else "nouvelles"} $noun"
                UILanguage.French to "starred" -> "$noun ${if (one) "favorite" else "favorites"}"
                UILanguage.Spanish to "new" -> "$noun ${if (one) "nueva" else "nuevas"}"
                UILanguage.Spanish to "starred" -> "$noun ${if (one) "destacada" else "destacadas"}"
                UILanguage.German to "new" -> "neue $noun"
                UILanguage.German to "starred" -> "markierte $noun"
                UILanguage.Portuguese to "new" -> "$noun ${if (one) "nova" else "novas"}"
                UILanguage.Portuguese to "starred" -> "$noun ${if (one) "marcada" else "marcadas"}"
                UILanguage.Hindi to "new" -> "नई $noun"
                UILanguage.Hindi to "starred" -> "चिह्नित $noun"
                UILanguage.Amharic to "new" -> "አዲስ $noun"
                UILanguage.Amharic to "starred" -> "ኮከብ የተሰጠው $noun"
                UILanguage.ChineseSimplified to "new" -> "新$noun"
                UILanguage.ChineseSimplified to "starred" -> "星标$noun"
                else -> return null
            }
            return countedPhrase(count, word, omitsNumeral(category, language))
        }

        private fun arabicForm(category: PluralCategory, one: String, two: String, few: String): String = when (category) {
            PluralCategory.One -> one
            PluralCategory.Two -> two
            PluralCategory.Few -> few
            else -> one
        }
    }
}
