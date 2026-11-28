import Foundation

public struct FoldRect: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public var maxX: Double { x + width }
    public var maxY: Double { y + height }
}

public struct FoldSplit: Equatable, Sendable {
    public static let minimumCaptionsExtent = 200.0
    public static let minimumControlsHeight = 96.0
    public static let minimumControlsWidth = 320.0

    public let captions: FoldRect
    public let controls: FoldRect

    public static func make(width: Double, height: Double, division: FoldRect?) -> FoldSplit? {
        guard let division, width > 0, height > 0, division.width > 0, division.height > 0 else { return nil }
        if division.height > division.width {
            return sideBySide(width: width, height: height, division: division)
        }
        return stacked(width: width, height: height, division: division)
    }

    private static func stacked(width: Double, height: Double, division: FoldRect) -> FoldSplit? {
        let top = division.y
        let bottom = height - division.maxY
        guard top >= minimumCaptionsExtent, bottom >= minimumControlsHeight else { return nil }
        return FoldSplit(
            captions: FoldRect(x: 0, y: 0, width: width, height: top),
            controls: FoldRect(x: 0, y: division.maxY, width: width, height: bottom)
        )
    }

    private static func sideBySide(width: Double, height: Double, division: FoldRect) -> FoldSplit? {
        let first = FoldRect(x: 0, y: 0, width: division.x, height: height)
        let second = FoldRect(x: division.maxX, y: 0, width: width - division.maxX, height: height)
        let (captions, controls) = second.width >= first.width ? (second, first) : (first, second)
        guard captions.width >= minimumCaptionsExtent, controls.width >= minimumControlsWidth else { return nil }
        return FoldSplit(captions: captions, controls: controls)
    }

    public var captionsInsets: (left: Double, top: Double, right: Double, bottom: Double) {
        let width = max(captions.maxX, controls.maxX)
        let height = max(captions.maxY, controls.maxY)
        return (captions.x, captions.y, width - captions.maxX, height - captions.maxY)
    }

    public var controlsInsets: (left: Double, right: Double) {
        let width = max(captions.maxX, controls.maxX)
        return (controls.x, width - controls.maxX)
    }
}
