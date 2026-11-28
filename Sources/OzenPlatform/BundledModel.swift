import CoreML
import Foundation

enum BundledModel {
    static func locate(_ name: String) -> URL? {
        Bundle.module.url(forResource: name, withExtension: "mlmodelc")
            ?? Bundle.module.url(forResource: name, withExtension: "mlpackage")
    }

    static func compiled(_ url: URL) throws -> URL {
        guard url.pathExtension == "mlpackage" else { return url }
        let target = try cacheFolder(for: url).appendingPathComponent(url.deletingPathExtension().lastPathComponent + ".mlmodelc", isDirectory: true)
        if FileManager.default.fileExists(atPath: target.path) { return target }
        let temporary = try MLModel.compileModel(at: url)
        defer { try? FileManager.default.removeItem(at: temporary) }
        do {
            try FileManager.default.moveItem(at: temporary, to: target)
        } catch {
            guard FileManager.default.fileExists(atPath: target.path) else { throw error }
        }
        return target
    }

    private static func cacheFolder(for source: URL) throws -> URL {
        let manifest = source.appendingPathComponent("Manifest.json").path
        let stamp = (try? FileManager.default.attributesOfItem(atPath: manifest)[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        let folder = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("BundledModels", isDirectory: true)
            .appendingPathComponent("\(version)-\(Int(stamp))", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}
