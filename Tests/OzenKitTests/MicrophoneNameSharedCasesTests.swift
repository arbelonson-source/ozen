import Foundation
import Testing
@testable import OzenKit

@Suite("Microphone name shared cases")
struct MicrophoneNameSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/microphone-names.json")

    @Test("every microphone in the shared table, from its connection and name, is the same kind on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let name = item["name"] as! String
            let connection = AudioPortType(rawValue: item["connection"] as! String)!
            let expected = AudioPortType(rawValue: item["expected"] as! String)
            #expect(AudioPortType.of(connection: connection, name: name) == expected, "\(name.debugDescription) over \(connection)")
        }
        #expect(cases.count > 40)
        #expect(Set(cases.compactMap { $0["expected"] as? String }) == ["remoteMic", "hearingAid", "bluetooth", "usb", "wired", "builtInMic", "other"])
    }
}
