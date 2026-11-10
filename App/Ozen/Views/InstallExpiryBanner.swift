import SwiftUI
import OzenKit

struct InstallExpiryBanner: View {
    let expiresAt: Date
    let now: Date
    let onDismiss: () -> Void

    var body: some View {
        WarningCard(
            systemImage: "calendar.badge.exclamationmark",
            title: InstallExpiry.warningTitle(expiresAt: expiresAt, now: now, utcOffsetSeconds: TimeZone.current.secondsFromGMT(for: expiresAt)),
            detail: InstallExpiry.warningDetail,
            tint: Color.purple.deepShade.opacity(0.92),
            closeIdentifier: "installExpiryClose",
            onClose: onDismiss
        )
    }
}
