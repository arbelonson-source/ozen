import Testing
@testable import OzenKit

private struct PassTextRandom: RandomNumberGenerator {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

@Suite("A pass's text, checked once")
struct WhisperPassTextTests {
    private let texts = [
        "מה שלומך היום", " שלום, מה נשמע?", "דני, בוא לאכול", "בואי לאכול.", "תבואי תבואי תבואי מחר",
        "תודה רבה", "תודה רבה.", " תודה", "תודה רבה תודה רבה תודה רבה", "תודה שצפיתם.", "[תודה על הצפייה]",
        "Thank you.", "you", "Music", "Thank you for watching", "Subtitles by the Amara.org community",
        "כתוביות: ישראל ישראלי", "תרגום: מיכל", "תרגום - מיכל", "Subtitles by default are off",
        "...", "-", "♪", "?!", "", "   ", "[מוזיקה]", "(צוחק) כן, בטח", "(מוזיקה)", "<|0.00|> מה נשמע <|1.20|>",
        "לא לא לא לא לא לא לא", "די די די די!", "Come on, come on, come on, come on, let's go!",
        "אחת שתיים שלוש אחת שתיים שלוש אחת שתיים שלוש אחת שתיים שלוש", "0 5 4 3 2 1 0 9 8 7 6 5",
        "אני הולך הביתה עכשיו. אני הולך הביתה עכשיו. אני הולך הביתה עכשיו.",
        "אבי, רותי, דני.", "אבי, רותי, דני. אבי, רותי, דני.", "סבתא.", " סבתא!", "סבתא, בואי.",
        "I think we should go now.", "Bonjour, comment ça va ?", "Merci.", "Hola, ¿cómo estás?", "Gracias.",
        "Olá, tudo bem?", "Obrigado.", "مرحبا كيف حالك", "شكرا", "Привет, как дела?", "Спасибо.",
        "今日はいい天気ですね。", "ご視聴ありがとうございました",
    ]
    private let filters = ["he", "en", "ar", "fr", "es", "pt", "ru", "ja"].map { WhisperResultFilter().forSpeech(in: $0) }
    private let echoes: [PromptEchoDetector?] = [nil, PromptEchoDetector(terms: ["סבתא"]), PromptEchoDetector(terms: ["אבי", "רותי", "דני"])]
    private let noSpeech: [Float] = [0, 0.2, 0.3, 0.7]
    private let logprobs: [Float] = [-0.05, -0.5, -0.95, -1.2, -.infinity]
    private let ratios: [Float] = [0.6, 1.2, 2.6, 3.5, 5, .infinity]
    private let voiced: [Int?] = [nil, 0, 1, 2, 5]

    private func segment(_ random: inout PassTextRandom) -> WhisperSegmentSummary {
        WhisperSegmentSummary(
            text: texts.randomElement(using: &random)!,
            noSpeechProb: noSpeech.randomElement(using: &random)!,
            avgLogprob: logprobs.randomElement(using: &random)!,
            compressionRatio: ratios.randomElement(using: &random)!,
            uncertainWords: Bool.random(using: &random) ? [] : ["מחר"],
            temperature: Int.random(in: 0..<4, using: &random) == 0 ? 0.2 : 0
        )
    }

    @Test("a pass shows exactly what checking its text and its segments apart showed")
    func passTextMatchesTheSeparateChecks() {
        var random = PassTextRandom(state: 757)
        var shown = 0, unvoiced = 0, partly = 0, echoed = 0
        for index in 0..<2_000 {
            let filter = filters.randomElement(using: &random)!
            let run = Int.random(in: 0..<4, using: &random) == 0
            let one = segment(&random)
            let segments = run
                ? Array(repeating: one, count: Int.random(in: 2..<5, using: &random))
                : (0..<Int.random(in: 0..<6, using: &random)).map { _ in segment(&random) }
            let echo = echoes.randomElement(using: &random)!
            let voicedChunks = voiced.randomElement(using: &random)!

            let dropped = filter.isUnvoicedPhrase(filter.acceptedText(from: segments, echo: echo), voicedChunks: voicedChunks, echo: echo)
            let segmentsBefore = dropped ? [] : filter.accepted(from: segments, echo: echo)
            let textBefore = dropped ? "" : filter.acceptedText(from: segments, echo: echo)

            let pass = filter.passText(from: segments, voicedChunks: voicedChunks, echo: echo)
            let same = pass.text == textBefore && pass.segments == segmentsBefore
                && CaptionConfidence.whisperConfidence(of: pass.segments) == CaptionConfidence.whisperConfidence(of: segmentsBefore)
                && pass.segments.flatMap(\.uncertainWords) == segmentsBefore.flatMap(\.uncertainWords)
            guard same else {
                Issue.record("case \(index): \(segments.map(\.text)) echo \(echo != nil) voiced \(String(describing: voicedChunks)): \"\(textBefore)\" became \"\(pass.text)\"")
                return
            }

            let kept = filter.accepted(from: segments, echo: echo)
            if !textBefore.isEmpty { shown += 1 }
            if dropped && !kept.isEmpty { unvoiced += 1 }
            if !kept.isEmpty && kept.count < segments.count { partly += 1 }
            if echo != nil && kept.count < filter.accepted(from: segments).count { echoed += 1 }
        }
        #expect(shown > 450 && unvoiced > 6 && partly > 350 && echoed > 12, "shown \(shown), unvoiced \(unvoiced), partly \(partly), echoed \(echoed)")
    }
}
