import Foundation
import Testing
@testable import OzenKit

@Suite("BannerContrast")
struct BannerContrastTests {
    private let black: BannerPalette.Components = (red: 0, green: 0, blue: 0)
    private let white: BannerPalette.Components = (red: 1, green: 1, blue: 1)

    @Test("the second line of an orange or red banner reads at 4.5:1 over white and over black")
    func subtitleReadable() {
        let tints = [BannerPalette.orange, BannerPalette.red]
        for tint in tints {
            for background in [white, black] {
                let ratio = BannerPalette.subtitleContrast(tint: tint, over: background)
                #expect(ratio >= 4.5)
            }
        }
    }

    @Test("the worst case keeps a margin above 4.5:1")
    func margin() {
        for tint in [BannerPalette.orange, BannerPalette.red] {
            #expect(BannerPalette.subtitleContrast(tint: tint, over: white) >= 4.8)
        }
    }

    @Test("the old 85 percent second line did not reach 4.5:1 on orange over white")
    func oldOpacityFailed() {
        let ratio = BannerPalette.subtitleContrast(tint: BannerPalette.orange, subtitleOpacity: 0.85, over: white)
        #expect(ratio < 4.5)
    }

    @Test("the banner keeps its fill and a fully opaque second line")
    func constants() {
        #expect(BannerPalette.fillAlpha == 0.92)
        #expect(BannerPalette.subtitleOpacity == 1.0)
    }
}
