import Foundation

/// Saves transfer history in UserDefaults, newest first.
public final class HistoryStore {
    private let defaults: UserDefaults
    private let key: String
    private let legacyMacKey: String?
    private let limit: Int

    /// `legacyMacKey` names history saved by the old AppKit Mac app; it is migrated on first load.
    public init(defaults: UserDefaults = .standard, key: String, legacyMacKey: String? = nil, limit: Int = 200) {
        self.defaults = defaults
        self.key = key
        self.legacyMacKey = legacyMacKey
        self.limit = limit
    }

    public func load() -> [TransferRecord] {
        migrateLegacyIfNeeded()
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([TransferRecord].self, from: data)) ?? []
    }

    @discardableResult
    public func add(_ record: TransferRecord) -> [TransferRecord] {
        let records = Array(([record] + load()).prefix(limit))
        save(records)
        return records
    }

    @discardableResult
    public func remove(id: UUID) -> [TransferRecord] {
        let records = load().filter { $0.id != id }
        save(records)
        return records
    }

    @discardableResult
    private func save(_ records: [TransferRecord]) -> Bool {
        guard let data = try? JSONEncoder().encode(records) else { return false }
        defaults.set(data, forKey: key)
        return true
    }

    private struct LegacyMacEntry: Decodable {
        let fileName: String
        let bytes: Int64
        let peerName: String
        let isOutgoing: Bool
        let completedAt: Date
    }

    /// Converts the old app's entries, and deletes them only once the converted history reads back.
    private func migrateLegacyIfNeeded() {
        guard let legacyMacKey, let data = defaults.data(forKey: legacyMacKey),
              let entries = try? JSONDecoder().decode([LegacyMacEntry].self, from: data) else { return }
        let existing = (defaults.data(forKey: key)).flatMap { try? JSONDecoder().decode([TransferRecord].self, from: $0) } ?? []
        let migrated = entries.map {
            TransferRecord(fileName: $0.fileName, peerName: $0.peerName, bytes: $0.bytes, date: $0.completedAt,
                           succeeded: true, detail: nil, direction: $0.isOutgoing ? .sent : .received)
        }
        let records = Array((existing + migrated).prefix(limit))
        guard save(records), let saved = defaults.data(forKey: key),
              (try? JSONDecoder().decode([TransferRecord].self, from: saved))?.count == records.count else { return }
        defaults.removeObject(forKey: legacyMacKey)
    }
}
