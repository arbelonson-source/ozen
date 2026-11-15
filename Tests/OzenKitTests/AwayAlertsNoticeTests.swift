import Foundation
import Testing
@testable import OzenKit

struct AwayAlertsNoticeTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/away-alerts-blocked.json")

    private static func table() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
    }

    @Test("the caption screen warns only when away alerts are on and notifications are off, as on the other phone")
    func sharedCases() throws {
        let cases = try Self.table()["cases"] as! [[String: Bool]]
        for item in cases {
            let actual = AwayAlertsNotice.blocked(awayAlertsOn: item["awayAlertsOn"]!, notificationsAllowed: item["notificationsAllowed"]!)
            #expect(actual == item["blocked"]!, "\(item)")
        }
        #expect(cases.count == 4)
    }

    @Test("the warning says in the same English words on both phones that alerts will not arrive")
    func sharedWords() throws {
        let table = try Self.table()
        Localization.$override.withValue(.english) {
            #expect(AwayAlertsNotice.title == table["title"] as? String)
            #expect(AwayAlertsNotice.detail == table["detail"] as? String)
        }
    }
}
