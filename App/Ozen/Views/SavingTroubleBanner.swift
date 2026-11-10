import SwiftUI
import OzenKit

struct SavingTroubleBanner: View {
    let onDismiss: () -> Void

    var body: some View {
        WarningCard(
            systemImage: "exclamationmark.triangle.fill",
            title: SavingTroubleNotice.title,
            detail: SavingTroubleNotice.detail,
            tint: Color.orange.deepShade.opacity(BannerPalette.fillAlpha),
            closeIdentifier: "savingTroubleClose",
            onClose: onDismiss
        )
    }
}
