import Foundation
import Testing
@testable import OzenKit

@Suite("Speech intake")
struct SpeechIntakeTests {
    private let chunk = 1_600

    private func speech() -> [Float] {
        (0..<chunk).map { 0.05 * sin(Float($0) * 0.3) }
    }

    private func silence() -> [Float] {
        [Float](repeating: 0, count: chunk)
    }

    private func intake(_ pattern: String) -> SpeechIntake {
        let intake = SpeechIntake()
        for mark in pattern {
            intake.append(mark == "s" ? speech() : silence())
        }
        return intake
    }

    @Test("speech is placed from the start of its first chunk to the end of its last; silence alone places nothing")
    func placesSpeech() {
        #expect(intake("...").status() == .init(count: 3 * chunk, firstSpeechStart: nil, lastSpeechEnd: nil, finished: false))
        #expect(intake("..ss.s..").status() == .init(count: 8 * chunk, firstSpeechStart: 2 * chunk, lastSpeechEnd: 6 * chunk, finished: false))
    }

    @Test("dropping silence ahead of speech moves the speech back by as much")
    func dropShiftsSpeech() {
        let intake = intake("...ss.")
        intake.drop(prefix: 2 * chunk)
        #expect(intake.status() == .init(count: 4 * chunk, firstSpeechStart: chunk, lastSpeechEnd: 3 * chunk, finished: false))
        intake.drop(prefix: chunk)
        #expect(intake.status().firstSpeechStart == 0)
    }

    @Test("a finished line dropped up to its end leaves no speech behind, even when it ended exactly there")
    func dropToSpeechEnd() {
        let exact = intake(".ss..")
        exact.drop(prefix: 3 * chunk)
        #expect(exact.status() == .init(count: 2 * chunk, firstSpeechStart: nil, lastSpeechEnd: nil, finished: false))
        let padded = intake(".ss..")
        padded.drop(prefix: 3 * chunk + 480)
        #expect(padded.status().lastSpeechEnd == nil)
        #expect(padded.status().count == 2 * chunk - 480)
    }

    @Test("a cut inside a long line keeps the speech after it, starting at the next whole chunk of speech")
    func dropInsideSpeech() {
        let intake = intake("ssss.")
        intake.drop(prefix: 2 * chunk + 100)
        #expect(intake.status() == .init(count: 3 * chunk - 100, firstSpeechStart: chunk - 100, lastSpeechEnd: 2 * chunk - 100, finished: false))
        intake.drop(prefix: chunk)
        #expect(intake.status().firstSpeechStart == nil)
        #expect(intake.status().lastSpeechEnd == chunk - 100)
    }

    @Test("dropping or copying past either end is clamped, and the samples copied are the ones kept")
    func clamps() {
        let intake = intake("s.")
        #expect(intake.copySamples(upTo: -5).isEmpty)
        #expect(intake.copySamples(upTo: 10 * chunk).count == 2 * chunk)
        intake.drop(prefix: -5)
        #expect(intake.status().count == 2 * chunk)
        intake.drop(prefix: chunk)
        #expect(intake.copySamples(upTo: chunk) == silence())
        intake.drop(prefix: 10 * chunk)
        #expect(intake.status() == .init(count: 0, firstSpeechStart: nil, lastSpeechEnd: nil, finished: false))
    }

    @Test("the end of the audio is reported once marked, and new audio still counts after it")
    func finished() {
        let intake = intake("s")
        intake.markFinished()
        intake.append(silence())
        #expect(intake.status() == .init(count: 2 * chunk, firstSpeechStart: 0, lastSpeechEnd: chunk, finished: true))
    }

    private let pause = 7

    /// Loud enough to stay speech however long it goes on: the quieter
    /// chunk above becomes the noise floor after a few seconds of it.
    private func loud() -> [Float] {
        (0..<chunk).map { 0.3 * sin(Float($0) * 0.3) }
    }

    private func feed(_ intake: SpeechIntake, _ pattern: some StringProtocol) {
        for mark in pattern {
            intake.append(mark == "s" ? loud() : silence())
        }
    }

