import SwiftUI
import OzenKit

extension View {
    func wantsLiveInputLevel() -> some View {
        modifier(WantsLiveInputLevel())
    }
}

private struct WantsLiveInputLevel: ViewModifier {
    func body(content: Content) -> some View {
        content
            .onAppear { LevelMeterDemand.shared.acquire() }
            .onDisappear { LevelMeterDemand.shared.release() }
    }
}
