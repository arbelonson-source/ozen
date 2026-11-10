import Foundation
import Testing
@testable import OzenKit

@Suite("Whisper result filter shared cases")
struct WhisperResultFilterSharedCasesTests {
    private struct Table: Decodable {
        let cases: [Case]
    }

    private struct Case: Decodable {
        let kind: String
        let input: String?
        let language: String?
        let hallucinationIn: String?
        let maxRepeats: Int?
        let text: String?
        let noSpeechProb: Float?
        let avgLogprob: Float?
        let compressionRatio: Float?
        let expected: String?
    }

    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/whisper-result-filter.json")

    private static let filters = ["default", "he", "en", "ar", "de", "es", "fr", "pt", "ru", "uk", "zh", "am", "hi"]

    private static func filter(_ name: String) -> WhisperResultFilter {
        name == "default" ? WhisperResultFilter() : WhisperResultFilter().forSpeech(in: name)
    }

    @Test("every case in the shared table gives the same answer on this platform")
    func sharedTable() throws {
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: Self.file))
        let filters = Dictionary(uniqueKeysWithValues: Self.filters.map { ($0, Self.filter($0)) })
        var checked = 0
        for item in table.cases {
            checked += 1
            switch item.kind {
            case "knownHallucination":
                let input = try #require(item.input)
                let expected = Set((item.hallucinationIn ?? "").split(separator: ",").map(String.init))
                let actual = Set(Self.filters.filter { filters[$0]!.isKnownHallucination(input) })
                #expect(actual == expected, "input: \(input)")
            case "collapsingRepeats":
                let input = try #require(item.input)
                #expect(WhisperResultFilter.collapsingRepeats(input, maxRepeats: item.maxRepeats ?? 3) == item.expected, "input: \(input)")
            case "repeatedSentence":
                let input = try #require(item.input)
                #expect(WhisperResultFilter.repeatedSentence(input) == item.expected, "input: \(input)")
            case "stripSpecialTokens":
                let input = try #require(item.input)
                #expect(WhisperResultFilter.stripSpecialTokens(input) == item.expected, "input: \(input)")
            case "acceptedText":
                let segment = WhisperSegmentSummary(
                    text: try #require(item.text),
                    noSpeechProb: try #require(item.noSpeechProb),
                    avgLogprob: try #require(item.avgLogprob),
                    compressionRatio: try #require(item.compressionRatio)
                )
                let filter = filters[try #require(item.language)]!
                #expect(filter.acceptedText(from: [segment]) == item.expected, "text: \(segment.text), scores: \(segment.noSpeechProb) \(segment.avgLogprob) \(segment.compressionRatio)")
            default:
                Issue.record("bad case: \(item.kind)")
            }
        }
        #expect(checked > 600)
    }
}
