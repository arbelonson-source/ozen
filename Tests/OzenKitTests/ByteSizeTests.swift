import Foundation
import Testing
@testable import OzenKit

@Suite("Sizes are written the same way in every language")
struct ByteSizeTests {
    @Test("kilobytes, megabytes and gigabytes, decimal, as the model list writes them")
    func text() {
        #expect(ByteSize.text(0) == "0 KB")
        #expect(ByteSize.text(400) == "1 KB")
        #expect(ByteSize.text(640_000) == "640 KB")
        #expect(ByteSize.text(819_200_000) == "819 MB")
        #expect(ByteSize.text(999_400_000) == "999 MB")
        #expect(ByteSize.text(999_600_000) == "1.0 GB")
        #expect(ByteSize.text(1_619_000_000) == "1.6 GB")
        for option in WhisperModelCatalog.options {
            #expect(ByteSize.text(Int64(option.sizeMB) * StorageSpaceGate.bytesPerMegabyte) == option.sizeLabel, "\(option.variant)")
        }
    }

    @Test("no size goes through iOS's formatter, which writes its units in the phone's language rather than Ozen's")
    func noPhoneLanguageFormatter() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var checked = 0
        for folder in ["App", "Sources"] {
            let files = FileManager.default.enumerator(at: root.appendingPathComponent(folder), includingPropertiesForKeys: nil)
            while let file = files?.nextObject() as? URL {
                guard file.pathExtension == "swift" else { continue }
                let text = try String(contentsOf: file, encoding: .utf8)
                #expect(!text.contains("ByteCountFormatter"), "\(file.lastPathComponent)")
                checked += 1
            }
        }
        #expect(checked > 100)
    }
}
