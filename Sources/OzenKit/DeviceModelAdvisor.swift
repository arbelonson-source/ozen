import Foundation

public enum ModelFit: Int, Codable, Sendable, Comparable {
    case tooSlow, aLittleBehind, keepsUp

    public static func < (lhs: ModelFit, rhs: ModelFit) -> Bool { lhs.rawValue < rhs.rawValue }
}

public struct LivePassMeasurement: Codable, Equatable, Sendable {
    public var variant: String
    public var averageSeconds: Double
    public var passes: Int

    public init(variant: String, averageSeconds: Double, passes: Int) {
        self.variant = variant
        self.averageSeconds = averageSeconds
        self.passes = passes
    }
}

public struct DeviceModelCheck: Codable, Equatable, Sendable {
    public var deviceName: String
    public var supported: [String]
    public var measurement: LivePassMeasurement?
    public var checkedAt: Double

    public init(deviceName: String, supported: [String], measurement: LivePassMeasurement? = nil, checkedAt: Double = 0) {
        self.deviceName = deviceName
        self.supported = supported
        self.measurement = measurement
        self.checkedAt = checkedAt
    }
}

public enum DeviceModelAdvisor {
    public static let minimumMeasuredPasses = 5

    static func listName(for option: WhisperModelOption) -> String? {
        switch option.variant {
        case "medium": return nil
        case "small_216MB": return "openai_whisper-small"
        case "ozen-turbo-hebrew-a3-8bit", "ivrit-large-v3-turbo-8bit": return "openai_whisper-large-v3-v20240930_626MB"
        default: return option.folderName
        }
    }

    public static func measuredFit(averageSeconds: Double) -> ModelFit {
        if averageSeconds <= InferenceCadence.baseSeconds { return .keepsUp }
        if averageSeconds <= InferenceCadence.baseSeconds * 2 { return .aLittleBehind }
        return .tooSlow
    }

    public static func fit(of option: WhisperModelOption, on check: DeviceModelCheck) -> ModelFit {
        guard option.speed > 1, let name = listName(for: option), check.supported.contains(name) else { return .tooSlow }
        guard let measurement = check.measurement, measurement.passes >= minimumMeasuredPasses,
              let measured = WhisperModelCatalog.option(for: measurement.variant)
        else { return .keepsUp }
        let timed = measuredFit(averageSeconds: measurement.averageSeconds)
        if option.speed < measured.speed { return timed == .keepsUp ? .keepsUp : .tooSlow }
        if option.speed == measured.speed { return timed }
        return .keepsUp
    }

    public static func warns(about fit: ModelFit) -> Bool { fit != .keepsUp }

    public static func recommendedVariant(for language: UILanguage, on check: DeviceModelCheck?) -> String {
        guard let check else { return WhisperModelCatalog.recommendedVariant(for: language) }
        let candidates = WhisperModelCatalog.listed(for: language).filter { language == .hebrew || !$0.isHebrewOnly }
        for wanted in [ModelFit.keepsUp, .aLittleBehind] {
            let fitting = candidates.filter { fit(of: $0, on: check) == wanted }
            if let best = fitting.reduce(nil, { (best: WhisperModelOption?, next) in
                guard let best else { return next }
                return (next.hebrewQuality, next.sizeMB) > (best.hebrewQuality, best.sizeMB) ? next : best
            }) { return best.variant }
        }
        let fastest = candidates.reduce(nil) { (best: WhisperModelOption?, next) in
            guard let best else { return next }
            return next.speed > best.speed ? next : best
        }
        return fastest?.variant ?? WhisperModelCatalog.recommendedVariant(for: language)
    }

    public static func ordered(for language: UILanguage, on check: DeviceModelCheck?) -> [WhisperModelOption] {
        let recommended = recommendedVariant(for: language, on: check)
        let all = WhisperModelCatalog.listed(for: language)
        return all.filter { $0.variant == recommended } + all.filter { $0.variant != recommended }
    }
}
