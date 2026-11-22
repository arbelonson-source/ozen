import SwiftUI
import OzenKit

struct SavingTroubleBanner: View {
    let title: String
    let detail: String
    let onDismiss: () -> Void

    var body: some View {
        WarningCard(
            systemImage: "exclamationmark.triangle.fill",
            title: title,
            detail: detail,
            tint: Color.orange.deepShade.opacity(BannerPalette.fillAlpha),
            closeIdentifier: "savingTroubleClose",
            onClose: onDismiss
        )
    }
}
