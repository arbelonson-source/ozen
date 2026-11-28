import Foundation
import Testing
@testable import OzenKit

@Suite("FoldSplit")
struct FoldSplitTests {
    private func division(x: Double = 0, y: Double = 0, width: Double, height: Double) -> FoldRect {
        FoldRect(x: x, y: y, width: width, height: height)
    }

    @Test("no fold, no split: the screen stays one piece")
    func noDivision() {
        #expect(FoldSplit.make(width: 800, height: 600, division: nil) == nil)
    }

    @Test("a fold with no width or height, as when the phone lies flat, splits nothing")
    func flatDivision() {
        #expect(FoldSplit.make(width: 800, height: 600, division: division(x: 400, width: 0, height: 600)) == nil)
        #expect(FoldSplit.make(width: 800, height: 600, division: division(y: 300, width: 800, height: 0)) == nil)
    }

    @Test("a fold across the screen puts the captions above it and the buttons below it")
    func horizontalFold() throws {
        let split = try #require(FoldSplit.make(width: 800, height: 700, division: division(y: 400, width: 800, height: 40)))
        #expect(split.captions == FoldRect(x: 0, y: 0, width: 800, height: 400))
        #expect(split.controls == FoldRect(x: 0, y: 440, width: 800, height: 260))
        #expect(split.captionsInsets.bottom == 300)
        #expect(split.captionsInsets.top == 0)
        #expect(split.controlsInsets.leading == 0)
        #expect(split.controlsInsets.trailing == 0)
    }

    @Test("a fold down the screen puts the captions on the wider side")
    func verticalFoldWiderRight() throws {
        let split = try #require(FoldSplit.make(width: 900, height: 700, division: division(x: 340, width: 40, height: 700)))
        #expect(split.captions == FoldRect(x: 380, y: 0, width: 520, height: 700))
        #expect(split.controls == FoldRect(x: 0, y: 0, width: 340, height: 700))
        #expect(split.captionsInsets.leading == 380)
        #expect(split.captionsInsets.trailing == 0)
        #expect(split.controlsInsets.leading == 0)
        #expect(split.controlsInsets.trailing == 560)
    }

    @Test("a fold down the screen with a wider left side puts the captions on the left")
    func verticalFoldWiderLeft() throws {
        let split = try #require(FoldSplit.make(width: 900, height: 700, division: division(x: 520, width: 40, height: 700)))
        #expect(split.captions == FoldRect(x: 0, y: 0, width: 520, height: 700))
        #expect(split.controls == FoldRect(x: 560, y: 0, width: 340, height: 700))
        #expect(split.captionsInsets.trailing == 380)
        #expect(split.controlsInsets.leading == 560)
    }

    @Test("two equal sides give the captions the right one")
    func equalSides() throws {
        let split = try #require(FoldSplit.make(width: 840, height: 700, division: division(x: 400, width: 40, height: 700)))
        #expect(split.captions.x == 440)
        #expect(split.controls.x == 0)
    }

    @Test("a half too small for the captions is not split: the screen stays one piece")
    func captionsHalfTooSmall() {
        #expect(FoldSplit.make(width: 800, height: 700, division: division(y: 150, width: 800, height: 40)) == nil)
        #expect(FoldSplit.make(width: 400, height: 700, division: division(x: 180, width: 40, height: 700)) == nil)
    }

    @Test("a half too small for the buttons is not split")
    func controlsHalfTooSmall() {
        #expect(FoldSplit.make(width: 800, height: 700, division: division(y: 600, width: 800, height: 40)) == nil)
        #expect(FoldSplit.make(width: 800, height: 700, division: division(x: 500, width: 40, height: 700)) == nil)
    }
}
