import Testing
@testable import OzenKit
import Foundation

@Suite("CaptionStabilizer")
struct CaptionStabilizerTests {

    @Test("a single final token becomes one committed segment")
    func singleFinalToken() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        let segment = stabilizer.ingest(
            TranscriptToken(utteranceID: id, text: "שלום", isFinal: true, timestamp: 0)
        )
        #expect(segment.text == "שלום")
        #expect(segment.isCommitted)
        #expect(stabilizer.segments.count == 1)
    }

    @Test("repeated partial tokens for the same utterance update in place, not append")
    func partialTokensUpdateInPlace() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "אני", isFinal: false, timestamp: 0))
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "אני רוצה", isFinal: false, timestamp: 0.3))
        let final = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "אני רוצה לשתות", isFinal: true, timestamp: 0.9))

        #expect(stabilizer.segments.count == 1)
        #expect(final.text == "אני רוצה לשתות")
        #expect(final.isCommitted)
    }

    @Test("text is never shortened as an utterance updates — nothing gets cut off mid-flight")
    func textNeverTruncatedWhilePending() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        let first = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "מה שלומך", isFinal: false, timestamp: 0))
        let second = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "מה שלומך היום", isFinal: false, timestamp: 0.4))

        #expect(second.text.count >= first.text.count)
        #expect(!first.isCommitted)
        #expect(!second.isCommitted)
    }

    @Test("commit(id:) finishes a pending segment without touching its text, and does nothing twice")
    func commitByIDLeavesTextAlone() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "תודה", isFinal: false, timestamp: 0))

        let committed = stabilizer.commit(id: id)
        #expect(committed?.text == "תודה")
        #expect(committed?.isCommitted == true)
        #expect(stabilizer.segments.first?.isCommitted == true)

        #expect(stabilizer.commit(id: id) == nil)
        #expect(stabilizer.commit(id: UUID()) == nil)
    }

    @Test("a pending segment commits on its own after a long enough silence")
    func silenceCommitsAStaleSegment() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 1.0)
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "רגע...", isFinal: false, timestamp: 0))

        let notYet = stabilizer.commitStale(now: 0.5)
        #expect(notYet.isEmpty)
        #expect(!stabilizer.segments[0].isCommitted)

        let nowCommitted = stabilizer.commitStale(now: 1.2)
        #expect(nowCommitted.count == 1)
        #expect(stabilizer.segments[0].isCommitted)
    }

    @Test("after the engine's final, a straggler for the same line changes neither its words nor its state, only who said it")
    func engineFinalIsFinal() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "בסדר", isFinal: true, timestamp: 0, confidence: 0.9))
        let straggler = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "בסדר גמור", isFinal: false, timestamp: 0.1, speakerClusterID: 2, confidence: 0.1))

        #expect(straggler.isCommitted)
        #expect(straggler.text == "בסדר")
        #expect(straggler.confidence == 0.9)
        #expect(straggler.speakerClusterID == 2)
    }

    @Test("a line committed only because the engine went quiet reopens when the engine turns out to be slow, then settles on its final")
    func safetyNetCommitReopens() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 6)
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "הרופא אמר", isFinal: false, timestamp: 0))
        #expect(stabilizer.commitStale(now: 7).map(\.id) == [id])
        #expect(stabilizer.segments.first?.isCommitted == true)
        #expect(stabilizer.segments.first?.isSettled == false)

        let reopened = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "הרופא אמר כדור", isFinal: false, timestamp: 8))
        #expect(reopened.isCommitted == false)
        #expect(reopened.text == "הרופא אמר כדור")

        let settled = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "הרופא אמר כדור אחד", isFinal: true, timestamp: 9))
        #expect(settled.isCommitted)
        #expect(settled.isSettled)
        #expect(settled.text == "הרופא אמר כדור אחד")

        // Now it's the engine's final: nothing more changes the words.
        let late = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "משהו אחר", isFinal: true, timestamp: 10))
        #expect(late.text == "הרופא אמר כדור אחד")
    }

    @Test("lines finished because listening stopped stay as they are")
    func commitAllIsFinal() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 6)
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "עד כאן", isFinal: false, timestamp: 0))
        _ = stabilizer.commitStale(now: 7)
        _ = stabilizer.commitAll()
        let late = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "עד כאן ועוד", isFinal: false, timestamp: 8))
        #expect(late.isCommitted)
        #expect(late.text == "עד כאן")
    }

    @Test("two different utterances are tracked as two independent segments")
    func independentUtterancesStayIndependent() {
        var stabilizer = CaptionStabilizer()
        let first = UUID()
        let second = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: first, text: "היי", isFinal: true, timestamp: 0, speakerClusterID: 0))
        stabilizer.ingest(TranscriptToken(utteranceID: second, text: "מה קורה", isFinal: true, timestamp: 1, speakerClusterID: 1))

        #expect(stabilizer.segments.count == 2)
        #expect(stabilizer.segments[0].speakerClusterID == 0)
        #expect(stabilizer.segments[1].speakerClusterID == 1)
    }

    @Test("commitStale never touches segments that already committed")
    func commitStaleIgnoresAlreadyCommitted() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 1.0)
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "כבר גמרתי", isFinal: true, timestamp: 0))

        let result = stabilizer.commitStale(now: 100)
        #expect(result.isEmpty)
    }

    @Test("an empty update never erases text already shown; an empty final still commits it")
    func emptyUpdateKeepsText() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "שלום", isFinal: false, timestamp: 1))
        let afterEmpty = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "", isFinal: false, timestamp: 2))
        #expect(afterEmpty.text == "שלום")
        #expect(afterEmpty.lastUpdateTimestamp == 2)

        let final = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "  ", isFinal: true, timestamp: 3))
        #expect(final.text == "שלום")
        #expect(final.isCommitted)
    }
}

