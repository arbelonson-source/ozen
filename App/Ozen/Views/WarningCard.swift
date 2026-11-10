import SwiftUI
import OzenKit

struct WarningCard: View {
    let systemImage: String
    let title: String
    let detail: String
    let tint: Color
    var spoken: String?
    let closeIdentifier: String
    let onClose: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 26, weight: .semibold))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                    Text(detail)
                        .font(.subheadline)
                        .opacity(BannerPalette.subtitleOpacity)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 16)
            .padding(.vertical, 12)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken ?? "\(title). \(detail)")
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.headline)
                    .opacity(BannerPalette.subtitleOpacity)
                    .frame(minWidth: 44, minHeight: 44)
                    .padding(.trailing, 6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr("סגירה", "Close"))
            .accessibilityIdentifier(closeIdentifier)
        }
        .frame(maxWidth: .infinity)
        .background(tint, in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
    }
}
