import SwiftUI
import OzenKit

extension View {
    func wantsLiveInputLevel() -> some View {
        modifier(WantsLiveInputLevel())
    }
}

private struct WantsLiveInputLevel: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    @State private var hold = LevelDemandHold()
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                shown = true
                sync()
            }
            .onDisappear {
                shown = false
                sync()
            }
            .onChange(of: scenePhase) { _, _ in sync() }
    }

    private func sync() {
        LevelMeterDemand.shared.apply(
            hold.update(shown: shown, inBackground: scenePhase == .background))
    }
}