@Suite("Caption confidence")
struct CaptionConfidenceTests {
    private func token(_ id: UUID, _ text: String, final: Bool, confidence: Float?) -> TranscriptToken {
        TranscriptToken(utteranceID: id, text: text, isFinal: final, timestamp: 1, confidence: confidence)
    }

    @Test("a line keeps the engine's latest confidence, and an update without one doesn't erase it")
    func carriedThrough() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(token(id, "שלו", final: false, confidence: 0.2))
        stabilizer.ingest(token(id, "שלום", final: false, confidence: nil))
        #expect(stabilizer.segments.first?.confidence == 0.2)
        stabilizer.ingest(token(id, "שלום לכם", final: true, confidence: 0.9))
        #expect(stabilizer.segments.first?.confidence == 0.9)
    }

    @Test("only finished lines with a real low score are marked unsure")
    func uncertaintyRule() {
        let line = "נפגשים מחר בבוקר אצל הרופא"
        for engine in TranscriptionEngineKind.allCases {
            #expect(CaptionConfidence.isUncertain(confidence: 0.3, isCommitted: true, text: line, engine: engine))
            #expect(CaptionConfidence.isUncertain(confidence: 0.3, isCommitted: false, text: line, engine: engine) == false)
            #expect(CaptionConfidence.isUncertain(confidence: 0.97, isCommitted: true, text: line, engine: engine) == false)
            #expect(CaptionConfidence.isUncertain(confidence: 0, isCommitted: true, text: line, engine: engine) == false)
            #expect(CaptionConfidence.isUncertain(confidence: nil, isCommitted: true, text: line, engine: engine) == false)
        }
        #expect(CaptionConfidence.isUncertain(confidence: 0.4, isCommitted: true, text: line, engine: .appleSpeech) == false)
    }

    @Test("Whisper's misheard lines get the mark: they score far above Apple's cutoff")
    func whisperScaleIsItsOwn() {
        // Lines ivrit.ai's models got wrong, on the phone (Turbo) and on the
        // home computer (large), with their scores: e^(mean log-probability).
        let misheard: [(String, Float)] = [
            ("היי, טוב לי להיות עודכם שוב.", 0.689),
            ("חברת החמישית, כמה היו?", 0.746),
            ("חברת החמישית, כמה היו?", 0.736),
            ("ועוד חמישית כמה היו?", 0.679),
        ]
        for (text, score) in misheard {
            #expect(CaptionConfidence.isUncertain(confidence: score, isCommitted: true, text: text, engine: .whisperKit))
            #expect(CaptionConfidence.isUncertain(confidence: score, isCommitted: true, text: text, engine: .homeServer))
            #expect(CaptionConfidence.isUncertain(confidence: score, isCommitted: true, text: text, engine: .appleSpeech) == false)
        }
        // Their median line, nearly always right, stays unmarked.
        let line = "כי למידה מורכבת מביצוע של רוטינות"
        #expect(CaptionConfidence.isUncertain(confidence: 0.97, isCommitted: true, text: line, engine: .homeServer) == false)
        #expect(CaptionConfidence.isUncertain(confidence: 0.9, isCommitted: true, text: line, engine: .whisperKit) == false)
    }

    @Test("a short answer needs a lower Whisper score to be marked: one doubtful token weighs more among a few")
    func shortLinesNeedALowerScore() {
        // Short lines from broadcast speech that the large model got right,
        // and ones it got wrong, with their scores.
        let right: [(String, Float)] = [("כן, למה לא?", 0.737), ("שתיים", 0.741), ("סבבה, יאללה", 0.698), ("יאללה! אוקיי", 0.702), ("אפשר ביס?", 0.683)]
        let wrong: [(String, Float)] = [("יואו!", 0.464), ("הייו!", 0.498), ("ריח אין.", 0.547), ("תגיד מה זה?", 0.583)]
        for engine in [TranscriptionEngineKind.whisperKit, .homeServer] {
            for (text, score) in right {
                #expect(CaptionConfidence.isUncertain(confidence: score, isCommitted: true, text: text, engine: engine) == false)
            }
            for (text, score) in wrong {
                #expect(CaptionConfidence.isUncertain(confidence: score, isCommitted: true, text: text, engine: engine))
            }
        }
        // Apple's cutoff is the same for any length.
        #expect(CaptionConfidence.isUncertain(confidence: 0.39, isCommitted: true, text: "שתיים", engine: .appleSpeech))
        #expect(CaptionConfidence.isUncertain(confidence: 0.45, isCommitted: true, text: "שתיים", engine: .appleSpeech) == false)
    }

    @Test("Ozen's noise-trained model is surer of itself, so its lines are marked a little higher up; other models and engines keep theirs")
    func noiseTrainedModelHasItsOwnCutoffs() {
        let a3 = "ozen-turbo-hebrew-a3-8bit"
        let line = "נפגשים מחר בבוקר אצל הרופא"
        #expect(CaptionConfidence.isUncertain(confidence: 0.82, isCommitted: true, text: line, engine: .whisperKit, model: a3))
        #expect(CaptionConfidence.isUncertain(confidence: 0.84, isCommitted: true, text: line, engine: .whisperKit, model: a3) == false)
        #expect(CaptionConfidence.isUncertain(confidence: 0.62, isCommitted: true, text: "ריח אין.", engine: .whisperKit, model: a3))
        #expect(CaptionConfidence.isUncertain(confidence: 0.82, isCommitted: true, text: line, engine: .whisperKit, model: "ivrit-large-v3-turbo-8bit") == false)
        #expect(CaptionConfidence.isUncertain(confidence: 0.62, isCommitted: true, text: "ריח אין.", engine: .whisperKit) == false)
        // The right short answers of the test above still go unmarked.
        #expect(CaptionConfidence.isUncertain(confidence: 0.683, isCommitted: true, text: "אפשר ביס?", engine: .whisperKit, model: a3) == false)
        // The home computer runs its own models, whichever one the phone has.
        #expect(CaptionConfidence.isUncertain(confidence: 0.82, isCommitted: true, text: line, engine: .homeServer, model: a3) == false)
    }

    @Test("the phone's score for a line is averaged as the cutoffs were measured: over its words' tokens and the end, not the four that open every line")
    func phoneScoreOnTheMeasuredScale() throws {
        // "hey oho" for "ah ho", a broadcast line Turbo got wrong: five
        // tokens, 0.43 on the home computer's scale.
        let tokens: [Float] = [-1.0, -1.2, -0.9, -1.1, -0.86]
        let average = try #require(WhisperSegmentSummary.averageLogprob(wordTokenLogprobs: tokens))
        #expect(abs(exp(average) - 0.43) < 0.005)
        #expect(CaptionConfidence.isUncertain(confidence: exp(average), isCommitted: true, text: "היי אוהו", engine: .whisperKit))
        // WhisperKit's own average also counts the line's start, language,
        // task and no-timestamps tokens and its end at 0: 0.60, unmarked.
        let whisperKits = exp(tokens.reduce(0, +) / Float(tokens.count + 5))
        #expect(CaptionConfidence.isUncertain(confidence: whisperKits, isCommitted: true, text: "היי אוהו", engine: .whisperKit) == false)
        #expect(WhisperSegmentSummary.averageLogprob(wordTokenLogprobs: []) == nil)
    }

    @Test("a line the phone's engine had to decode again at a raised temperature is shown as unsure, however sure its retry reads")
    func retriedLineIsUnsure() throws {
        let words = "פגשתי מהרופא שתביא לי את הטלפון"
        let plain = WhisperSegmentSummary(text: words, noSpeechProb: 0, avgLogprob: log(0.95), compressionRatio: 1.2)
        var retried = plain
        retried.temperature = 0.2
        let sure = try #require(CaptionConfidence.whisperConfidence(of: [plain]))
        #expect(abs(sure - 0.95) < 0.001)
        #expect(CaptionConfidence.isUncertain(confidence: sure, isCommitted: true, text: words, engine: .whisperKit) == false)
        let doubtful = try #require(CaptionConfidence.whisperConfidence(of: [plain, retried]))
        #expect(CaptionConfidence.isUncertain(confidence: doubtful, isCommitted: true, text: words, engine: .whisperKit))
        #expect(CaptionConfidence.isUncertain(confidence: doubtful, isCommitted: true, text: "כן", engine: .whisperKit))
        #expect(CaptionConfidence.whisperConfidence(of: []) == nil)
    }

    @Test("confidence is saved with the line, and older saved lines have none")
    func savedWithHistory() throws {
        let live = TranscriptSegment(id: UUID(), text: "אולי", isCommitted: true, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 0, confidence: 0.25)
        let record = TranscriptSessionRecord.make(from: [live], speakerName: { _ in nil }, id: UUID(), startedAt: 0, endedAt: nil, engine: .whisperKit, modelVariant: nil, inputName: nil)
        let decoded = try JSONDecoder().decode(TranscriptSessionRecord.self, from: JSONEncoder().encode(record))
        #expect(decoded.segments.first?.confidence == 0.25)

        let old = #"{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","text":"ישן","startTimestamp":1,"isCommitted":true}"#
        #expect(try JSONDecoder().decode(SavedSegment.self, from: Data(old.utf8)).confidence == nil)
    }

    @Test("marking unsure lines is on by default and survives older settings files")
    func settingDefault() throws {
        #expect(DisplayPreferences.default.markUncertainLines)
        let old = try JSONDecoder().decode(DisplayPreferences.self, from: Data(#"{"fontSize":30}"#.utf8))
        #expect(old.markUncertainLines)
    }
}

@Suite("CaptionStabilizer finishing every open line")
struct CaptionStabilizerCommitAllTests {
    @Test("stopping settles lines committed only by a pause, as written, and returns them for the screen")
    func commitAllSettlesProvisionalLines() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 6)
        let quiet = UUID()
        let reopened = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: quiet, text: "התקשרי ל-050", isFinal: false, timestamp: 0))
        stabilizer.ingest(TranscriptToken(utteranceID: reopened, text: "עוד", isFinal: false, timestamp: 0))
        _ = stabilizer.commitStale(now: 7)
        stabilizer.ingest(TranscriptToken(utteranceID: reopened, text: "עוד משהו", isFinal: false, timestamp: 8))

        let finished = stabilizer.commitAll()
        #expect(Set(finished.map(\.id)) == [quiet, reopened])
        let allSettled = stabilizer.segments.allSatisfy(\.isSettled)
        #expect(allSettled)
        #expect(stabilizer.segments.first?.text == "התקשרי ל-050")
    }

    @Test("a final whose words were suppressed settles a line committed only by a pause")
    func commitByIDSettlesProvisionalLine() {
        var stabilizer = CaptionStabilizer(silenceCommitThreshold: 6)
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "תודה", isFinal: false, timestamp: 0))
        _ = stabilizer.commitStale(now: 7)

        #expect(stabilizer.commit(id: id)?.isSettled == true)
        #expect(stabilizer.commit(id: id) == nil)
        let late = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "תודה רבה", isFinal: false, timestamp: 8))
        #expect(late.isSettled)
        #expect(late.text == "תודה")
    }

    @Test("commitAll finishes open lines only, and returns just those")
    func commitAll() {
        var stabilizer = CaptionStabilizer()
        let open = UUID()
        let done = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: done, text: "שלום", isFinal: true, timestamp: 1))
        stabilizer.ingest(TranscriptToken(utteranceID: open, text: "מה נש", isFinal: false, timestamp: 2))

        let finished = stabilizer.commitAll()
        #expect(finished.map(\.id) == [open])
        let allFinished = stabilizer.segments.allSatisfy { $0.isCommitted }
        #expect(allFinished)
        #expect(stabilizer.commitAll().isEmpty)
        #expect(stabilizer.segments.map(\.text) == ["שלום", "מה נש" + CaptionStabilizer.cutOffMark])
    }

    @Test("a line that already trails off in three dots is not marked cut off a second time")
    func cutOffMarkNotDoubled() {
        #expect(CaptionStabilizer.markingCutOff("ואז הוא...") == "ואז הוא...")
        #expect(CaptionStabilizer.markingCutOff("ואז הוא" + CaptionStabilizer.cutOffMark) == "ואז הוא" + CaptionStabilizer.cutOffMark)
        #expect(CaptionStabilizer.markingCutOff("ואז הוא") == "ואז הוא" + CaptionStabilizer.cutOffMark)

        var stabilizer = CaptionStabilizer()
        stabilizer.ingest(TranscriptToken(utteranceID: UUID(), text: "ואז הוא...", isFinal: false, timestamp: 1))
        _ = stabilizer.commitAll()
        #expect(stabilizer.segments.map(\.text) == ["ואז הוא..."])
    }
}
