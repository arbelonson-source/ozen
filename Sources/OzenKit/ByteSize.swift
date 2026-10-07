import Foundation

public enum ByteSize {
    public static func text(_ bytes: Int64) -> String {
        let kilobytes = (Double(bytes) / 1_000).rounded(.up)
        if kilobytes < 1_000 { return "\(Int(kilobytes)) KB" }
        let megabytes = (Double(bytes) / Double(StorageSpaceGate.bytesPerMegabyte)).rounded()
        if megabytes >= 1_000 { return String(format: "%.1f GB", megabytes / 1_000) }
        return "\(Int(megabytes)) MB"
    }
}
