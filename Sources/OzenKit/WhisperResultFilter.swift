import Foundation

/// The per-segment numbers Whisper reports alongside its text, reduced to
/// what the filter below needs. Kept as a plain struct so the filtering
/// rules are testable here without WhisperKit's own result types.
public struct WhisperSegmentSummary: Sendable, Equatable {
    public var text: String
    /// Probability the model assigns to "this window contains no speech".
    public var noSpeechProb: Float
    /// Mean log-probability of the emitted tokens, as `averageLogprob`
    /// works it out; very negative means the model was guessing.
    public var avgLogprob: Float
    /// zlib compression ratio — high values mean repetitive output, the
    /// signature of a decoding loop ("toda toda toda ..."). The home
    /// computer measures it over the text; the phone's engine (WhisperKit)
    /// over its token numbers, where the same short repeat scores higher.
    public var compressionRatio: Float
    /// The words of this segment the model was least sure of (see
    /// `UncertainWords`), when the caller worked them out.
    public var uncertainWords: [String]
    /// Above 0 when the plain decode failed the model's own checks and this
    /// is a retry with sharpened odds (see `CaptionConfidence.retriedLine`).
    public var temperature: Float

    public init(text: String, noSpeechProb: Float, avgLogprob: Float, compressionRatio: Float, uncertainWords: [String] = [], temperature: Float = 0) {
        self.text = text
        self.noSpeechProb = noSpeechProb
        self.avgLogprob = avgLogprob
        self.compressionRatio = compressionRatio
        self.uncertainWords = uncertainWords
        self.temperature = temperature
    }

    /// The words' token scores summed, over their count plus one for the
    /// end of text: the reference Whisper's average, and the home
    /// computer's, on which every cutoff here and the unsure mark's were
    /// measured. WhisperKit 1.1's own also counts the four tokens that open
    /// every line and the end at a perfect 0, which lifted a misheard short
    /// line from 0.43 to 0.60 and kept the question mark off it: of 839
    /// broadcast lines through Turbo, 10 of the 21 marked lost the mark,
    /// 9 of them with a word wrong. The end's own score, which WhisperKit
    /// doesn't keep, counts as 0.
    public static func averageLogprob(wordTokenLogprobs: [Float]) -> Float? {
        guard !wordTokenLogprobs.isEmpty else { return nil }
        return wordTokenLogprobs.reduce(0, +) / Float(wordTokenLogprobs.count + 1)
    }
}

/// How much a line the phone's engine can write out whole. WhisperKit 1.1
/// decodes in 224 token positions (its `Constants.maxTokenContext`, half
/// of Whisper's 448) and stops at 223: the four that open a line, the
/// names prompt with its marker, then the words; what it hasn't written
/// by then is skipped with the rest of the window. Real long lines needed
/// up to 8.3 tokens a second (123 broadcast lines of 22-27 s through
/// ivrit.ai's Turbo, median 6.2), so with 60 tokens of names 86 of them
/// would have lost their end, and with WhisperKit's most, every one.
/// A line is cut short enough to fit instead, at a breath
/// (`UtteranceCut`), and nothing is lost.
public enum WhisperKitDecodeRoom {
    public static let positions = 223
    public static let tokensPerSecond = 8.5
    /// WhisperKit keeps the last 111 tokens of a longer prompt, which
    /// would drop the names listed first; cut here, from the end, instead.
    /// Each token of names takes 0.12 s off the longest line.
    public static let maxPromptTokens = 80

    public static func longestLineSeconds(promptTokens: Int, upTo cap: Double) -> Double {
        let opening = 4 + (promptTokens > 0 ? promptTokens + 1 : 0)
        return min(cap, Double(positions - opening) / tokensPerSecond)
    }

    /// How many words a pass over a line still being said may write. On
    /// 839 recordings through ivrit.ai's Turbo, 11 of 3,117 such passes
    /// looped on a drawn-out sound (an "ehhh" written as one letter over
    /// and over) and ran to the decoder's end, 224 tokens: the filter
    /// throws the pass away, but the phone had spent a whole decode on it
    /// and waits twice as long again before the next. Real speech, there
    /// and in 3,485 more passes over 82 windows of up to 27 s, never wrote
    /// more than 12 tokens in 0.6 s, 20 in 1.2 s or 50 in 4.8 s (17.4 a
    /// second at the 99.9th percentile), so a pass that fills this much
    /// room is a loop, and the screen keeps what it had. The pass that
    /// ends the line keeps the whole decoder.
    public static func livePassTokens(seconds: Double) -> Int {
        min(positions, Int((seconds * 20).rounded(.up)) + 16)
    }

    public static func livePassRanOut(wordTokens: Int, seconds: Double) -> Bool {
        wordTokens >= livePassTokens(seconds: seconds)
    }
}

