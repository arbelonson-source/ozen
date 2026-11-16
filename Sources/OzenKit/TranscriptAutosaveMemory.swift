import Foundation

/// Counts, per conversation file, the writes any store on one folder has made.
/// An autosave memory trusts its entry only while the count is what it was when
/// the entry was made: a rename through another store to a title of the same
/// length, within the clock's resolution, leaves the file's date and size as
/// they were, and the count is then the only thing that shows the change.
final class TranscriptFolderEdits: @unchecked Sendable {
    private let lock = NSLock()
    private var epoch = 0
    private var counts: [String: Int] = [:]

    func version(of key: String) -> (epoch: Int, count: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (epoch, counts[key] ?? 0)
    }

    func bump(_ key: String) {
        lock.lock()
        counts[key, default: 0] += 1
        lock.unlock()
    }

    func bumpAll() {
        lock.lock()
        epoch += 1
        lock.unlock()
    }
}

/// What the last save of each recent conversation wrote (its name and the
/// engine, microphone and language of its first save), with the file's date and size
/// then, and when its History caches were last rebuilt. Each
/// `TranscriptHistoryStore` has its own, which stores on one folder keep
/// honest through a shared `TranscriptFolderEdits`; a file changed since, by
/// anything, no longer matches its date and size and is read again.
final class TranscriptAutosaveMemory: @unchecked Sendable {
    struct Remembered {
        let title: String?
        let engine: TranscriptionEngineKind
        let modelVariant: String?
        let inputName: String?
        let languageCode: String?
        let utcOffsetSeconds: Int?
        let modifiedAt: Date
        let size: Int
        let version: (epoch: Int, count: Int)
    }

    private let edits: TranscriptFolderEdits
    private let lock = NSLock()
    private var entries: [String: Remembered] = [:]
    private var order: [String] = []
    private var lastRefresh: [String: Date] = [:]
    private var readCount = 0
    private var refreshCount = 0

    init(edits: TranscriptFolderEdits = TranscriptFolderEdits()) {
        self.edits = edits
    }

    var reads: Int { locked { readCount } }
    var refreshes: Int { locked { refreshCount } }

    func countRead() { locked { readCount += 1 } }
    func countRefresh() { locked { refreshCount += 1 } }

    func remembered(_ url: URL) -> Remembered? {
        let key = url.lastPathComponent
        let stamp = Self.stamp(of: url)
        return locked {
            guard let entry = entries[key] else { return nil }
            let version = edits.version(of: key)
            guard let stamp, stamp.modifiedAt == entry.modifiedAt, stamp.size == entry.size,
                  version.epoch == entry.version.epoch, version.count == entry.version.count else {
                drop(key)
                return nil
            }
            touch(key)
            return entry
        }
    }

    func remember(_ record: TranscriptSessionRecord, at url: URL) {
        guard let stamp = Self.stamp(of: url) else { return }
        let key = url.lastPathComponent
        let entry = Remembered(
            title: record.title,
            engine: record.engine,
            modelVariant: record.modelVariant,
            inputName: record.inputName,
            languageCode: record.languageCode,
            utcOffsetSeconds: record.utcOffsetSeconds,
            modifiedAt: stamp.modifiedAt,
            size: stamp.size,
            version: edits.version(of: key)
        )
        locked {
            entries[key] = entry
            touch(key)
            while order.count > TranscriptHistoryStore.rememberedConversations {
                drop(order[0])
            }
        }
    }

    func forget(_ url: URL) {
        edits.bump(url.lastPathComponent)
        locked { drop(url.lastPathComponent) }
    }

    func forgetAll() {
        edits.bumpAll()
        locked {
            entries.removeAll()
            order.removeAll()
        }
    }

    func cacheRefreshDue(_ url: URL, at now: Date) -> Bool {
        let key = url.lastPathComponent
        return locked {
            if let last = lastRefresh[key] {
                let age = now.timeIntervalSince(last)
                if age >= 0, age < TranscriptHistoryStore.cacheRefreshInterval { return false }
            }
            if lastRefresh.count >= TranscriptHistoryStore.rememberedConversations * 4 {
                lastRefresh.removeAll()
            }
            lastRefresh[key] = now
            return true
        }
    }

    private func touch(_ key: String) {
        order.removeAll { $0 == key }
        order.append(key)
    }

    private func drop(_ key: String) {
        entries[key] = nil
        order.removeAll { $0 == key }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private static func stamp(of url: URL) -> (modifiedAt: Date, size: Int)? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modifiedAt = attributes[.modificationDate] as? Date,
              let size = (attributes[.size] as? NSNumber)?.intValue
        else { return nil }
        return (modifiedAt, size)
    }
}