    @Test("a pause that more speech followed is remembered where the speech stopped; a shorter gap, or silence before any speech, is not")
    func remembersPauses() {
        let intake = SpeechIntake(pauseSamples: pause * chunk)
        feed(intake, ".........ss......ss.......ss")
        #expect(intake.status().pauses == [19 * chunk])
        #expect(intake.status().lastSpeechEnd == 28 * chunk)
        let unasked = SpeechIntake()
        feed(unasked, "ss.......ss")
        #expect(unasked.status().pauses.isEmpty)
    }

    @Test("dropping a line ended at a pause forgets that pause and moves the later ones back")
    func dropForgetsPausesBehindIt() {
        let intake = SpeechIntake(pauseSamples: pause * chunk)
        feed(intake, "ss.......ss.......ss")
        #expect(intake.status().pauses == [2 * chunk, 11 * chunk])
        intake.drop(prefix: 5 * chunk)
        #expect(intake.status().pauses == [6 * chunk])
        #expect(intake.status().firstSpeechStart == 4 * chunk)
        intake.drop(prefix: 6 * chunk)
        #expect(intake.status().pauses.isEmpty)
    }

    @Test("the voice model's verdicts follow the audio through a drop, and are unknown without one")
    func voiceEvidence() {
        let loud: VoiceEvidence.Scorer = { samples in
            samples.suffix(VoiceEvidence.chunkSamples).contains { abs($0) > 0.01 } ? 0.9 : 0.1
        }
        let intake = SpeechIntake(voiceScorer: loud)
        let block = VoiceEvidence.chunkSamples
        intake.append([Float](repeating: 0.05, count: 2 * block))
        intake.append([Float](repeating: 0, count: 2 * block))
        #expect(intake.hasVoice(upTo: block) == true)
        #expect(intake.voicedChunks(upTo: 4 * block) == 2)
        intake.drop(prefix: 2 * block)
        #expect(intake.hasVoice(upTo: 2 * block) == false)
        #expect(SpeechIntake().hasVoice(upTo: block) == nil)
    }

    /// Where each line ends, in chunks from the start, when every pass
    /// (live or final) takes `passChunks` of audio to run: the intake and
    /// the cut as the on-device engine uses them, one decision per pass.
    private func lines(_ pattern: String, passChunks: Int) -> [Int] {
        let intake = SpeechIntake(pauseSamples: pause * chunk)
        let marks = Array(pattern)
        var fed = 0
        var dropped = 0
        var ends: [Int] = []
        while true {
            let next = min(fed + passChunks, marks.count)
            feed(intake, String(marks[fed..<next]))
            fed = next
            if fed == marks.count { intake.markFinished() }
            let status = intake.status()
            guard let speechEnd = status.lastSpeechEnd else {
                if status.finished { return ends }
                continue
            }
            let atPause = UtteranceCut.lineEndAtPause(
                pauses: status.pauses, speechEnd: speechEnd, total: status.count,
                pause: pause * chunk, pad: 3 * chunk, maxSamples: 280 * chunk, mostWaiting: 2
            )
            guard atPause != nil || status.finished else { continue }
            let line = UtteranceCut.finishedLine(
                total: status.count, speechEnd: atPause ?? speechEnd, pad: 3 * chunk,
                maxSamples: 280 * chunk, stillTalkingAtCap: false
            )
            intake.drop(prefix: line.end)
            dropped += line.end
            ends.append(dropped / chunk)
        }
    }

    @Test("three sentences said while two-second passes run come out as three lines, each ending at its own pause")
    func threeSentencesInSlowPasses() {
        let pattern = "......" + "ssssssssss" + ".........." + "ssssssssss" + ".........." + "ssssssssss" + "................"
        #expect(lines(pattern, passChunks: 20) == [19, 39, 59])
    }

    @Test("more than two finished sentences waiting for a four-second pass share one line, so a slow phone stops falling behind")
    func backlogSharesALine() {
        let sentence = "ssssssssss" + "........"
        let pattern = "......" + String(repeating: sentence, count: 5) + ".........."
        #expect(lines(pattern, passChunks: 40) == [19, 73, 91])
    }
}