/// Whisper is famous for hallucinating on silence and background noise:
/// given a quiet room it will happily emit "toda raba" ("thanks"), "ktuviot
/// al yedei ..." ("captions by ..."), or "Subtitles by the Amara.org
/// community". For a captioning app that's worse than showing nothing — the
/// reader can't tell an invented sentence from a real one. This applies the
/// same three statistical checks the reference Whisper implementation uses
/// to decide a window is junk, plus a short list of phrases the model is
/// known to invent on silence in Hebrew and English.
///
/// On the home computer (faster-whisper 1.2 on CTranslate2 4.8, October
/// 2026) the large-v3 models, ivrit.ai's Turbo and large and OpenAI's
/// Turbo, put the no-speech probability at about 0.0001 on everything,
/// pure silence included (ivrit.ai's large is 99.9% sure silence is
/// Hebrew), where OpenAI's Small gives 0.87 on silence and 0.78 on
/// kitchen noise; and they write a confident "toda raba" on faint hiss. For its lines the
/// checks below that read it never fire, and what keeps an invented
/// "thank you" off the screen is the voice check before the model hears a
/// line (the computer's speech gate; on the phone, `VoiceEvidence`, and
/// for a lone thank-you, how much voice it held, `isUnvoicedPhrase`). The
/// phone's engine never works it out: WhisperKit 1.1 reports 0 for every
/// segment ("TODO: implement no speech prob" in its TextDecoder), so on
/// the phone too they never fire.
public struct WhisperResultFilter: Sendable, Equatable {
    public var noSpeechThreshold: Float
    public var logprobThreshold: Float
    public var compressionRatioThreshold: Float
    public var knownHallucinations: Set<String>
    /// Phrases Whisper invents on noise that people also genuinely say in
    /// conversation ("toda", "toda raba" — "thanks", "thank you very much").
    /// Dropped only when the segment's own statistics look like noise, or
    /// the voice model heard too little voice under it (`isUnvoicedPhrase`),
    /// never just for being the phrase: missing a real "thank you" is its own
    /// kind of wrong.
    public var ambiguousHallucinations: Set<String>
    /// Above this no-speech probability an ambiguous phrase is treated as
    /// invented. Real short speech sits far below it (and so does
    /// everything the home computer's large models and the phone's engine
    /// send, see above).
    public var ambiguousNoSpeechThreshold: Float
    /// Below this mean log-probability an ambiguous phrase is treated as
    /// a guess.
    public var ambiguousLogprobThreshold: Float
    /// See `isUnvoicedPhrase`.
    public var minimumPhraseVoicedChunks = 2
    /// Openings of the credit lines Whisper invents on silence, which come with
    /// an arbitrary name attached ("ktuviot al yedei <name>" — "captions by
    /// <name>"), so an exact-phrase list can't catch them.
    public var hallucinatedCreditPrefixes: [String]
    /// A credit prefix only condemns a short segment; a long one that
    /// happens to start the same way is someone actually talking.
    public var maximumCreditLineWords: Int
    /// A bare label followed by a dash ("עריכה - ישראל") is a real, common
    /// subtitle-community sign-off, but a dash is also just how someone
    /// pauses mid-sentence after saying that same word. Tighter than
    /// `maximumCreditLineWords` since a genuine credit line is short.
    public var maximumDashCreditLineWords: Int

    /// Bare labels ("ktuviot" / "captions", "targum" / "translation") are
    /// ordinary words too, so they only count as a credit when a colon follows,
    /// as in "targum: Michal" ("translation: Michal").
    public var hallucinatedCreditLabels: [String]

    /// Each also in the short written form of "by" that subtitle files use
    /// (ayin-gershayim-yod, which the normalizer reads without its mark),
    /// and the "translated and synced by" credit of Hebrew subtitle sites.
    public static let defaultCreditPrefixes: [String] = [
        "כתוביות על ידי", "תורגם על ידי", "תרגום על ידי", "תמלול על ידי", "תוכתב על ידי",
        "כתוביות ע״י", "תורגם ע״י", "תרגום ע״י", "תמלול ע״י",
        "תורגם וסונכרן", "סונכרן על ידי", "סונכרן ע״י",
        "subtitles by", "subtitled by", "translated by", "transcribed by", "captions by",
        "ترجمة نانسي قنقر", "تمت الترجمة بواسطة",
        "subtítulos realizados por la comunidad de amara.org",
        "sous-titres réalisés par la communauté d'amara.org",
        "untertitel der amara.org-community",
        "legendas pela comunidade amara.org",
        "субтитры сделал",
    ]

    public static let defaultCreditLabels: [String] = [
        "כתוביות", "תרגום", "תמלול", "הפקה", "עריכה", "סנכרון", "subtitles", "translation", "captions", "ترجمة",
    ]

    /// Nobody says these to someone across a dinner table: they are
    /// broadcast credits and sound tags, always dropped.
    public static let defaultKnownHallucinations: Set<String> = [
        "תודה שצפיתם", "תודה על הצפייה", "תודה על הצפיה", "תודה שהאזנתם",
        "כתוביות", "תרגום", "תרגום וכתוביות", "כתוביות על ידי", "תרגום על ידי",
        "מחיאות כפיים",
        // English leaks through even with the language forced to Hebrew.
        "thanks for watching", "thank you for watching",
        "subtitles by the amara.org community", "subtitles by", "you",
        "music", "applause", "laughter",
        // Inherited from Whisper's YouTube-heavy training data: an outro
        // nobody in a real conversation says, and the model's own
        // uncertainty tag for audio it can't place (brackets and
        // parentheses are already gone by the time this is compared).
        "speaking in a foreign language", "please subscribe",
        "don't forget to subscribe", "like and subscribe",
        // The same outros as Hebrew YouTube and subtitle files word them.
        "תודה רבה שצפיתם", "תודה רבה לכם שצפיתם", "תודה שצפיתם בסרטון",
        "הירשמו לערוץ", "תירשמו לערוץ", "אל תשכחו להירשם לערוץ",
        "اشتركوا في القناة", "اشترك في القناة", "شكرا على المشاهدة", "شكرا لمشاهدتكم",
        "продолжение следует",
    ]

    public static let defaultAmbiguousHallucinations: Set<String> = [
        "תודה", "תודה רבה", "תודה לכם", "thank you",
        // The sound tags Whisper writes on music and laughter, but also
        // words people say, and Shira is a common girl's name: "שירה!"
        // called across the room was dropped however clearly it was heard.
        // In brackets they are still always tags (see `isBracketed`).
        "מוזיקה", "שירה", "צחוק",
        // Unlike the subscribe lines above, a real farewell could
        // plausibly sound like this, so it only drops when the model was
        // also unsure of itself.
        "see you next time", "see you in the next video",
        "נתראה בסרטון הבא", "צפייה מהנה", "צפיה מהנה",
        "شكرا", "شكرا لكم",
        "gracias", "merci", "danke", "obrigado", "спасибо",
    ]

