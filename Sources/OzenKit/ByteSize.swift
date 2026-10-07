import Foundation

public enum ByteSize {
    public static func text(_ bytes: Int64) -> String {
        let kilobytes = (Double(bytes) / 1_000).rounded(.up)
        if kilobytes < 1_000 { return "\(Int(kilobytes))\u{00A0}KB" }
        let megabytes = (Double(bytes) / Double(StorageSpaceGate.bytesPerMegabyte)).rounded()
        if megabytes >= 1_000 { return String(format: "%.1f\u{00A0}GB", megabytes / 1_000) }
        return "\(Int(megabytes))\u{00A0}MB"
    }
}
