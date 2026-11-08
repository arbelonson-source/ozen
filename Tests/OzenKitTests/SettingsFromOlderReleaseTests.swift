import Foundation
import Testing
@testable import OzenKit

@Suite("Settings saved by an older release")
struct SettingsFromOlderReleaseTests {
    private static let savedBy030 = #"""
        {
            "allowCellularModelDownload": true,
            "allowServerFallbackForAppleSpeech": true,
            "appLanguage": "english",
            "betterModelOfferDeclines": 2,
            "betterModelOfferSnoozedUntil": 1800000000,
            "cloudModel": "nova-3",
            "cloudProvider": "deepgram",
            "display": {
                "announceNewLines": false,
                "autoHideControls": false,
                "boldText": true,
                "emphasizeNumbers": false,
                "fontSize": 48,
                "keepScreenAwake": false,
                "lockScreenCaptions": false,
                "markUncertainLines": false,
                "showSpeakerNames": false,
                "theme": "highContrast"
            },
            "engine": "cloud",
            "hapticOnSpeechResume": false,
            "hasCompletedOnboarding": true,
            "historyRetention": "month",
            "homeServerAddress": "192.168.1.20",
            "homeServerBeam": 3,
            "keywordAlerts": [
                {
                    "id": "66666666-7777-8888-9999-000000000000",
                    "isEnabled": false,
                    "phrase": "Dana"
                }
            ],
            "languageCode": "en",
            "modelFitOfferDismissed": [],
            "nameAlertOfferDismissed": true,
            "notifyWhenInBackground": false,
            "preferredInputUID": "usb-lav-1",
            "quickPhrases": [
                "One moment, please"
            ],
            "quietHours": {
                "endHour": 6,
                "isEnabled": true,
                "startHour": 23
            },
            "saveHistory": false,
            "soundAlerts": {
                "isEnabled": false,
                "minimumImportance": 3,
                "mutedIdentifiers": [
                    "siren"
                ],
                "sensitiveIdentifiers": [
                    "smoke_detector"
                ]
            },
            "speakerProfiles": [
                {
                    "embedding": [
                        0.25,
                        0.5,
                        0.75
                    ],
                    "id": "11111111-2222-3333-4444-555555555555",
                    "name": "Dana"
                }
            ],
            "speakerSimilarityThreshold": 0.8,
            "speakerThresholdScale": 2,
            "speechRate": 0.375,
            "vocabulary": [
                "Ozen"
            ],
            "whisperModelVariant": "ozen-turbo-hebrew-a3-8bit",
            "wordFixes": [
                {
                    "heard": "oh zen",
                    "written": "Ozen"
                }
            ]
        }
        """#

    @Test("every setting 0.3.0 saved comes back unchanged after this build loads it and saves it again")
    func everySettingSavedBy030Survives() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-settings-0.3.0-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("settings.json")
        try Data(Self.savedBy030.utf8).write(to: url)
        let store = SettingsStore(fileURL: url)
        try store.save(store.load())

        let saved = try Self.object(Data(Self.savedBy030.utf8))
        let again = try Self.object(Data(contentsOf: url))
        let changed = saved.keys.sorted().filter { !Self.same(saved[$0], again[$0]) }
        #expect(saved.count == 31)
        #expect(changed.isEmpty, "settings 0.3.0 saved that came back changed or missing: \(changed)")
    }

    private struct NotAnObject: Error {}

    private static func object(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw NotAnObject() }
        return object
    }

    private static func same(_ first: Any?, _ second: Any?) -> Bool {
        guard let first, let second, let one = canonical(first), let other = canonical(second) else { return false }
        return one == other
    }

    private static func canonical(_ value: Any) -> Data? {
        try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])
    }
}
