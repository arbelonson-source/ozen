import Foundation
import Testing
@testable import OzenKit

@Suite("A regional or old language code in the cloud engines")
struct CloudLanguageCodeTests {
    private let exactProviders: [CloudProvider] = [.deepgram, .assemblyAI, .soniox, .speechmatics, .gemini]

    @Test("a regional tag or the old Hebrew code is covered like its plain code")
    func regionalTagIsCovered() {
        for provider in exactProviders {
            for code in ["en-GB", "EN-us", "iw", "he-IL", "pt-BR"] {
                #expect(provider.covers(languageCode: code), "\(provider) \(code)")
            }
        }
    }

    @Test("a language the service lacks stays uncovered, regional or not")
    func unknownStaysUncovered() {
        for provider in [CloudProvider.deepgram, .assemblyAI, .soniox, .speechmatics] {
            for code in ["am", "am-ET", "xx-YY"] {
                #expect(!provider.covers(languageCode: code), "\(provider) \(code)")
            }
        }
    }

    @Test("the plain code maps old and regional codes and leaves unknown ones alone")
    func plainCode() {
        #expect(CloudProvider.plainCode("iw") == "he")
        #expect(CloudProvider.plainCode("en-GB") == "en")
        #expect(CloudProvider.plainCode("xx-YY") == "xx-YY")
    }

    @Test("a request made for the old Hebrew code carries the Hebrew code")
    func requestCarriesPlainCode() async throws {
        let http = FakeCloudHTTP(answers: [.text("שלום")])
        let engine = CloudSpeechEngine(provider: .deepgram, http: http, failedSegmentPauseSeconds: 0.001, apiKey: { "k" })
        let chunk = (0..<1_024).map { 0.05 * sin(Float($0) * 0.3) }
        let (audio, input) = AsyncStream<[Float]>.makeStream()
        let tokens = engine.stream(languageCode: "iw", audio: audio)
        for _ in 0..<16 { input.yield(chunk) }
        for _ in 0..<16 { input.yield([Float](repeating: 0, count: 1_024)) }
        input.finish()
        do { for try await _ in tokens {} } catch {}
        let url = try #require(http.transcriptionRequests.first?.url)
        #expect(url.absoluteString.contains("language=he"), "\(url)")
    }

    @Test("preparing with a regional tag checks the key instead of giving up on the language")
    func prepareWithRegionalTag() async {
        let http = FakeCloudHTTP()
        let engine = CloudSpeechEngine(provider: .deepgram, http: http, apiKey: { "k" })
        #expect(await engine.checkAvailability(languageCode: "en-GB") == .available)
    }
}
