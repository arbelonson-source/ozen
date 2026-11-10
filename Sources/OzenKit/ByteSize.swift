import Foundation

public enum ByteSize {
    public static func text(_ bytes: Int64) -> String {
        let kilobytes = (Double(bytes) / 1_000).rounded(.up)
        if kilobytes < 1_000 { return joined("\(Int(kilobytes))", .kilo) }
        let megabytes = (Double(bytes) / Double(StorageSpaceGate.bytesPerMegabyte)).rounded()
        if megabytes >= 1_000 { return joined(NumberText.decimal(megabytes / 1_000, places: 1), .giga) }
        return self.megabytes(Int(megabytes))
    }

    public static func megabytes(_ megabytes: Int) -> String {
        joined("\(megabytes)", .mega)
    }

    public static func roundedUp(megabytes: Int) -> String {
        guard megabytes >= 1_000 else { return self.megabytes(megabytes) }
        let tenths = (megabytes + 99) / 100
        return joined(NumberText.decimal(Double(tenths) / 10, places: 1), .giga)
    }

    private enum Scale { case kilo, mega, giga }

    private static func joined(_ number: String, _ scale: Scale) -> String {
        let units = units(in: Localization.language)
        let unit = switch scale {
        case .kilo: units.kilo
        case .mega: units.mega
        case .giga: units.giga
        }
        return "\(number)\u{00A0}\(unit)"
    }

    private static func units(in language: UILanguage) -> (kilo: String, mega: String, giga: String) {
        switch language {
        case .french: ("ko", "Mo", "Go")
        case .russian, .ukrainian: ("КБ", "МБ", "ГБ")
        case .arabic: ("كيلوبايت", "ميغابايت", "غيغابايت")
        case .hebrew, .english, .german, .spanish, .portuguese, .hindi, .amharic, .chineseSimplified: ("KB", "MB", "GB")
        }
    }
}
