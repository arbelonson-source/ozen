import Foundation
import Testing
@testable import OzenKit

@Suite("Number emphasis shared cases")
struct NumberEmphasisSharedCasesTests {
    private struct Table: Decodable {
        let cases: [Case]
    }

    private struct Case: Decodable {
        let kind: String
        let input: String
        let expected: Expected?
        let only: String?
        let knownDifference: String?
    }

    private enum Expected: Decodable {
        case words([String])
        case flag(Bool)

        init(from decoder: Decoder) throws {
            let box = try decoder.singleValueContainer()
            if let flag = try? box.decode(Bool.self) {
                self = .flag(flag)
            } else {
                self = .words(try box.decode([String].self))
            }
        }
    }

    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/number-emphasis.json")

    @Test("every case in the shared table gives the same answer on this platform")
    func sharedTable() throws {
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: Self.file))
        #expect(table.cases.count > 100)
        var checked = 0
        for item in table.cases {
            if let note = item.knownDifference {
                print("skipped (known difference): \(item.input) - \(note)")
                continue
            }
            if let only = item.only, only != "swift" { continue }
            checked += 1
            switch (item.kind, item.expected) {
            case ("emphasized", .words(let words)?):
                let actual = NumberEmphasis.ranges(in: item.input).map { String(item.input[$0]) }
                #expect(actual == words, "input: \(item.input)")
            case ("listable", .flag(let flag)?):
                #expect(NumberEmphasis.hasListableNumber(item.input) == flag, "input: \(item.input)")
            default:
                Issue.record("bad case: \(item.input)")
            }
        }
        #expect(checked > 100)
    }
}
