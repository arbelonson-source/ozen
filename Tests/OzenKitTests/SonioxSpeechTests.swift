import Foundation
import Testing
@testable import OzenKit

@Suite("Soniox")
struct SonioxSpeechTests {
    static let frames: [String] = {
        let file = DeepgramSpeechTests.fixtures.appendingPathComponent("soniox-two-speakers.jsonl")
        let text = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        return text.split(whereSeparator: \.isNewline).map(String.init)
    }()

    private func settings(_ config: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Data(config.utf8)) as? [String: Any])
    }

    @Test("the connection opens on Soniox's live address with the key in its header, not in a message")
    func connection() {
        #expect(SonioxSpeech.streamURL.absoluteString == "wss://stt-rt.soniox.com/transcribe-websocket")
        #expect(SonioxSpeech.headers(apiKey: "sx-test") == ["Authorization": "Bearer sx-test"])
        #expect(SonioxSpeech.end == "")
    }

    @Test("the settings name the live model, the microphone's own format, the caption language, speakers, line ends and the names list")
    func config() throws {
        let sent = try settings(SonioxSpeech.config(languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"]))
        #expect(sent["model"] as? String == "stt-rt-v5")
        #expect(sent["audio_format"] as? String == "pcm_s16le")
        #expect((sent["sample_rate"] as? NSNumber)?.intValue == 16_000)
        #expect((sent["num_channels"] as? NSNumber)?.intValue == 1)
        #expect(sent["language_hints"] as? [String] == ["he"])
        #expect((sent["enable_speaker_diarization"] as? NSNumber)?.boolValue == true)
        #expect((sent["enable_endpoint_detection"] as? NSNumber)?.boolValue == true)
        #expect((sent["context"] as? [String: Any])?["terms"] as? [String] == ["דנה", "ד\"ר כהן"])
        #expect(sent["api_key"] == nil)

        let plain = try settings(SonioxSpeech.config(languageCode: "fr", vocabulary: []))
        #expect(plain["language_hints"] as? [String] == ["fr"])
        #expect(plain["context"] == nil)
    }

    @Test("a long names list is cut to the first hundred")
    func termsCap() throws {
        let names = (1...150).map { "שם\($0)" }
        let sent = try settings(SonioxSpeech.config(languageCode: "he", vocabulary: names))
        #expect((sent["context"] as? [String: Any])?["terms"] as? [String] == Array(names.prefix(100)))
    }

    @Test("a reply gives its words in order, each final or still changing, with the speaker as text or number")
    func reply() {
        let frame = #"{"tokens":[{"text":"שלום","is_final":true,"speaker":"1","start_ms":0,"end_ms":400},{"text":" לכולם","is_final":false,"speaker":2}],"final_audio_proc_ms":400,"total_audio_proc_ms":900}"#
        #expect(SonioxSpeech.reply(from: frame) == .tokens([
            SonioxSpeech.Token(text: "שלום", isFinal: true, speaker: "1", startMs: 0, endMs: 400),
            SonioxSpeech.Token(text: " לכולם", isFinal: false, speaker: "2", startMs: nil, endMs: nil),
        ], finished: false))
        #expect(SonioxSpeech.reply(from: #"{"tokens":[],"finished":true}"#) == .tokens([], finished: true))
        #expect(SonioxSpeech.reply(from: "<html>") == nil)
        #expect(SonioxSpeech.reply(from: #"{"hello":1}"#) == nil)
    }

    @Test("an error message names what went wrong, by its code")
    func errors() {
        func error(_ code: Int, _ type: String) -> SonioxSpeech.Reply? {
            SonioxSpeech.reply(from: #"{"tokens":[],"error_code":\#(code),"error_type":"\#(type)","error_message":"x"}"#)
        }
        #expect(error(401, "unauthenticated") == .failure(.keyRejected))
        #expect(error(402, "organization_balance_exhausted") == .failure(.outOfCredit))
        #expect(error(402, "project_monthly_budget_exhausted") == .failure(.outOfCredit))
        #expect(error(429, "rate_limit_exceeded") == .failure(.rateLimited))
        #expect(error(503, "service_unavailable") == .failure(.serverTrouble(status: 503)))
        #expect(error(400, "invalid_request") == .failure(.serverTrouble(status: 400)))
    }

    @Test("the key is checked by listing one file, and the check's answer reads the same way")
    func keyCheck() {
        let request = SonioxSpeech.keyCheckRequest(apiKey: "sx-test")
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://api.soniox.com/v1/files?limit=1")
        #expect(request.headers["Authorization"] == "Bearer sx-test")
        #expect(SonioxSpeech.failure(from: CloudHTTPResponse(status: 401, body: Data())) == .keyRejected)
        #expect(SonioxSpeech.failure(from: CloudHTTPResponse(status: 403, body: Data())) == .keyRejected)
        #expect(SonioxSpeech.failure(from: CloudHTTPResponse(status: 402, body: Data())) == .outOfCredit)
        #expect(SonioxSpeech.failure(from: CloudHTTPResponse(status: 500, body: Data())) == .serverTrouble(status: 500))
    }

    @Test("eleven of the twelve caption languages; Soniox has no Amharic")
    func languages() {
        #expect(SonioxSpeech.languages == ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"])
        #expect(CloudProvider.soniox.covers(languageCode: "he"))
        #expect(!CloudProvider.soniox.covers(languageCode: "am"))
        #expect(CloudProvider.soniox.models == ["stt-rt-v5"])
        #expect(CloudProvider.soniox.keychainService == "com.arbelonson.ozen.cloud.soniox")
        #expect(CloudProvider.soniox.streams && !CloudProvider.deepgram.streams && !CloudProvider.openRouter.streams)
    }

    @Test("a two-person conversation becomes a line per turn: live words, the final line, and a new turn marked when the voice changes")
    func linesFromFixture() throws {
        var lines = CloudStreamLines()
        var shown: [TranscriptToken] = []
        for frame in Self.frames {
            guard case .tokens(let tokens, let finished)? = SonioxSpeech.reply(from: frame) else {
                Issue.record("unreadable fixture frame")
                continue
            }
            shown += lines.take(tokens, at: 1)
            if finished { shown += lines.finish(at: 1) }
        }
        #expect(shown.map(\.text) == ["מה", "מה שלומך", "מה שלומך?", "טוב, תודה.", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"])
        #expect(shown.map(\.isFinal) == [false, false, true, false, false, true, false, true])
        #expect(shown.map(\.startsNewSpeakerTurn) == [false, false, false, true, true, true, true, true])
        let ids = shown.map(\.utteranceID)
        #expect(Set(ids[0...2]).count == 1 && Set(ids[3...5]).count == 1 && Set(ids[6...7]).count == 1)
        #expect(Set(ids).count == 3)
    }

    @Test("one voice going on past 28 seconds is cut at the next word, as every other engine cuts a line")
    func longLine() {
        var lines = CloudStreamLines()
        var shown = lines.take([SonioxSpeech.Token(text: "אחת", isFinal: true, speaker: "1", startMs: 0, endMs: 500)], at: 1)
        shown += lines.take([SonioxSpeech.Token(text: "ים", isFinal: true, speaker: "1", startMs: 27_900, endMs: 28_100)], at: 1)
        #expect(shown.last?.text == "אחתים")
        shown += lines.take([SonioxSpeech.Token(text: " שתיים", isFinal: true, speaker: "1", startMs: 28_200, endMs: 28_600)], at: 1)
        let finals = shown.filter(\.isFinal)
        #expect(finals.map(\.text) == ["אחתים"])
        #expect(shown.last?.text == "שתיים" && shown.last?.isFinal == false)
        #expect(shown.last?.startsNewSpeakerTurn == false)
        #expect(shown.last?.utteranceID != finals.first?.utteranceID)
    }

    @Test("a line Soniox ends before any of its guesses became final keeps what was on screen, as the other engines keep theirs")
    func guessesKept() {
        var lines = CloudStreamLines()
        let live = lines.take([SonioxSpeech.Token(text: "שלום", isFinal: false, speaker: "1", startMs: 0, endMs: 300)], at: 1)
        let ended = lines.take([SonioxSpeech.Token(text: "<end>", isFinal: true)], at: 2)
        #expect(ended.map(\.text) == ["שלום"])
        #expect(ended.first?.isFinal == true && ended.first?.utteranceID == live.first?.utteranceID)
    }

    @Test("a new voice heard first in the guesses is a new turn from its first word, before any of it is final")
    func newVoiceInGuesses() {
        var lines = CloudStreamLines()
        _ = lines.take([
            SonioxSpeech.Token(text: "שלום", isFinal: true, speaker: "1", startMs: 0, endMs: 300),
            SonioxSpeech.Token(text: "<end>", isFinal: true),
        ], at: 1)
        let other = lines.take([SonioxSpeech.Token(text: "היי", isFinal: false, speaker: "2", startMs: 900, endMs: 1_200)], at: 2)
        #expect(other.map(\.text) == ["היי"])
        #expect(other.first?.startsNewSpeakerTurn == true)
        let same = lines.take([
            SonioxSpeech.Token(text: "<end>", isFinal: true),
            SonioxSpeech.Token(text: "ומה", isFinal: false, speaker: "2", startMs: 1_800, endMs: 2_000),
        ], at: 3)
        #expect(same.last?.text == "ומה" && same.last?.startsNewSpeakerTurn == false)
    }

    @Test("a lost connection cuts the line on screen with the cut-off mark; with nothing on screen there is nothing to cut")
    func cutOff() {
        var lines = CloudStreamLines()
        #expect(lines.cutOff(at: 1) == nil)
        let live = lines.take([SonioxSpeech.Token(text: "שלום", isFinal: false, speaker: "1", startMs: 0, endMs: 300)], at: 1)
        let cut = lines.cutOff(at: 2)
        #expect(cut?.text == CaptionStabilizer.markingCutOff("שלום"))
        #expect(cut?.isFinal == true)
        #expect(cut?.utteranceID == live.first?.utteranceID)
        #expect(lines.cutOff(at: 3) == nil)
    }
}
