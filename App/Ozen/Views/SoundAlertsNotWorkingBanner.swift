import SwiftUI
import OzenKit

struct SoundAlertsNotWorkingBanner: View {
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 26

    private var spoken: String {
        "\(StoppedCaptionsNotice.soundAlertsNotWorkingTitle). \(StoppedCaptionsNotice.soundAlertsNotWorkingDetail)"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "bell.slash.fill")
                .font(.system(size: iconSize, weight: .semibold))
            VStack(alignment: .leading, spacing: 2) {
                Text(StoppedCaptionsNotice.soundAlertsNotWorkingTitle)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                Text(StoppedCaptionsNotice.soundAlertsNotWorkingDetail)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                    .opacity(BannerPalette.subtitleOpacity)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(Color.red.deepShade.opacity(BannerPalette.fillAlpha), in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
        .onAppear { Announcement.post(spoken) }
        .accessibilityIdentifier("soundAlertsNotWorkingBanner")
    }
}
