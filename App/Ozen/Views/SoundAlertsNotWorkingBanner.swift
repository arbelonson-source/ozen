import SwiftUI
import OzenKit

struct SoundAlertsNotWorkingBanner: View {
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "bell.slash.fill")
                .font(.system(size: 26, weight: .semibold))
            VStack(alignment: .leading, spacing: 2) {
                Text(StoppedCaptionsNotice.soundAlertsNotWorkingTitle)
                    .font(.headline)
                Text(StoppedCaptionsNotice.soundAlertsNotWorkingDetail)
                    .font(.subheadline)
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
        .accessibilityLabel("\(StoppedCaptionsNotice.soundAlertsNotWorkingTitle). \(StoppedCaptionsNotice.soundAlertsNotWorkingDetail)")
        .accessibilityIdentifier("soundAlertsNotWorkingBanner")
    }
}
