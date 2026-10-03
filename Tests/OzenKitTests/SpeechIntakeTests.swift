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
}
