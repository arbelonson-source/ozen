import Foundation

public extension AppLanguage {
    var bundleLocalization: String? {
        switch self {
        case .system: return nil
        case .hebrew: return "he"
        case .english: return "en"
        case .arabic: return "ar"
        case .russian: return "ru"
        case .amharic: return "am"
        case .french: return "fr"
        case .spanish: return "es"
        case .ukrainian: return "uk"
        case .german: return "de"
        case .portuguese: return "pt-PT"
        case .chineseSimplified: return "zh-Hans"
        case .hindi: return "hi"
        }
    }
}

public enum SystemLanguage {
    public static let key = "AppleLanguages"
    static let writtenKey = "OzenWroteAppleLanguages"

    public static func apply(_ setting: AppLanguage, to defaults: UserDefaults) {
        if let code = setting.bundleLocalization {
            defaults.set([code], forKey: key)
            defaults.set(true, forKey: writtenKey)
        } else if defaults.bool(forKey: writtenKey) {
            defaults.removeObject(forKey: key)
            defaults.removeObject(forKey: writtenKey)
        }
    }
}
