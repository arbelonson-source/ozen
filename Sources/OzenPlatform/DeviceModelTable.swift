import Foundation
@preconcurrency import WhisperKit
import OzenKit

public enum DeviceModelTable {
    public static var currentDeviceName: String { WhisperKit.deviceName() }

    public static func check(measurement: LivePassMeasurement?, now: Date = Date()) -> DeviceModelCheck {
        DeviceModelCheck(
            deviceName: currentDeviceName,
            supported: WhisperKit.recommendedModels().supported,
            measurement: measurement,
            checkedAt: now.timeIntervalSince1970
        )
    }
}
