import Foundation
@preconcurrency import WhisperKit
import OzenKit

public enum DeviceModelTable {
    public static func check(measurement: LivePassMeasurement?, now: Date = Date()) -> DeviceModelCheck {
        DeviceModelCheck(
            deviceName: WhisperKit.deviceName(),
            supported: WhisperKit.recommendedModels().supported,
            measurement: measurement,
            checkedAt: now.timeIntervalSince1970
        )
    }
}
