import SwiftUI
import OzenKit

/// Away alerts are on but the phone's notifications for Ozen are off, so
/// nothing reaches her while the phone is in a pocket. It stays for as long
/// as that lasts, and a tap opens the phone's notification settings.
struct AwayAlertsBlockedBanner: View {
    @Environment(\.openURL) private var openURL
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 26

    private var spoken: String {
        "\(AwayAlertsNotice.title). \(AwayAlertsNotice.detail)"
    }

    var body: some View {
        Button {
            if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                openURL(url)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "bell.slash.fill")
                    .font(.system(size: iconSize, weight: .semibold))
                VStack(alignment: .leading, spacing: 2) {
                    Text(AwayAlertsNotice.title)
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(AwayAlertsNotice.detail)
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                        .opacity(BannerPalette.subtitleOpacity)
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(Color.red.deepShade.opacity(BannerPalette.fillAlpha), in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(tr("פתיחת הגדרות הטלפון", "Open the phone’s settings"))
        .onAppear { Announcement.post(spoken) }
        .accessibilityIdentifier("awayAlertsBlockedBanner")
    }
}
