import SwiftUI
import OzenKit

extension AnyTransition {
    static func banner(from edge: Edge, reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: edge).combined(with: .opacity)
    }
}

private struct AlertOverlay: ViewModifier {
    let viewModel: LiveCaptionViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: SoundAlert?
    @State private var shownHit: KeywordHit?
    @State private var shownHitAt = Date()
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// A banner or pill that hid itself on its timer left a VoiceOver or
    /// braille user, who heard only the buzz, no way to find which sound or
    /// word it was; while VoiceOver runs it stays until closed or replaced
    /// (see `AlertBannerTimer`), as the control bar does.
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverRunning

    private struct PillTimer: Hashable {
        let id: UUID
        let seconds: Double
        let voiceOverRunning: Bool
    }

    private struct BannerTimer: Hashable {
        let id: UUID?
        let voiceOverRunning: Bool
    }

    private var pillTimer: PillTimer? {
        guard let hit = shownHit else { return nil }
        let seconds = KeywordPill.visibleSeconds(for: hit, speaker: viewModel.speakerName(for: hit), accessibilitySize: dynamicTypeSize.isAccessibilitySize)
        return PillTimer(id: hit.id, seconds: seconds, voiceOverRunning: voiceOverRunning)
    }

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if shown != nil || shownHit != nil {
                    VStack(spacing: 8) {
                        if let shown {
                            SoundAlertBanner(alert: shown) {
                                withAnimation { self.shown = nil }
                                viewModel.dismissSoundAlert(id: shown.id)
                            }
                            .transition(.banner(from: .top, reduceMotion: reduceMotion))
                        }
                        if let shownHit {
                            KeywordHitPill(hit: shownHit, speakerName: viewModel.speakerName(for: shownHit)) {
                                withAnimation(reduceMotion ? nil : .default) { self.shownHit = nil }
                            }
                                .transition(.banner(from: .top, reduceMotion: reduceMotion))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
            }
            .overlay {
                AlertFlashOverlay(alert: viewModel.currentScreenSoundAlert, standIn: viewModel.flashInsteadOfBuzz)
            }
            .onChange(of: viewModel.screenSoundAlert?.id) { _, _ in
                guard let alert = viewModel.currentScreenSoundAlert, alert.takesBanner(from: shown) else { return }
                withAnimation { shown = alert }
            }
            .onChange(of: viewModel.attentionKeywordHit?.id) { _, _ in
                guard let hit = viewModel.attentionKeywordHit else { return }
                shownHitAt = Date()
                withAnimation(reduceMotion ? nil : .default) { shownHit = hit }
            }
            .task(id: BannerTimer(id: shown?.id, voiceOverRunning: voiceOverRunning)) {
                guard let current = shown,
                      let left = AlertBannerTimer.hidesAfter(viewModel.bannerSecondsLeft(for: current), voiceOverRunning: voiceOverRunning)
                else { return }
                try? await Task.sleep(for: .seconds(left))
                if Task.isCancelled { return }
                if shown?.id == current.id {
                    withAnimation { shown = nil }
                }
            }
            .task(id: pillTimer) {
                guard let timer = pillTimer,
                      let left = AlertBannerTimer.hidesAfter(KeywordPill.secondsLeft(total: timer.seconds, elapsed: Date().timeIntervalSince(shownHitAt)), voiceOverRunning: timer.voiceOverRunning)
                else { return }
                try? await Task.sleep(for: .seconds(left))
                if Task.isCancelled { return }
                if shownHit?.id == timer.id {
                    withAnimation(reduceMotion ? nil : .default) { shownHit = nil }
                }
            }
    }
}

extension View {
    func alertOverlay(for viewModel: LiveCaptionViewModel) -> some View {
        modifier(AlertOverlay(viewModel: viewModel))
            .readingDirection(of: viewModel.uiLanguage)
    }
}