    public init(
        noSpeechThreshold: Float = 0.6,
        logprobThreshold: Float = -1.0,
        compressionRatioThreshold: Float = 2.4,
        knownHallucinations: Set<String> = WhisperResultFilter.defaultKnownHallucinations,
        ambiguousHallucinations: Set<String> = WhisperResultFilter.defaultAmbiguousHallucinations,
        ambiguousNoSpeechThreshold: Float = 0.25,
        ambiguousLogprobThreshold: Float = -0.9,
        hallucinatedCreditPrefixes: [String] = WhisperResultFilter.defaultCreditPrefixes,
        hallucinatedCreditLabels: [String] = WhisperResultFilter.defaultCreditLabels,
        maximumCreditLineWords: Int = 7,
        maximumDashCreditLineWords: Int = 4
    ) {
        self.hallucinatedCreditLabels = hallucinatedCreditLabels.map { $0.lowercased() }
        self.ambiguousHallucinations = Set(ambiguousHallucinations.map(Self.normalize))
        self.ambiguousNoSpeechThreshold = ambiguousNoSpeechThreshold
        self.ambiguousLogprobThreshold = ambiguousLogprobThreshold
        self.hallucinatedCreditPrefixes = hallucinatedCreditPrefixes.map(Self.normalize)
        self.maximumCreditLineWords = maximumCreditLineWords
        self.maximumDashCreditLineWords = maximumDashCreditLineWords
        self.noSpeechThreshold = noSpeechThreshold
        self.logprobThreshold = logprobThreshold
        self.compressionRatioThreshold = compressionRatioThreshold
        // Stored pre-normalized so lookups compare like with like — an
        // entry written as "amara.org" would otherwise never match input
        // whose dot the normalizer already stripped.
        self.knownHallucinations = Set(knownHallucinations.map(Self.normalize))
    }

    /// The filter for speech in `languageCode`. In English a lone "you" is
    /// a real answer as often as it is Whisper's filler for silence, and
    /// "music", "applause" and "laughter" are words people say as well as
    /// its sound tags, so there they only go when the model was also
    /// unsure, like "thank you" (and in brackets, see `isBracketed`); in
    /// any other language they can only be the filler.
    ///
    /// Speech in a language with its own list in
    /// Resources/WhisperLanguageLines.json, shared with the Android app,
    /// also drops what Whisper writes on silence in that language: the
    /// community-subtitle credits it learned from Amara.org files and the
    /// channel outros. A plain thank-you there is only ambiguous, since
    /// people say it.
    public func forSpeech(in languageCode: String) -> WhisperResultFilter {
        let language = UILanguage.match(languageTag: languageCode)
        var filter = self
        if language == .english {
            for word in Self.englishWordsAlsoSaid.map(Self.normalize) {
                if filter.knownHallucinations.remove(word) != nil {
                    filter.ambiguousHallucinations.insert(word)
                }
            }
        }
        if let lines = WhisperLanguageLines.lines(for: language) {
            filter.knownHallucinations.formUnion(lines.known.map(Self.normalize))
            filter.ambiguousHallucinations.formUnion(lines.ambiguous.map(Self.normalize))
            filter.hallucinatedCreditPrefixes += lines.creditPrefixes.map(Self.normalize)
        }
        return filter
    }

    /// See `forSpeech(in:)`.
    static let englishWordsAlsoSaid = ["you", "music", "applause", "laughter"]

    /// The text worth showing from one decoding pass, with junk segments
    /// dropped. Empty when nothing survives — the caller should then emit
    /// nothing rather than a blank token.
    ///
    /// `echo` drops segments that are just the vocabulary prompt read back
    /// (see `PromptEchoDetector`).
    public func acceptedText(from segments: [WhisperSegmentSummary], echo: PromptEchoDetector? = nil) -> String {
        let joined = accepted(from: segments, echo: echo)
            .map { segment in
                let text = withoutNotes(Self.stripSpecialTokens(segment.text).trimmingCharacters(in: .whitespacesAndNewlines))
                guard segment.compressionRatio > compressionRatioThreshold else { return text }
                return Self.repeatedSentence(text) ?? text
            }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return Self.collapsingRepeats(joined)
    }

    /// Whether `text` is nothing but one of the ambiguous phrases, heard
    /// in fewer than `minimumPhraseVoicedChunks` of the voice model's
    /// 0.256 s chunks. Household noise that gets past the voice check
    /// comes back as a "toda raba" scored like a real one: 35 real
    /// thank-yous (five cut from broadcast lines, each clean and at 10 to
    /// 0 dB of kitchen and living-room noise) averaged -0.07 or better,
    /// nowhere near `ambiguousLogprobThreshold`. The voice check is what
    /// tells them apart. Through the phone's gate, 45 minutes of kitchen,
    /// living-room and laundry noise (three microphones in each room) gave
    /// 7 lines that came back as a thank-you, 5 of them with one voiced
    /// chunk; the real ones (0.35 to 0.42 s) had two or more in 312 of 320
    /// placements across the chunk grid. Without a count nothing is dropped.
    ///
    /// With `echo`, the same goes for the whole prompt of a one-entry list
    /// (`PromptEchoDetector.isWholePrompt`), which comes back on silence
    /// and would then set off its alert. Not measured on names: the
    /// thank-you numbers above are all there is.
    public func isUnvoicedPhrase(_ text: String, voicedChunks: Int?, echo: PromptEchoDetector? = nil) -> Bool {
        guard let voicedChunks, voicedChunks < minimumPhraseVoicedChunks else { return false }
        return ambiguousHallucinations.contains(Self.normalize(Self.collapsingRepeats(text, maxRepeats: 1)))
            || echo?.isWholePrompt(text) == true
    }

