import Foundation
import Testing
@testable import OzenKit

@Suite("ModelDiskSpace")
struct ModelDiskSpaceTests {
    private let root = FileManager.default.temporaryDirectory
        .appendingPathComponent("ozen-space-\(UUID().uuidString)", isDirectory: true)

    private var folder: URL { root.appendingPathComponent("openai_whisper-small", isDirectory: true) }
    private var partial: URL { root.appendingPathComponent(".cache/huggingface/download/openai_whisper-small", isDirectory: true) }
    private var otherPartial: URL { root.appendingPathComponent(".cache/huggingface/download/openai_whisper-base", isDirectory: true) }

    private func write(_ bytes: Int, to relativePath: String, in base: URL) throws {
        let url = base.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 7, count: bytes).write(to: url)
    }

    private func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    @Test("the half-downloaded file sits where the hub writes it, as the download meter expects")
    func layout() throws {
        #expect(ModelDiskSpace.partialFolder(modelsRoot: root, folderName: "openai_whisper-small") == partial)
        #expect(try #require(HubDownloadMeter(modelsRoot: root, variant: "small")).partialFolder == partial)
    }

    @Test("a download cut off before its first file finished still shows as interrupted")
    func cutOffEarly() throws {
        #expect(ModelDiskSpace.state(of: folder, partialFolder: partial) == .missing)
        try write(300, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(ModelDiskSpace.state(of: folder, partialFolder: partial) == .partial)
    }

    @Test("the hub's notes left by an earlier delete don't make a model look interrupted")
    func notesAreNotADownload() throws {
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.metadata", in: partial)
        #expect(ModelDiskSpace.state(of: folder, partialFolder: partial) == .missing)
    }

    @Test("a whole model stays whole whatever is left in the partial folder")
    func wholeStaysWhole() throws {
        for bundle in ModelFolderInspector.requiredBundles {
            try write(1, to: "\(bundle)/coremldata.bin", in: folder)
        }
        try ModelFolderInspector.markComplete(folder)
        try write(300, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(ModelDiskSpace.state(of: folder, partialFolder: partial) == .verified)
    }

    @Test("a model's size counts the file still arriving")
    func sizeCountsArriving() throws {
        try write(300, to: "AudioEncoder.mlmodelc/coremldata.bin", in: folder)
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        #expect(ModelDiskSpace.size(of: folder, partialFolder: partial) == 500)
    }

    @Test("deleting a model also deletes the file it was downloading, and no other model's")
    func deleteTakesPartial() throws {
        try write(300, to: "AudioEncoder.mlmodelc/coremldata.bin", in: folder)
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        try write(100, to: "AudioEncoder.mlmodelc/weights/weight.bin.9f8e.incomplete", in: otherPartial)
        try ModelDiskSpace.delete(folder, partialFolder: partial)
        #expect(!exists(folder))
        #expect(!exists(partial))
        #expect(exists(otherPartial))
        #expect(ModelDiskSpace.state(of: folder, partialFolder: partial) == .missing)
    }

    @Test("deleting a model with only a half-downloaded file frees it, and deleting nothing is fine")
    func deleteOnlyPartial() throws {
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        try ModelDiskSpace.delete(folder, partialFolder: partial)
        #expect(!exists(partial))
        try ModelDiskSpace.delete(folder, partialFolder: partial)
    }

    @Test("the total on disk counts the hidden partial downloads")
    func totalCountsHidden() throws {
        try write(300, to: "AudioEncoder.mlmodelc/coremldata.bin", in: folder)
        try write(200, to: "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", in: partial)
        try write(100, to: "AudioEncoder.mlmodelc/weights/weight.bin.9f8e.incomplete", in: otherPartial)
        #expect(ModelDiskSpace.bytes(in: root) == 600)
    }
}
