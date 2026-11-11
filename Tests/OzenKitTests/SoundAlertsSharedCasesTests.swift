import Foundation
import Testing
@testable import OzenKit

@Suite("Sound alerts shared cases")
struct SoundAlertsSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/sound-alerts.json")

    private static func table() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
    }

    private static func number(_ value: Any?) -> Double {
        (value as! NSNumber).doubleValue
    }

    @Test("the catalog lists the same sounds in the same order, with the same importance, cooldown, picture and names")
    func catalog() throws {
        let table = try Self.table()
        let rows = table["catalog"] as! [[String: Any]]
        let hebrew = Localization.$override.withValue(.hebrew) { SoundEventCatalog.events }
        let english = Localization.$override.withValue(.english) { SoundEventCatalog.events }
        #expect(hebrew.map(\.identifier) == rows.map { $0["identifier"] as! String })
        for (row, (inHebrew, inEnglish)) in zip(rows, zip(hebrew, english)) {
            let identifier = inHebrew.identifier
            #expect(inHebrew.importance.rawValue == row["importance"] as! Int, "\(identifier)")
            #expect(inHebrew.cooldownKey == row["sameSoundAs"] as! String, "\(identifier)")
            #expect(inHebrew.systemImage == row["systemImage"] as! String, "\(identifier)")
            #expect(inHebrew.name == row["hebrew"] as! String, "\(identifier)")
            #expect(inEnglish.name == row["english"] as! String, "\(identifier)")
        }
        #expect(SoundEventCatalog.listed.map(\.identifier) == table["listed"] as! [String])
        #expect(SoundEventCatalog.vibrationLookalikes == Set(table["vibrationLookalikes"] as! [String]))
        #expect(SoundEventPolicy.sustainedIdentifiers == Set(table["sustained"] as! [String]))
    }

    @Test("every heard sequence raises the same alerts at the same moments")
    func sequences() throws {
        let sequences = try Self.table()["sequences"] as! [[String: Any]]
        var heard = 0
        for sequence in sequences {
            let note = sequence["note"] as! String
            let given = sequence["preferences"] as? [String: Any] ?? [:]
            let defaults = SoundAlertPreferences.default
            let preferences = SoundAlertPreferences(
                isEnabled: given["isEnabled"] as? Bool ?? defaults.isEnabled,
                minimumImportance: (given["minimumImportance"] as? Int).flatMap(SoundEvent.Importance.init(rawValue:)) ?? defaults.minimumImportance,
                mutedIdentifiers: Set(given["mutedIdentifiers"] as? [String] ?? []),
                sensitiveIdentifiers: Set(given["sensitiveIdentifiers"] as? [String] ?? [])
            )
            var policy = SoundEventPolicy(preferences: preferences)
            let changed = sequence["policy"] as? [String: Any] ?? [:]
            if let value = changed["minimumConfidence"] { policy.minimumConfidence = Self.number(value) }
            if let value = changed["sensitiveConfidence"] { policy.sensitiveConfidence = Self.number(value) }
            if let value = changed["cooldownSeconds"] { policy.cooldownSeconds = Self.number(value) }
            if let value = changed["emergencyConfidence"] { policy.emergencyConfidence = Self.number(value) }
            if let value = changed["persistenceWindowSeconds"] { policy.persistenceWindowSeconds = Self.number(value) }
            for (index, step) in (sequence["steps"] as! [[String: Any]]).enumerated() {
                if step["reset"] != nil {
                    policy.resetCooldowns()
                    continue
                }
                let hear = step["hear"] as! [Any]
                let observation = SoundObservation(identifier: hear[0] as! String, confidence: Self.number(hear[1]), timestamp: Self.number(hear[2]))
                let alert = policy.evaluate(observation)
                #expect(alert?.event.identifier == step["alert"] as? String, "\(note), step \(index + 1)")
                if let alert {
                    #expect(alert.confidence == observation.confidence && alert.timestamp == observation.timestamp, "\(note), step \(index + 1)")
                }
                heard += 1
            }
        }
        #expect(sequences.count > 20 && heard > 70)
    }

    @Test("what is heard in one reading, which alert takes the banner and for how long, and the gate between gaps")
    func matchingBannersAndGate() throws {
        let table = try Self.table()
        for item in table["matching"] as! [[String: Any]] {
            let candidates = (item["candidates"] as! [[Any]]).map { (identifier: $0[0] as! String, confidence: Self.number($0[1])) }
            let found = SoundEventCatalog.matchingObservations(from: candidates, minimumConfidence: Self.number(item["minimumConfidence"]), timestamp: 7)
            let expected = (item["expected"] as! [[Any]]).map { SoundObservation(identifier: $0[0] as! String, confidence: Self.number($0[1]), timestamp: 7) }
            #expect(found == expected, "\(item["note"] as! String)")
        }
        for item in table["banners"] as! [[String: Any]] {
            let alert = SoundAlert(event: SoundEventCatalog.event(for: item["alert"] as! String)!, confidence: 0.9, timestamp: 1)
            let shown = (item["shown"] as? String).map { SoundAlert(event: SoundEventCatalog.event(for: $0)!, confidence: 0.9, timestamp: 0) }
            #expect(alert.takesBanner(from: shown) == item["takesBanner"] as! Bool, "\(item)")
            #expect(alert.bannerSeconds == Self.number(item["bannerSeconds"]), "\(item)")
        }
        var gate = SoundAlertGate()
        for item in table["gate"] as! [[String: Any]] {
            let passed = gate.pass([Float](repeating: 0.5, count: item["length"] as! Int), alertsOn: item["on"] as! Bool).map(\.count)
            #expect(passed == item["passed"] as! [Int], "\(item)")
        }
    }
}
