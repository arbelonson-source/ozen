import Testing
@testable import OzenKit

@Suite("Where a line that ran too long is cut")
struct UtteranceCutTests {
    /// Loud alternating samples, with silence over `gap`.
    private func speech(count: Int, gap: Range<Int>? = nil) -> [Float] {
        (0..<count).map { index in
            if let gap, gap.contains(index) { return 0 }
            return index % 2 == 0 ? 0.5 : -0.5
        }
    }

    @Test("the cut goes into the quiet between words, not wherever the counter stopped")
    func findsTheGap() {
        let samples = speech(count: 16_000, gap: 12_000..<12_800)
        let cut = UtteranceCut.quietestPoint(in: samples, before: 16_000, lookBack: 8_000, frame: 400)
        // The middle of the last frame that is all silence.
        #expect(cut == 12_600)
    }

    @Test("a gap older than the look-back is not reached for")
    func staysInTheLookBack() {
        let samples = speech(count: 16_000, gap: 1_000..<1_800)
        let cut = UtteranceCut.quietestPoint(in: samples, before: 16_000, lookBack: 4_000, frame: 400)
        #expect(cut >= 12_000)
    }

    @Test("with nothing quieter anywhere, the latest point wins so little carries over")
    func evenSpeech() {
        let samples = speech(count: 16_000)
        let cut = UtteranceCut.quietestPoint(in: samples, before: 16_000, lookBack: 4_000, frame: 400)
        #expect(cut >= 15_000)
        #expect(cut <= 16_000)
    }

    @Test("too little audio, or nonsense arguments, leave the cut where it was asked for")
    func edges() {
        #expect(UtteranceCut.quietestPoint(in: speech(count: 100), before: 100, lookBack: 4_000, frame: 400) == 100)
        #expect(UtteranceCut.quietestPoint(in: [], before: 50, lookBack: 10, frame: 4) == 0)
        #expect(UtteranceCut.quietestPoint(in: speech(count: 1_000), before: 5_000, lookBack: 1_000, frame: 0) == 1_000)
        #expect(UtteranceCut.quietestPoint(in: speech(count: 1_000), before: -3, lookBack: 1_000, frame: 100) == 0)
    }

    @Test("a finished line reaches no further than the cap, whether someone was still talking, paused or stopped")
    func finishedLineStaysWithinTheCap() {
        #expect(UtteranceCut.finishedLine(total: 100, speechEnd: 60, pad: 5, maxSamples: 280, stillTalkingAtCap: false) == (65, false))
        #expect(UtteranceCut.finishedLine(total: 62, speechEnd: 60, pad: 5, maxSamples: 280, stillTalkingAtCap: false) == (62, false))
        #expect(UtteranceCut.finishedLine(total: 290, speechEnd: 289, pad: 5, maxSamples: 280, stillTalkingAtCap: true) == (280, true))
        #expect(UtteranceCut.finishedLine(total: 270, speechEnd: 260, pad: 5, maxSamples: 280, stillTalkingAtCap: true) == (265, true))
        #expect(UtteranceCut.finishedLine(total: 400, speechEnd: 390, pad: 5, maxSamples: 280, stillTalkingAtCap: false) == (280, true))
    }

    private func lineEnd(_ pauses: [Int], speechEnd: Int, total: Int, maxSamples: Int = 280, finished: Bool = false) -> Int? {
        UtteranceCut.lineEndAtPause(pauses: pauses, speechEnd: speechEnd, total: total, pause: 7, pad: 3, maxSamples: maxSamples, mostWaiting: 2, finished: finished)
    }

    @Test("a line ends at the first pause heard, even one a slow pass hid while the next sentence began")
    func endsAtTheFirstPause() {
        #expect(lineEnd([], speechEnd: 60, total: 66) == nil)
        #expect(lineEnd([], speechEnd: 60, total: 67) == 60)
        #expect(lineEnd([40], speechEnd: 60, total: 62) == 40)
        #expect(lineEnd([40], speechEnd: 60, total: 70) == 40)
        #expect(lineEnd([20, 40], speechEnd: 60, total: 62) == 20)
    }

    @Test("more than two finished sentences waiting share one line, as far as the longest line reaches")
    func backlogSharesALine() {
        #expect(lineEnd([20, 40], speechEnd: 60, total: 70) == 60)
        #expect(lineEnd([20, 40, 50], speechEnd: 60, total: 62) == 50)
        #expect(lineEnd([20, 40, 50], speechEnd: 60, total: 62, maxSamples: 45) == 40)
        #expect(lineEnd([20, 40, 50], speechEnd: 60, total: 62, maxSamples: 22) == 20)
    }

    @Test("when the captions stop before the last pause, the last sentence counts as waiting too")
    func stoppedCountsTheLastSentence() {
        #expect(lineEnd([], speechEnd: 60, total: 62, finished: true) == 60)
        #expect(lineEnd([40], speechEnd: 60, total: 62, finished: true) == 40)
        #expect(lineEnd([20, 40], speechEnd: 60, total: 62, finished: true) == 60)
        #expect(lineEnd([20, 40], speechEnd: 60, total: 62, maxSamples: 45, finished: true) == 40)
    }
}
