import Foundation
import OzenKit

struct UserDefaultsAwaySoundStore: AwaySoundRecordStore {
    private static let key = "ozen-away-sound"

    func read() -> String? { UserDefaults.standard.string(forKey: Self.key) }

    func write(_ value: String?) {
        if let value {
            UserDefaults.standard.set(value, forKey: Self.key)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.key)
        }
    }
}
