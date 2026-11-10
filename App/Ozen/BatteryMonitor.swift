import SwiftUI
import UIKit
import Observation
import OzenKit

/// Watches the battery while captions run and raises `BatteryAdvisor`'s
/// warnings for the caption screen to show.
@MainActor
@Observable
final class BatteryMonitor {
    struct Notice: Identifiable, Equatable {
        let id = UUID()
        let warning: BatteryWarning
    }

    private(set) var notice: Notice?
    /// Called with each new warning, e.g. to post it as a notification
    /// when the app isn't on screen.
    @ObservationIgnored var onWarning: ((BatteryWarning) -> Void)?
    /// Called when the phone is on a charger or watching stops, so a
    /// posted warning can be taken down.
    @ObservationIgnored var onCleared: (() -> Void)?
    private var advisor = BatteryAdvisor()
    private var observers: [NSObjectProtocol] = []
    private var isActive = false

    /// Starts or stops watching. Readings only count while captions are
    /// running: a warning for a phone that isn't doing anything is noise.
    func setActive(_ active: Bool) {
        guard active != isActive else { return }
        isActive = active
        let device = UIDevice.current
        if active {
            device.isBatteryMonitoringEnabled = true
            let center = NotificationCenter.default
            for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
                observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    Task { @MainActor [weak self] in self?.read() }
                })
            }
            read()
        } else {
            for observer in observers {
                NotificationCenter.default.removeObserver(observer)
            }
            observers.removeAll()
            device.isBatteryMonitoringEnabled = false
            // Nothing reads the charger from here on: a "plug in a charger"
            // left up would stay after she did.
            notice = nil
            onCleared?()
        }
    }

    func dismiss() {
        notice = nil
    }

    private func read() {
        let device = UIDevice.current
        let pluggedIn = device.batteryState == .charging || device.batteryState == .full
        let level: Float? = device.batteryState == .unknown ? nil : device.batteryLevel
        let warning = advisor.update(level: level, isPluggedIn: pluggedIn)
        if advisor.shouldWithdraw {
            notice = nil
            onCleared?()
        }
        if let warning {
            notice = Notice(warning: warning)
            onWarning?(warning)
        }
    }
}

/// "The battery is at 18%" in the top overlay, tinted by urgency.
struct BatteryBanner: View {
    /// What VoiceOver reads for it, on the banner and when it appears.
    static func spoken(_ warning: BatteryWarning) -> String {
        tr("הסוללה ב־%1 אחוז", "Battery at %1 percent", args: ["\(warning.percent)"]) + ". " + advice(for: warning)
    }

    let notice: BatteryMonitor.Notice
    let onDismiss: () -> Void

    private var isCritical: Bool { Self.isCritical(notice.warning) }

    private static func isCritical(_ warning: BatteryWarning) -> Bool {
        if case .critical = warning { return true }
        return false
    }

    private var advice: String { Self.advice(for: notice.warning) }

    private static func advice(for warning: BatteryWarning) -> String {
        isCritical(warning) ? tr("הטלפון עלול להיכבות באמצע השיחה. חברו אותו למטען.", "The phone might shut down mid-conversation. Plug in a charger.") : tr("כדאי לחבר את הטלפון למטען.", "Consider plugging in a charger.")
    }

    var body: some View {
        // White on the system orange is under 3:1: the deeper shade
        // keeps the percentage readable at a glance.
        WarningCard(
            systemImage: isCritical ? "battery.0percent" : "battery.25percent",
            title: tr("הסוללה ב־%1%", "Battery at %1%", args: ["\(notice.warning.percent)"]),
            detail: advice,
            tint: (isCritical ? Color.red : Color.orange).deepShade.opacity(BannerPalette.fillAlpha),
            spoken: Self.spoken(notice.warning),
            closeIdentifier: "batteryClose",
            onClose: onDismiss
        )
    }
}