    /// The subset of `segments` that survive into `acceptedText`, for a
    /// caller that needs to derive something else (confidence, timing)
    /// from exactly the content actually shown, not from segments that
    /// were rejected as hallucinations or noise.
    ///
    /// The whole prompt of a one-entry list goes only when the model was
    /// unsure of it or thought the window held no speech, like an
    /// ambiguous phrase (see `PromptEchoDetector.isWholePrompt`); said
    /// with confidence it stays. With other speech in the same pass it
    /// stays too: the name was most likely said, as part of the line.
    public func accepted(from segments: [WhisperSegmentSummary], echo: PromptEchoDetector? = nil) -> [WhisperSegmentSummary] {
        let candidates = segments.filter { segment in
            accepts(segment) && echo?.isEcho(Self.stripSpecialTokens(segment.text)) != true
        }
        guard let echo, candidates.allSatisfy({ echo.isWholePrompt(Self.stripSpecialTokens($0.text)) }) else { return candidates }
        return candidates.filter { segment in
            segment.noSpeechProb <= ambiguousNoSpeechThreshold && segment.avgLogprob >= ambiguousLogprobThreshold
        }
    }

    /// A decoding loop that stays short enough to pass the compression check
    /// still puts "lavo lavo lavo lavo lavo lavo" ("come come come...") on
    /// screen. Any word or short phrase repeated back to back more than
    /// `maxRepeats` times is cut down to that many: people do say "lo, lo, lo"
    /// ("no, no, no"), but nobody says it six times. The kept copies are the
    /// first ones and the last, so the sentence keeps its closing punctuation.
    ///
    /// Digits and counting words may repeat up to `maxNumberRepeats` times,
    /// because phone numbers, codes and amounts are read out one digit at a
    /// time ("zero five zero five five five five five"), and a number shown
    /// shorter than it was said is wrong with no sign that it is. Every
    /// other word is still cut to `maxRepeats`, and a caller asking for a
    /// single copy gets a single copy of a number as well.
    ///
    /// Text with nothing to collapse comes back exactly as it was.
    public static func collapsingRepeats(
        _ text: String,
        maxRepeats: Int = 3,
        maxPhraseWords: Int = 4,
        maxNumberRepeats: Int = 10
    ) -> String {
        collapse(text, maxRepeats: maxRepeats, maxPhraseWords: maxPhraseWords, maxNumberRepeats: maxNumberRepeats).text
    }

    /// The words of `text` outside every phrase said three or more times
    /// back to back, once each phrase is cut to one copy: "Come on, come on,
    /// come on, come on, let's go!" leaves 2 ("let's go"); nil when no phrase
    /// is said that often.
    static func wordsBesideEmphasis(_ text: String) -> Int? {
        let result = collapse(text, maxRepeats: 1, maxPhraseWords: 4, maxNumberRepeats: 10, emphasisFrom: 3)
        return result.emphasized > 0 ? result.words - result.emphasized : nil
    }

    private static func collapse(
        _ text: String,
        maxRepeats: Int,
        maxPhraseWords: Int,
        maxNumberRepeats: Int,
        emphasisFrom: Int = Int.max
    ) -> (text: String, words: Int, emphasized: Int) {
        var words = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        var changed = false
        if maxRepeats >= 1, emphasisFrom == Int.max {
            for index in words.indices {
                if let shortened = collapsingUnspacedRuns(words[index], maxRepeats: maxRepeats, maxNumberRepeats: maxNumberRepeats) {
                    words[index] = shortened
                    changed = true
                }
            }
        }
        var emphasis = [Bool](repeating: false, count: words.count)
        guard maxRepeats >= 1, words.count > maxRepeats else {
            return (changed ? words.joined(separator: " ") : text, words.filter { !normalize($0).isEmpty }.count, 0)
        }
        let numberLimit = maxRepeats > 1 ? max(maxRepeats, maxNumberRepeats) : maxRepeats
        for phraseLength in 1...maxPhraseWords {
            let keys = words.map(normalize)
            var result: [String] = []
            var resultEmphasis: [Bool] = []
            var index = 0
            while index < words.count {
                let phraseEnd = index + phraseLength
                guard phraseEnd <= words.count, !keys[index..<phraseEnd].allSatisfy(\.isEmpty) else {
                    result.append(words[index])
                    resultEmphasis.append(emphasis[index])
                    index += 1
                    continue
                }
                let phrase = keys[index..<phraseEnd]
                var repeats = 1
                while index + (repeats + 1) * phraseLength <= words.count,
                      keys[(index + repeats * phraseLength)..<(index + (repeats + 1) * phraseLength)].elementsEqual(phrase) {
                    repeats += 1
                }
                let limit = repeats > maxRepeats && phrase.allSatisfy(isSpokenNumber) ? numberLimit : maxRepeats
                if repeats > limit {
                    result.append(contentsOf: words[index..<(index + (limit - 1) * phraseLength)])
                    let lastStart = index + (repeats - 1) * phraseLength
                    result.append(contentsOf: words[lastStart..<(lastStart + phraseLength)])
                    resultEmphasis.append(contentsOf: Array(repeating: repeats >= emphasisFrom, count: limit * phraseLength))
                    index += repeats * phraseLength
                    changed = true
                } else {
                    result.append(words[index])
                    resultEmphasis.append(emphasis[index])
                    index += 1
                }
            }
            words = result
            emphasis = resultEmphasis
        }
        let spoken = words.indices.filter { !normalize(words[$0]).isEmpty }
        return (changed ? words.joined(separator: " ") : text, spoken.count, spoken.filter { emphasis[$0] }.count)
    }

