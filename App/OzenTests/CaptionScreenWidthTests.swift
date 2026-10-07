import Testing
import SwiftUI
import UIKit
@testable import Ozen
@testable import OzenKit
import Foundation

@Suite("Caption screen width")
@MainActor
struct CaptionScreenWidthTests {
    private let pixelRounding = 0.5

    private func widths(proposing size: CGSize) -> [(DynamicTypeSize, CGFloat)] {
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
        return [DynamicTypeSize.large, .xxxLarge, .accessibility5].map { textSize in
            let screen = UIHostingController(rootView: LiveCaptionView(viewModel: viewModel).environment(\.dynamicTypeSize, textSize))
            return (textSize, screen.sizeThatFits(in: size).width)
        }
    }

    @Test("with lines on screen, the caption screen is no wider than an iPhone 13 mini or SE, at any text size")
    func fitsTheNarrowestPhones() {
        let narrowest = CGSize(width: 375, height: 667)
        for (textSize, width) in widths(proposing: narrowest) {
            #expect(width <= narrowest.width + pixelRounding, "\(textSize): \(width) points wide")
        }
    }

    @Test("with lines on screen, the caption screen is no wider than an iPad's narrowest Split View, at any text size")
    func fitsTheNarrowestSplitView() {
        let narrowest = CGSize(width: 320, height: 700)
        for (textSize, width) in widths(proposing: narrowest) {
            #expect(width <= narrowest.width + pixelRounding, "\(textSize): \(width) points wide")
        }
    }
}
