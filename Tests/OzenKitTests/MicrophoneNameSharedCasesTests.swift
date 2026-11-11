import Foundation
import Testing
@testable import OzenKit

@Suite("Microphone name shared cases")
struct MicrophoneNameSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/microphone-names.json")

    @Test("every microphone name in the shared table reads as the same kind on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let name = item["name"] as! String
            let expected = item["expected"] as? String
            #expect(AudioPortType.named(name)?.rawValue == expected, "\(name.debugDescription)")
        }
        #expect(cases.count > 40)
        #expect(Set(cases.compactMap { $0["expected"] as? String }) == ["remoteMic", "hearingAid"])
    }
}
