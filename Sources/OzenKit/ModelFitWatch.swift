import Foundation

public struct ModelFitDismissal: Codable, Hashable, Sendable {
    public var variant: String
    public var deviceName: String

    public init(variant: String, deviceName: String) {
        self.variant = variant
        self.deviceName = deviceName
    }
}

public enum ModelFitWatch {
    public static let passesToSave = 20

    public static func counts(heat: DeviceHeat, lowPower: Bool, onPhoneModel: Bool) -> Bool {
        onPhoneModel && heat < .serious && !lowPower
    }

    /// Phones that stay in Low Power Mode never get a normal pass counted;
    /// for them Low Power Mode is the speed they really have, so those
    /// passes get a tally of their own.
    public static func countsInLowPower(heat: DeviceHeat, lowPower: Bool, onPhoneModel: Bool) -> Bool {
        onPhoneModel && heat < .serious && lowPower
    }

    public enum FitTally: Equatable, Sendable { case normal, lowPower }

    public static func tally(
        heatAtStart: DeviceHeat, lowPowerAtStart: Bool,
        heatAtEnd: DeviceHeat, lowPowerAtEnd: Bool,
        retried: Bool, onPhoneModel: Bool
    ) -> FitTally? {
        guard !retried else { return nil }
        if counts(heat: heatAtStart, lowPower: lowPowerAtStart, onPhoneModel: onPhoneModel),
           counts(heat: heatAtEnd, lowPower: lowPowerAtEnd, onPhoneModel: onPhoneModel) { return .normal }
        if countsInLowPower(heat: heatAtStart, lowPower: lowPowerAtStart, onPhoneModel: onPhoneModel),
           countsInLowPower(heat: heatAtEnd, lowPower: lowPowerAtEnd, onPhoneModel: onPhoneModel) { return .lowPower }
        return nil
    }

    public static func shouldSave(passes: Int) -> Bool { passes >= passesToSave }

    public static func measurementToKeep(
        saved: LivePassMeasurement?,
        counted: LivePassMeasurement?,
        live: LivePassMeasurement?,
        selected: String,
        lowPowerCounted: LivePassMeasurement? = nil
    ) -> LivePassMeasurement? {
        let counted = counted?.variant == selected ? counted : nil
        let live = live?.variant == selected ? live : nil
        if let counted, shouldSave(passes: counted.passes) { return counted }
        let savedNormal = saved.map { $0.fitEligible == true && !$0.lowPower && shouldSave(passes: $0.passes) && $0.variant == selected } ?? false
        if let lowPower = lowPowerCounted, lowPower.variant == selected, lowPower.lowPower,
           shouldSave(passes: lowPower.passes), !savedNormal { return lowPower }
        guard let live, live.passes >= DeviceModelAdvisor.minimumMeasuredPasses else { return saved }
        if let saved, saved.fitEligible == true, shouldSave(passes: saved.passes), saved.variant == live.variant { return saved }
        return live
    }

    public static func offer(
        check: DeviceModelCheck?,
        current: String,
        language: UILanguage,
        dismissed: [ModelFitDismissal],
        device: String
    ) -> String? {
        guard let check, check.deviceName == device,
              let measurement = check.measurement, measurement.variant == current,
              measurement.fitEligible == true, shouldSave(passes: measurement.passes),
              DeviceModelAdvisor.measuredFit(averageSeconds: measurement.averageSeconds) != .keepsUp,
              !dismissed.contains(ModelFitDismissal(variant: current, deviceName: device)),
              let running = WhisperModelCatalog.option(for: current)
        else { return nil }
        let recommended = DeviceModelAdvisor.recommendedVariant(for: language, on: check)
        guard recommended != current, let lighter = WhisperModelCatalog.option(for: recommended),
              lighter.speed > running.speed,
              DeviceModelAdvisor.fit(of: lighter, on: check) != .tooSlow,
              !dismissed.contains(ModelFitDismissal(variant: recommended, deviceName: device))
        else { return nil }
        return recommended
    }
}
