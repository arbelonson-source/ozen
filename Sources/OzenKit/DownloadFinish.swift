import Foundation

public enum DownloadFinish {
    public static func finish(_ folder: URL) throws {
        try Task.checkCancellation()
        try? ModelFolderInspector.markComplete(folder)
    }
}
