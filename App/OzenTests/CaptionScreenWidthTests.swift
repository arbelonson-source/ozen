import Testing
import SwiftUI
import UIKit
@testable import Ozen
@testable import OzenKit
import Foundation

@Suite("Caption screen width")
@MainActor
struct CaptionScreenWidthTests {
    @Test("with lines on screen, the caption screen is no wider than an iPhone 13 mini or SE, at any text size")
    func fitsTheNarrowestPhones() {
        let viewModel = LiveCaptionViewModel(
            settingsStore: SettingsStore(fileURL: FileManager.default.temporaryDirectory
                .appendingPathComponent("ozen-width-test-\(UUID()).json")),
            pipeline: CaptionPipeline(
                audio: FakeAudioCapturer(),
                engineFactory: { settings in FakeEngine(kind: settings.engine) },
                embedder: FakeEmbedder()
            )
        )
        viewModel.pipeline.seedForScreenshots()
        let narrowest = CGSize(width: 375, height: 667)
        let pixelRounding = 0.5
        for textSize in [DynamicTypeSize.large, .xxxLarge, .accessibility5] {
            let screen = UIHostingController(rootView: LiveCaptionView(viewModel: viewModel).environment(\.dynamicTypeSize, textSize))
            let width = screen.sizeThatFits(in: narrowest).width
            #expect(width <= narrowest.width + pixelRounding, "\(textSize): \(width) points wide")
        }
    }
}
