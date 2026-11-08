import SwiftUI
import OzenKit

struct ListeningCutBanner: View {
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .accessibilityHidden(true)
                Text(text)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("listeningCutText")
                Spacer(minLength: 0)
            }
            Button(action: onDismiss) {
                Text(tr("אישור", "OK"))
                    .font(.body.weight(.semibold))
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityIdentifier("listeningCutDismiss")
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 4)
        .frame(maxWidth: .infinity)
        .background(Color.orange.deepShade.opacity(BannerPalette.fillAlpha), in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("listeningCutBanner")
    }
}
