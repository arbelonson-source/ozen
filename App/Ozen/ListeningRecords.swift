import Foundation
import OzenKit

struct UserDefaultsListeningStore: ListeningRecordStore {
    private static let key = "ozen-listening-record"

    func read() -> String? { UserDefaults.standard.string(forKey: Self.key) }
    func write(_ value: String) { UserDefaults.standard.set(value, forKey: Self.key) }
    func clear() { UserDefaults.standard.removeObject(forKey: Self.key) }
}

enum ListeningRecords {
    static var appVersion: Int {
        let info = Bundle.main.infoDictionary ?? [:]
        return ListeningCut.versionNumber(
            shortVersion: info["CFBundleShortVersionString"] as? String,
            build: info["CFBundleVersion"] as? String
        )
    }

    static func makeRecorder() -> ListeningRecorder {
        ListeningRecorder(store: UserDefaultsListeningStore(), version: appVersion)
    }
}