    /// A loop in a script written without spaces ("好的好的好的好的好的好的",
    /// "はいはいはいはいはい") has no words to split on, so `collapse` looks inside
    /// each word for a unit of one to eight characters said back to back more
    /// than `maxRepeats` times and cuts it to that many, like the spaced
    /// path. A unit may carry CJK or full-width punctuation ("好的，"), but
    /// needs at least one character of such a script; the last copy may lack
    /// its trailing punctuation ("好的，好的，好的，好的"). A single character
    /// is cut only past six copies, because "谢谢谢谢" and "哈哈哈" are
    /// ordinary speech. Numerals may repeat up to `maxNumberRepeats` times,
    /// like spoken digits. nil when nothing is cut.
    private static func collapsingUnspacedRuns(_ word: String, maxRepeats: Int, maxNumberRepeats: Int) -> String? {
        let scalars = Array(word.unicodeScalars)
        guard scalars.count > maxRepeats, scalars.contains(where: { isRunScript($0.value) }) else { return nil }
        var current = scalars
        var changed = false
        for unit in 1...8 {
            var result: [Unicode.Scalar] = []
            var index = 0
            while index < current.count {
                let end = index + unit
                guard end <= current.count,
                      current[index..<end].allSatisfy({ isRunScript($0.value) || isRunPunctuation($0.value) }),
                      current[index..<end].contains(where: { isRunScript($0.value) }),
                      unit == 1 || Set(current[index..<end]).count > 1 else {
                    result.append(current[index])
                    index += 1
                    continue
                }
                let chunk = Array(current[index..<end])
                var repeats = 1
                while index + (repeats + 1) * unit <= current.count,
                      current[(index + repeats * unit)..<(index + (repeats + 1) * unit)].elementsEqual(chunk) {
                    repeats += 1
                }
                var core = chunk
                while let last = core.last, !isRunScript(last.value) { core.removeLast() }
                let tailStart = index + repeats * unit
                let hasTail = core.count < unit && tailStart + core.count <= current.count
                    && current[tailStart..<(tailStart + core.count)].elementsEqual(core)
                let copies = repeats + (hasTail ? 1 : 0)
                var limit = unit == 1 ? max(maxRepeats, 6) : maxRepeats
                if maxRepeats > 1, chunk.filter({ isRunScript($0.value) }).allSatisfy({ isUnspacedNumeral($0.value) }) {
                    limit = max(limit, maxNumberRepeats)
                }
                if copies > limit {
                    for _ in 0..<(hasTail ? limit - 1 : limit) { result.append(contentsOf: chunk) }
                    if hasTail { result.append(contentsOf: core) }
                    index = tailStart + (hasTail ? core.count : 0)
                    changed = true
                } else {
                    result.append(current[index])
                    index += 1
                }
            }
            current = result
        }
        guard changed else { return nil }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: current)
        return String(view)
    }

    private static func isRunPunctuation(_ value: UInt32) -> Bool {
        switch value {
        case 0x3000...0x303F, 0xFF01...0xFF0F, 0xFF1A...0xFF20, 0xFF5B...0xFF65, 0x2026: return true
        default: return false
        }
    }

    private static func isRunScript(_ value: UInt32) -> Bool {
        switch value {
        case 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x2FA1F, 0x30000...0x3134F,
             0x3041...0x309F, 0x30A0...0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF9F,
             0x1100...0x11FF, 0x3130...0x318F, 0xAC00...0xD7AF,
             0x0E01...0x0EFF, 0x1000...0x109F, 0x1780...0x17FF:
            return true
        default:
            return false
        }
    }

    private static func isUnspacedNumeral(_ value: UInt32) -> Bool {
        "零〇一二三四五六七八九十百千万萬亿億两兩".unicodeScalars.contains { $0.value == value }
    }

    /// Whether a word, already through `normalize`, is a number: written in
    /// digits (in any script; `normalize` turns the hyphen in "5-5" into a
    /// space, so spaces are allowed) or a counting word: Hebrew, with or without prefixes attached, or one
    /// from any language with a word list (this has no language to go by).
    /// "hasheni" ("the second", "the other one") reads as "sheni" with a
    /// prefix, but it is an ordinal: repeated, it is a loop, not a count.
    private static func isSpokenNumber(_ key: String) -> Bool {
        guard !key.isEmpty else { return false }
        if key.allSatisfy({ $0.isNumber || $0 == " " }) { return true }
        if NumberEmphasis.numberReading(of: key) != nil && !NumberEmphasis.otherOneWords.contains(key) { return true }
        return key.split(separator: " ").allSatisfy { NumberWordProfile.isCountingWordInAnyLanguage(String($0)) }
    }

    /// Someone saying a sentence again with no pause, as people do for a
    /// listener who didn't catch it, comes back as the sentence written
    /// twice, and that alone takes the compression ratio past 2.4: a
    /// Hebrew FLEURS sentence of ten words or more did 90% of the time,
    /// and the whole line was dropped (7 of 24 finals and 9 of 24 live
    /// passes, two speakers 0.3 s apart, turbo and large-v3). Two or
    /// three back-to-back copies of one sentence of at least
    /// `minimumWords` words, alike word for word to `minimumSimilarity`,
    /// are that sentence, shown once. A decoding loop runs to more copies
    /// or over fewer words, and stays dropped.
    public static func repeatedSentence(_ text: String, minimumWords: Int = 5, minimumSimilarity: Double = 0.7) -> String? {
        let tokens = text.split(whereSeparator: { $0.isWhitespace }).map(String.init).filter { !normalize($0).isEmpty }
        let keys = tokens.map(normalize)
        var best: (similarity: Double, size: Int)?
        for copies in 2...3 {
            let average = keys.count / copies
            guard average >= minimumWords else { continue }
            for size in max(minimumWords, average - 2)...(average + 2) where size < keys.count {
                guard let similarity = copySimilarity(keys, size: size, copies: copies),
                      similarity >= minimumSimilarity, similarity > (best?.similarity ?? 0) else { continue }
                best = (similarity, size)
            }
        }
        guard let best else { return nil }
        let sentence = tokens[..<best.size].joined(separator: " ")
        // Two copies of two copies is a loop of four, and a "sentence"
        // that is itself a word or short phrase over again is a loop cut
        // in half ("pak pak pak..." on dripping water). One that only
        // doubles a word of its own ("lat lat", slowly; "ken ken", yes)
        // is still a sentence: refusing any doubled word dropped the line.
        let unlooped = collapsingRepeats(sentence, maxRepeats: 1).split(whereSeparator: { $0.isWhitespace })
        guard unlooped.count >= minimumWords,
              repeatedSentence(sentence, minimumWords: minimumWords, minimumSimilarity: minimumSimilarity) == nil
        else { return nil }
        return sentence
    }

    /// How alike the least alike later copy is to the first `size` words,
    /// each copy free to run a few words longer or shorter; nil when more
    /// than a word is left over after the last one.
    static func copySimilarity(_ keys: [String], size: Int, copies: Int) -> Double? {
        let first = Array(keys[..<size])
        var start = size
        var least = 1.0
        for _ in 1..<copies {
            var best: (similarity: Double, length: Int)?
            for length in max(1, size - 3)...(size + 3) where start + length <= keys.count {
                let similarity = wordSimilarity(first, Array(keys[start..<(start + length)]))
                if similarity > (best?.similarity ?? -1) { best = (similarity, length) }
            }
            guard let best else { return nil }
            least = min(least, best.similarity)
            start += best.length
        }
        return keys.count - start <= 1 ? least : nil
    }

    /// Twice the longest run of words the two share in order, over both lengths.
    static func wordSimilarity(_ a: [String], _ b: [String]) -> Double {
        guard !a.isEmpty || !b.isEmpty else { return 1 }
        var previous = [Int](repeating: 0, count: b.count + 1)
        for word in a {
            var current = [Int](repeating: 0, count: b.count + 1)
            for (index, other) in b.enumerated() {
                current[index + 1] = word == other ? previous[index] + 1 : max(previous[index + 1], current[index])
            }
            previous = current
        }
        return Double(2 * previous[b.count]) / Double(a.count + b.count)
    }

    public func accepts(_ segment: WhisperSegmentSummary) -> Bool {
        let text = withoutNotes(Self.stripSpecialTokens(segment.text).trimmingCharacters(in: .whitespacesAndNewlines))
        if text.isEmpty { return false }
        // A lone "...", "-" or "♪" is a well-known Whisper hallucination on a
        // quiet or noisy window that doesn't trip the noSpeech/logprob
        // thresholds together; normalize() already strips exactly
        // punctuation and symbols, so an empty result means no real word
        // survived.
        if Self.normalize(text).isEmpty { return false }
        // "toda raba toda raba": a decoding loop over a known phrase is
        // judged as the phrase itself.
        let once = Self.collapsingRepeats(text, maxRepeats: 1)
        if isKnownHallucination(text) || isKnownHallucination(once) { return false }
        if ambiguousHallucinations.contains(Self.normalize(once)),
           Self.isBracketed(text) || segment.noSpeechProb > ambiguousNoSpeechThreshold || segment.avgLogprob < ambiguousLogprobThreshold {
            return false
        }
        // The reference implementation only treats "no speech" as decisive
        // when the model was *also* unsure of its tokens; a confident
        // transcript in a window the VAD thought was quiet is kept.
        if segment.noSpeechProb > noSpeechThreshold && segment.avgLogprob < logprobThreshold {
            return false
        }
        // A sentence that merely opens like a credit ("Subtitles by default
        // are off") is kept when the model was sure of it; an unsure one is
        // the made-up credit it so often writes on quiet.
        if segment.noSpeechProb > ambiguousNoSpeechThreshold || segment.avgLogprob < ambiguousLogprobThreshold,
           [{ text }, { once }, { Self.repeatedSentence(text) ?? text }].contains(where: {
               let line = $0()
               return isCreditLine(raw: line, normalized: Self.normalize(line), anyRemainder: true)
           }) {
            return false
        }
        if segment.compressionRatio > compressionRatioThreshold {
            // Two or three copies of a sentence measured 2.5 to 3.6; the
            // loops Whisper wrote on household noise 11 to 25.
            guard segment.compressionRatio <= 2 * compressionRatioThreshold else { return false }
            // "di di di di!" (enough!) and nothing else: the phone's engine
            // scores repetition over its token numbers, where a word said
            // four or five times on its own already measures 2.5 to 3.3
            // (the computer, over the letters, 1.2 to 1.7), and the pass came
            // back empty. Kept, it is cut to three like any repeat; a longer
            // phrase over again, or "toda toda toda...", is still a loop.
            let word = Self.normalize(once)
            if once != text, word.split(separator: " ").count <= 2, !ambiguousHallucinations.contains(word) { return true }
            // "Come on, come on, come on, come on, let's go!": a phrase said
            // over for emphasis with real words around it, from a model that
            // was sure of it, is shown cut to three. At least two words
            // outside the phrase, or it is a loop.
            if let beside = Self.wordsBesideEmphasis(text), beside >= 2,
               segment.noSpeechProb <= ambiguousNoSpeechThreshold,
               segment.avgLogprob >= ambiguousLogprobThreshold,
               !isKnownHallucination(Self.collapsingRepeats(text)) { return true }
            guard let sentence = Self.repeatedSentence(text), !isKnownHallucination(sentence) else { return false }
        }
        return true
    }

    private nonisolated(unsafe) static let squareNote = /\[[\p{L}\p{M} _-]*\]/
    private nonisolated(unsafe) static let wholeRoundNote = /^[(\u{FF08}][^()\u{FF08}\u{FF09}]*[)\u{FF09}][.!\u{3002}\u{FF01}]*$/
    private nonisolated(unsafe) static let roundNote = /[(\u{FF08}]([^()\u{FF08}\u{FF09}]*)[)\u{FF09}]/

    /// "[Música]", "[音乐]", "(掌声)": Whisper writes a sound as a note in
    /// brackets, in whatever language it hears. Real speech never comes in
    /// square brackets, so any note there goes, alone or inside a line
    /// ("[Music] Hello there" keeps "Hello there"); digits stay, so "[10:30]"
    /// is kept. Round brackets also hold real words ("(I think)", "(HSBC)"),
    /// so a part in them goes only when it is a known sound or outro, or when
    /// the whole line is one bracketed part (a segment that is nothing but
    /// "(...)" is a tag, as in `isBracketed`).
    func withoutNotes(_ text: String) -> String {
        guard text.contains(where: { $0 == "[" || $0 == "(" || $0 == "\u{FF08}" }) else { return text }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).wholeMatch(of: Self.wholeRoundNote) != nil { return "" }
        var cleaned = text.replacing(Self.squareNote, with: "")
        cleaned = cleaned.replacing(Self.roundNote) { match in
            let inner = Self.normalize(String(match.output.1))
            return knownHallucinations.contains(inner) || ambiguousHallucinations.contains(inner) ? "" : String(match.output.0)
        }
        return cleaned.replacing(/\s{2,}/, with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// "[מוזיקה]", "(צחוק)": Whisper's way of labelling a sound. Nobody's
    /// speech comes out in brackets.
    static func isBracketed(_ text: String) -> Bool {
        guard let first = text.first, let last = text.last else { return false }
        return (first == "[" && last == "]") || (first == "(" && last == ")")
    }

    /// Case-, punctuation- and bracket-insensitive lookup, so "[toda raba]",
    /// "toda raba." and "toda raba!" all match one entry.
    public func isKnownHallucination(_ text: String) -> Bool {
        let normalized = Self.normalize(text)
        if knownHallucinations.contains(normalized) { return true }
        return isCreditLine(raw: text, normalized: normalized)
    }

    /// "ktuviot: Yisrael Yisraeli" ("captions: Israel Israeli"), "Subtitles by
    /// XYZ": a short segment that opens with a credit phrase, followed by a
    /// separator (the normalizer already turned ":" into nothing) or a name.
    /// Whole words only, so "ktuviotayim" or "targumim" never match.
    func isCreditLine(raw: String, normalized: String, anyRemainder: Bool = false) -> Bool {
        if isUnspacedCreditLine(normalized) { return true }
        let words = normalized.split(separator: " ")
        guard !words.isEmpty, words.count <= maximumCreditLineWords else { return false }
        let rawWords = raw.split(whereSeparator: \.isWhitespace).map(String.init).filter { !Self.normalize($0).isEmpty }
        let byPhrase = hallucinatedCreditPrefixes.contains { prefix in
            let prefixWords = prefix.split(separator: " ")
            guard words.count >= prefixWords.count, Array(words.prefix(prefixWords.count)) == prefixWords else { return false }
            return anyRemainder || Self.looksLikeCredit(Array(rawWords.dropFirst(prefixWords.count)))
        }
        if byPhrase { return true }

        // Without the stray U+200F and vowel points `normalize` drops for
        // the other checks, but with the colon and dash this one needs.
        let unmarked = HebrewText.stripNiqqud(raw).unicodeScalars.filter { $0.properties.generalCategory != .format }
        let cased = String(String.UnicodeScalarView(unmarked))
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "[](){}<>\"'״-–—")))
        let opening = cased.lowercased()
        return hallucinatedCreditLabels.contains { label in
            guard opening.hasPrefix(label) else { return false }
            let afterLabel = cased.dropFirst(label.count).drop(while: \.isWhitespace)
            if afterLabel.first == ":" {
                return anyRemainder || Self.looksLikeCredit(Self.remainderWords(afterLabel))
            }
            guard let separator = afterLabel.first, Self.creditLabelDashes.contains(separator) else { return false }
            return words.count <= maximumDashCreditLineWords
                && (anyRemainder || Self.looksLikeCredit(Self.remainderWords(afterLabel)))
        }
    }

    private static func remainderWords(_ afterLabel: Substring) -> [String] {
        afterLabel.dropFirst().split(whereSeparator: \.isWhitespace).map(String.init).filter { !normalize($0).isEmpty }
    }

    /// What follows a credit opening decides whether it is a credit or a
    /// person talking about captions ("Subtitles by default are off",
    /// "Subtitles: on or off?"). A credit names someone or something: a
    /// capitalised name, an address ("amara.org", "@name") or "the
    /// community", with nothing but such words in the line. Words in a script without letter case (Hebrew, Arabic,
    /// Chinese, Hindi...) show no such difference, so they count as a name
    /// and a line there is dropped as before, as is a bare year. Nothing after the opening
    /// ("Subtitles by") is a credit too.
    static func looksLikeCredit(_ remainder: [String]) -> Bool {
        let words = remainder.filter { !$0.trimmingCharacters(in: .punctuationCharacters.union(.symbols)).isEmpty }
        if words.isEmpty { return true }
        return words.enumerated().allSatisfy { index, raw in
            let word = raw.trimmingCharacters(in: .punctuationCharacters.union(.symbols))
            let plain = normalize(word)
            if isAddress(raw) || communityWords.contains(plain) { return true }
            if index == 0, leadingArticles.contains(plain) { return true }
            if nameConnectors.contains(plain) { return true }
            guard let letter = word.first(where: \.isLetter) else { return word.contains(where: \.isNumber) }
            return !letter.isLowercase
        }
    }

    private static func isAddress(_ word: String) -> Bool {
        if word.contains("@") || word.lowercased().hasPrefix("www.") || word.lowercased().hasPrefix("http") { return true }
        let chars = Array(word)
        return chars.indices.dropFirst().dropLast().contains { chars[$0] == "." && chars[$0 - 1].isLetter && chars[$0 + 1].isLetter }
    }

    static let nameConnectors: Set<String> = [
        "and", "&", "de", "del", "la", "van", "von", "der", "den", "di", "da", "el", "al", "bin", "y", "e", "du", "des", "le", "ten", "ter",
    ]

    static let leadingArticles: Set<String> = ["the", "la", "le", "les", "el", "los", "las", "die", "der", "das"]

    static let communityWords: Set<String> = [
        "community", "comunidad", "comunidade", "communauté", "gemeinschaft", "сообщество", "спільнота",
    ]

    /// Chinese and Japanese have no spaces, so a credit prefix there is
    /// matched by characters, on a line short enough to be a credit.
    static let maximumUnspacedCreditLineCharacters = 24

    func isUnspacedCreditLine(_ normalized: String) -> Bool {
        let compact = normalized.filter { !$0.isWhitespace }
        guard !compact.isEmpty, compact.count <= Self.maximumUnspacedCreditLineCharacters else { return false }
        return hallucinatedCreditPrefixes.contains { prefix in
            !prefix.contains(" ") && prefix.unicodeScalars.contains(where: Self.isUnspacedScript) && compact.hasPrefix(prefix)
        }
    }

    static func isUnspacedScript(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x3040...0x30FF, 0x3400...0x9FFF, 0xF900...0xFAFF, 0x0E00...0x0E7F: return true
        default: return false
        }
    }

    /// A dash reads as a separator only for the tighter, dash-specific word
    /// cap above -- a colon needs no such caution since real speech almost
    /// never opens with "word:".
    static let creditLabelDashes: Set<Character> = ["-", "\u{2013}", "\u{2014}"]

    /// Removes Whisper's control tokens (`<|startoftranscript|>`,
    /// `<|he|>`, `<|0.00|>` timestamps, ...) that leak into segment text
    /// depending on decoding options. Belt and braces: the engine asks
    /// for them to be skipped, and this makes sure none reach the screen.
    public static func stripSpecialTokens(_ text: String) -> String {
        var result = ""
        var index = text.startIndex
        while index < text.endIndex {
            if text[index...].hasPrefix("<|"), let close = text[index...].range(of: "|>") {
                index = close.upperBound
                continue
            }
            result.append(text[index])
            index = text.index(after: index)
        }
        return result
    }

    static func normalize(_ text: String) -> String {
        // Also strips Unicode format characters (bidi marks, zero-width
        // joiners): WhisperKit's Hebrew/Arabic output regularly carries a
        // stray U+200F alongside otherwise-exact hallucinated text, which
        // survived punctuation/symbol stripping alone and made every
        // known-hallucination and credit-line comparison in this file
        // miss what would otherwise be an exact match.
        //
        // Niqqud (Hebrew vowel points) is stripped the same way
        // `HebrewText.normalize` strips it, via the same
        // `HebrewText.separatingJoiners` + `stripNiqqud` pair. Apple's
        // on-device recognizer and home-server Hebrew models occasionally
        // emit pointed text; without this, a pointed silence hallucination
        // ("תּוֹדָה שֶׁצְּפִיתֶם") would never match `knownHallucinations`,
        // which is stored unpointed, and would reach the screen instead of
        // being dropped.
        let withoutNiqqud = HebrewText.stripNiqqud(HebrewText.separatingJoiners(text))
        let stripped = withoutNiqqud.unicodeScalars.filter { scalar in
            !(0x064B...0x065F).contains(scalar.value) && scalar.value != 0x0670 && scalar.value != 0x0640
                && !CharacterSet.punctuationCharacters.contains(scalar)
                && !CharacterSet.symbols.contains(scalar)
                && scalar.properties.generalCategory != .format
        }
        return String(String.UnicodeScalarView(stripped))
            .lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    /// The words of `normalize(text)`, read from the front and normalized
    /// one at a time, for a caller that may need only the first few: the
    /// line being written comes again at every word, and normalizing all
    /// of it each time made a word cost more the longer the line was.
    ///
    /// `normalize` splits at spaces after it has dropped punctuation and
    /// marks; here the line is split first, at spaces and at the word
    /// joiners `normalize` turns into spaces, and each part is normalized
    /// by itself. That finds the same words as long as no space shares a
    /// character with what is next to it (`isPlainForSplitting`): a
    /// combining mark right after a space is part of the space's
    /// character, which `normalize` drops as a space. At the first letter
    /// that isn't plain, the whole line is normalized after all. The words
    /// handed out before it are already right: such a letter only changes
    /// the word it is in, or the one after a space it follows.
    struct NormalizedWords {
        private let text: String
        private var next: String.UnicodeScalarView.Index
        private var read: [String] = []
        private var isWhole = false

        init(_ text: String) {
            self.text = text
            next = text.unicodeScalars.startIndex
        }

        /// Whether the line has a word at `index`.
        mutating func has(_ index: Int) -> Bool {
            while read.count <= index, !isWhole, readWord() {}
            return index < read.count
        }

        subscript(index: Int) -> String { read[index] }

        private mutating func readWord() -> Bool {
            let scalars = text.unicodeScalars
            while next < scalars.endIndex {
                while next < scalars.endIndex, Self.separates(scalars[next]) { next = scalars.index(after: next) }
                guard next < scalars.endIndex else { return false }
                let start = next
                while next < scalars.endIndex, !Self.separates(scalars[next]) {
                    guard Self.isPlainForSplitting(scalars[next]) else {
                        read = WhisperResultFilter.normalize(text).split(separator: " ").map(String.init)
                        isWhole = true
                        return false
                    }
                    next = scalars.index(after: next)
                }
                let word = WhisperResultFilter.normalize(String(scalars[start..<next]))
                if !word.isEmpty {
                    read.append(word)
                    return true
                }
            }
            return false
        }

        private static func separates(_ scalar: Unicode.Scalar) -> Bool {
            scalar.properties.isWhitespace || HebrewText.wordJoiners.contains(scalar)
        }

        /// A letter that is a character of its own next to a space once
        /// `normalize` has dropped what it drops. Marks it keeps join the
        /// letter (or space) before them; the few letters that join a space
        /// as marks do, or that a space joins, are listed by hand.
        static func isPlainForSplitting(_ scalar: Unicode.Scalar) -> Bool {
            let value = scalar.value
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark:
                return (0x0591...0x05C7).contains(value) || (0x064B...0x065F).contains(value) || value == 0x0670
            default:
                switch value {
                case 0x0E33, 0x0EB3, 0x0D4E, 0xFF9E, 0xFF9F, 0x11000...0x11FFF: return false
                default: return true
                }
            }
        }
    }
}
