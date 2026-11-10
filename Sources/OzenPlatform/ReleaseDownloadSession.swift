import Foundation

/// The one URLSession every release download goes through. On the phone it
/// is a background session: iOS keeps fetching the model after she leaves
/// the app or locks the phone, and wakes the app when a file has arrived.
/// The download used to run in a session of the app's own, which stopped
/// as soon as the app left the screen; a notice asked her to come back.
///
/// Each task carries where its bytes go (`Placement`, in the task's
/// description). A file that arrives while nothing waits for it, after
/// iOS ended the app and woke it again for the download, still lands in
/// place, and the next attempt finds it whole. A download still running
/// when the app opens again is joined rather than started a second time.
public final class ReleaseDownloadSession: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    public static let identifier = (Bundle.main.bundleIdentifier ?? "ozen") + ".release-downloads"

    public static let shared = ReleaseDownloadSession(configuration: {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        #if os(iOS)
        configuration.sessionSendsLaunchEvents = true
        #endif
        configuration.isDiscretionary = false
        return configuration
    }())

    enum Failure: Error, Equatable {
        /// The file on disk is no longer the size the range was asked
        /// from: appending would put the bytes in the wrong place.
        case misplaced
    }

    /// Where a task's bytes go: appended to `destination` when the server
    /// sends the rest of the file from `offset`, in its place when it sends
    /// the whole file.
    struct Placement: Codable, Hashable {
        var source: String
        var destination: String
        var offset: Int64
    }

    private final class Transfer {
        var placement: Placement
        var received: @Sendable (Int64) -> Void = { _ in }
        /// Bytes told to `received` so far, so a download joined part way,
        /// or resumed, reports what it already holds.
        var reported: Int64 = 0
        var failure: Error?
        var waiters: [CheckedContinuation<Void, Error>] = []
        /// The task's end when nobody was waiting yet: a download cancelled
        /// as it starts, or one that finished while the app was away.
        var outcome: Result<Void, Error>?

        init(placement: Placement) {
            self.placement = placement
        }
    }

    private let lock = NSLock()
    private var session: URLSession!
    private var transfers: [Int: Transfer] = [:]
    /// What a cut-off download already fetched, kept by iOS: the next
    /// attempt at the same file carries on from there.
    private var resumeData: [Placement: Data] = [:]
    private var eventWaiters: [CheckedContinuation<Void, Never>] = []
    private var eventsDelivered = false

    init(configuration: URLSessionConfiguration) {
        super.init()
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    func fetch(_ request: URLRequest, placement: Placement, received: @escaping @Sendable (Int64) -> Void) async throws {
        let task = await start(request, placement: placement, received: received)
        do {
            try await withTaskCancellationHandler {
                try await wait(for: task)
            } onCancel: {
                task.cancel { [weak self] data in
                    guard let self, let data, let placement = Self.placement(of: task) else { return }
                    lock.withLock { resumeData[placement] = data }
                }
            }
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    /// Joins a download of the same file still running from before, or
    /// carries on from what a cut-off one fetched, or starts afresh.
    func start(_ request: URLRequest, placement: Placement, received: @escaping @Sendable (Int64) -> Void) async -> URLSessionDownloadTask {
        let running = await withCheckedContinuation { (continuation: CheckedContinuation<[URLSessionTask], Never>) in
            session.getAllTasks { continuation.resume(returning: $0) }
        }
        let downloads = running.compactMap { $0 as? URLSessionDownloadTask }
        let (joined, stale) = Self.sort(downloads.map { (Self.placement(of: $0), $0.state != .completed && $0.state != .canceling) }, for: placement)
        stale.forEach { downloads[$0].cancel() }
        let task: URLSessionDownloadTask
        if let joined {
            task = downloads[joined]
        } else if let data = lock.withLock({ resumeData.removeValue(forKey: placement) }) {
            task = session.downloadTask(withResumeData: data)
        } else {
            task = session.downloadTask(with: request)
        }
        task.taskDescription = Self.describe(placement)
        lock.withLock {
            let transfer = tracked(task) ?? Transfer(placement: placement)
            // Joining a download nobody was waiting for: its next report
            // counts everything it fetched so far, not just what is new.
            if transfer.waiters.isEmpty { transfer.reported = 0 }
            transfer.received = received
            transfers[task.taskIdentifier] = transfer
        }
        return task
    }

    /// Of the downloads the session already has, the one to join (same
    /// file, same place, still running) and the ones to cancel: any other
    /// download into the same file would race it.
    static func sort(_ existing: [(placement: Placement?, running: Bool)], for wanted: Placement) -> (join: Int?, cancel: [Int]) {
        var join: Int?
        var cancel: [Int] = []
        for (index, download) in existing.enumerated() where download.placement?.destination == wanted.destination {
            if join == nil, download.running, download.placement == wanted {
                join = index
            } else if download.running {
                cancel.append(index)
            }
        }
        return (join, cancel)
    }

    func wait(for task: URLSessionTask) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let ended: Result<Void, Error>? = lock.withLock {
                guard let transfer = transfers[task.taskIdentifier] else { return .failure(CancellationError()) }
                if let outcome = transfer.outcome {
                    transfers[task.taskIdentifier] = nil
                    return outcome
                }
                transfer.waiters.append(continuation)
                return nil
            }
            if let ended {
                continuation.resume(with: ended)
            } else {
                task.resume()
            }
        }
    }

    /// Lets a test stand in for a task the session was given.
    func track(_ task: URLSessionTask, placement: Placement) {
        lock.withLock { transfers[task.taskIdentifier] = Transfer(placement: placement) }
    }

    /// For the app's launch in the background: returns once iOS has handed
    /// over every event of the downloads that ran while it was away, and
    /// the next file, if there is one, has been asked for.
    public func deliverBackgroundEvents() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let delivered = lock.withLock {
                if eventsDelivered {
                    eventsDelivered = false
                    return true
                }
                eventWaiters.append(continuation)
                return false
            }
            if delivered { continuation.resume() }
        }
        for _ in 0..<30 {
            if lock.withLock({ transfers.values.contains { !$0.waiters.isEmpty } }) { return }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didResumeAtOffset fileOffset: Int64, expectedTotalBytes: Int64) {
        report(downloadTask, total: fileOffset)
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        report(downloadTask, total: totalBytesWritten)
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        report(downloadTask)
        guard let placement = lock.withLock({ tracked(downloadTask)?.placement }) else { return }
        do {
            try Self.place(location, status: (downloadTask.response as? HTTPURLResponse)?.statusCode, at: placement)
        } catch {
            lock.withLock { tracked(downloadTask)?.failure = error }
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let waiting: ([CheckedContinuation<Void, Error>], Result<Void, Error>)? = lock.withLock {
            guard let transfer = tracked(task) else { return nil }
            if let data = (error as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                resumeData[transfer.placement] = data
            }
            let ended: Result<Void, Error> = (transfer.failure ?? error).map { .failure($0) } ?? .success(())
            guard !transfer.waiters.isEmpty else {
                transfer.outcome = ended
                return nil
            }
            transfers[task.taskIdentifier] = nil
            return (transfer.waiters, ended)
        }
        if let (waiters, ended) = waiting {
            waiters.forEach { $0.resume(with: ended) }
        }
    }

    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let waiting: [CheckedContinuation<Void, Never>] = lock.withLock {
            defer { eventWaiters.removeAll() }
            eventsDelivered = eventWaiters.isEmpty
            return eventWaiters
        }
        waiting.forEach { $0.resume() }
    }

    /// Progress so far, told as what came since the last report. A whole
    /// file sent back when part of it was asked for (the server, or a proxy,
    /// ignored the range) starts the count over, and the file with it; that
    /// is checked again as the file arrives, in case its first bytes came
    /// before the server's answer was read.
    private func report(_ task: URLSessionDownloadTask, total: Int64? = nil) {
        let status = (task.response as? HTTPURLResponse)?.statusCode
        let told: (@Sendable (Int64) -> Void, [Int64])? = lock.withLock {
            guard let transfer = tracked(task), status == nil || status == 200 || status == 206 else { return nil }
            var amounts: [Int64] = []
            if status == 200, transfer.placement.offset > 0 {
                amounts.append(-transfer.placement.offset)
                transfer.placement.offset = 0
                task.taskDescription = Self.describe(transfer.placement)
            }
            if let total, total > transfer.reported {
                amounts.append(total - transfer.reported)
                transfer.reported = total
            }
            return (transfer.received, amounts)
        }
        guard let (received, amounts) = told else { return }
        amounts.forEach(received)
    }

    /// The task's transfer, made from its description when the app was
    /// ended and opened again since it started. Called with the lock held.
    private func tracked(_ task: URLSessionTask) -> Transfer? {
        if let transfer = transfers[task.taskIdentifier] { return transfer }
        guard let placement = Self.placement(of: task) else { return nil }
        let transfer = Transfer(placement: placement)
        transfers[task.taskIdentifier] = transfer
        return transfer
    }

    static func describe(_ placement: Placement) -> String? {
        (try? JSONEncoder().encode(placement)).map { String(decoding: $0, as: UTF8.self) }
    }

    static func placement(of task: URLSessionTask) -> Placement? {
        task.taskDescription.flatMap { try? JSONDecoder().decode(Placement.self, from: Data($0.utf8)) }
    }

    /// Puts a finished download where it belongs. A 206 is the rest of the
    /// file from `offset` and is appended, but only onto a file still that
    /// long; a 200 is the whole file and replaces whatever was there.
    /// Anything else leaves the file on disk as it was.
    static func place(_ downloaded: URL, status: Int?, at placement: Placement) throws {
        guard let status else { throw URLSessionReleaseFileFetcher.Failure.notHTTP }
        let destination = URL(fileURLWithPath: placement.destination)
        let fileManager = FileManager.default
        switch status {
        case 206 where placement.offset > 0:
            let size = (try? fileManager.attributesOfItem(atPath: destination.path)[.size] as? NSNumber)?.int64Value
            guard size == placement.offset else { throw Failure.misplaced }
            let output = try FileHandle(forWritingTo: destination)
            defer { try? output.close() }
            try output.seekToEnd()
            let input = try FileHandle(forReadingFrom: downloaded)
            defer { try? input.close() }
            while let chunk = try input.read(upToCount: URLSessionReleaseFileFetcher.writeBatch), !chunk.isEmpty {
                try output.write(contentsOf: chunk)
            }
        case 200, 206:
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: downloaded, to: destination)
        default:
            throw URLSessionReleaseFileFetcher.Failure.status(status)
        }
    }
}
