import AVFoundation
import MediaPlayer
import OzenKit
import OzenPlatform
import SwiftUI

struct MediaVolumeNotice: View {
    let notice: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(notice, systemImage: "speaker.slash.fill")
                .font(.footnote)
                .foregroundStyle(.readable(.orange))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("mediaVolumeNotice")
            SystemVolumeSlider()
                .frame(height: 44)
        }
    }
}

extension View {
    func watchingMediaVolume(while active: Bool = true, _ update: @escaping @MainActor @Sendable (Float) -> Void) -> some View {
        task(id: active) { @MainActor in
            guard active else { return }
            while !Task.isCancelled {
                update(AVAudioSession.sharedInstance().outputVolume)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}

private struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        MPVolumeView(frame: .zero)
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {}
}

struct SpeakingRouteNotice: View {
    let notice: String
    let useSpeaker: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(notice, systemImage: "headphones")
                .font(.footnote)
                .foregroundStyle(.readable(.orange))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("speakingRouteNotice")
            Button(SpeakingRoute.speakerButton, action: useSpeaker)
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
                .accessibilityIdentifier("useSpeakerButton")
        }
    }
}

extension View {
    func watchingSpeakingRoute(while active: Bool = true, _ update: @escaping @MainActor @Sendable ([SpeakingOutput]) -> Void) -> some View {
        task(id: active) { @MainActor in
            guard active else { return }
            while !Task.isCancelled {
                update(SpeakingOutputRoute.outputs())
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}
