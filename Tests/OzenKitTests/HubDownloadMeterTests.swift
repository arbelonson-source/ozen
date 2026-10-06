import Foundation
import Testing
@testable import OzenKit

@Suite("HubDownloadMeter")
struct HubDownloadMeterTests {
    private let root = FileManager.default.temporaryDirectory
        .appendingPathComponent("ozen-hub-\(UUID().uuidString)", isDirectory: true)

    private var folder: URL { root.appendingPathComponent("openai_whisper-small", isDirectory: true) }
    private var partial: URL { root.appendingPathComponent(".cache/huggingface/download/openai_whisper-small", isDirectory: true) }

    private func write(_ bytes: Int, to relativePath: String, in base: URL) throws {
        let url = base.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 7, count: bytes).write(to: url)
    }

    private func meter() -> HubDownloadMeter {
        HubDownloadMeter(folder: folder, partialFolder: partial, totalBytes: 1000)
    }

    @Test("shows the share of the bytes on disk, not the hub's share of files")
    func countsBytes() throws {
        let meter = meter()
        for name in ["analytics/coremldata.bin", "coremldata.bin", "metadata.json", "model.mil"] {
            try write(2, to: "AudioEncoder.mlmodelc/\(name)", in: folder)
        }
        try write(42, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(abs(meter.fraction(reported: 4.2 / 19) - 0.05) < 1e-9)
    }

    @Test("a finished file moving out of the partial folder never moves the bar back")
    func neverBack() throws {
        let meter = meter()
        try write(300, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(abs(meter.fraction(reported: 0.1) - 0.3) < 1e-9)
        try FileManager.default.removeItem(at: partial.appendingPathComponent("AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete"))
        #expect(abs(meter.fraction(reported: 0.1) - 0.3) < 1e-9)
        try write(300, to: "AudioEncoder.mlmodelc/weights/weight.bin", in: folder)
        try write(100, to: "TextDecoder.mlmodelc/weights/weight.bin.9f8e.incomplete", in: partial)
        #expect(abs(meter.fraction(reported: 0.2) - 0.4) < 1e-9)
    }

    @Test("only the files still arriving count in the partial folder")
    func onlyIncomplete() throws {
        let meter = meter()
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.metadata", in: partial)
        try write(100, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(abs(meter.fraction(reported: 0.1) - 0.1) < 1e-9)
    }

    @Test("all the bytes there is not the end; the hub saying so is")
    func endIsTheHubs() throws {
        let meter = meter()
        try write(1004, to: "AudioEncoder.mlmodelc/weights/weight.bin", in: folder)
        #expect(meter.fraction(reported: 18.0 / 19) < 1)
        #expect(meter.fraction(reported: 1) == 1)
    }

    @Test("nothing on disk yet is nothing done")
    func empty() {
        #expect(meter().fraction(reported: 0) == 0)
    }

    @Test("a hub model is measured where WhisperKit puts it; a release model and an unknown one are not")
    func layout() throws {
        let small = try #require(HubDownloadMeter(modelsRoot: root, variant: "small"))
        #expect(small.folder == folder)
        #expect(small.partialFolder == partial)
        #expect(small.totalBytes == 486_000_000)
        #expect(HubDownloadMeter(modelsRoot: root, variant: WhisperModelCatalog.recommendedVariant) == nil)
        #expect(HubDownloadMeter(modelsRoot: root, variant: "not-a-model") == nil)
    }
}
