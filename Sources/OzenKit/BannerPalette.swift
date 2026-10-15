import Foundation

public enum BannerPalette {
    public typealias Components = (red: Double, green: Double, blue: Double)

    public static let orange: Components = (red: 0.66, green: 0.28, blue: 0)
    public static let red: Components = (red: 0.75, green: 0, blue: 0)
    public static let fillAlpha: Double = 0.92
    public static let subtitleOpacity: Double = 1.0
    public static let white: Components = (red: 1, green: 1, blue: 1)

    public static func subtitleContrast(
        tint: Components,
        subtitleOpacity: Double = BannerPalette.subtitleOpacity,
        over background: Components
    ) -> Double {
        let fill: Components = (
            red: tint.red * fillAlpha + background.red * (1 - fillAlpha),
            green: tint.green * fillAlpha + background.green * (1 - fillAlpha),
            blue: tint.blue * fillAlpha + background.blue * (1 - fillAlpha)
        )
        return ContrastRatio.ratio(foreground: white, alpha: subtitleOpacity, overBackground: fill)
    }
}
