import Foundation
import LinkBridgePresentation

// Checks for the Mac's history store, including migration from the old AppKit app's format.

/// The old app's entry, encoded the way it was (default JSONEncoder).
private struct LegacyMacEntry: Codable {
    let fileName: String
    let bytes: Int64
    let peerName: String
    let isOutgoing: Bool
    let completedAt: Date
}

private func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "checks-\(UUID().uuidString)")!
}

private func sample(_ name: String) -> TransferRecord {
    TransferRecord(fileName: name, peerName: "Pixel 8", bytes: 1, date: Date(), succeeded: true, detail: nil)
}

func runHistoryStoreChecks() {
    section("History store: legacy Mac migration") {
        let defaults = freshDefaults()
        let sent = Date(timeIntervalSince1970: 1_790_000_000), got = Date(timeIntervalSince1970: 1_790_000_500)
        let legacy = [LegacyMacEntry(fileName: "b.zip", bytes: 9, peerName: "Pixel 8", isOutgoing: false, completedAt: got),
                      LegacyMacEntry(fileName: "a.pdf", bytes: 5, peerName: "Pixel 8", isOutgoing: true, completedAt: sent)]
        defaults.set(try JSONEncoder().encode(legacy), forKey: "legacy")
        let store = HistoryStore(defaults: defaults, key: "v2", legacyMacKey: "legacy")
        let records = store.load()
        check(records.map(\.fileName) == ["b.zip", "a.pdf"], "keeps order, newest first")
        check(records.map(\.direction) == [.received, .sent], "maps direction")
        check(records.map(\.date) == [got, sent] && records.allSatisfy(\.succeeded), "keeps dates, marks succeeded")
        check(defaults.data(forKey: "legacy") == nil, "legacy key removed after migrating")
        check(store.load().count == 2, "second load does not duplicate")
    }

    section("History store: unreadable legacy data") {
        let defaults = freshDefaults()
        defaults.set(Data("not json".utf8), forKey: "legacy")
        let store = HistoryStore(defaults: defaults, key: "v2", legacyMacKey: "legacy")
        check(store.load().isEmpty, "garbage gives empty history")
        check(defaults.data(forKey: "legacy") != nil, "garbage legacy data left untouched")
    }

    section("History store: add, limit, remove") {
        let store = HistoryStore(defaults: freshDefaults(), key: "v2", limit: 200)
        for i in 0..<201 { store.add(sample("f\(i)")) }
        let all = store.load()
        check(all.count == 200 && all.first?.fileName == "f200", "capped at 200, newest first")
        let target = all[5]
        let after = store.remove(id: target.id)
        check(after.count == 199 && !after.contains { $0.id == target.id }, "removes exactly that record")
        check(store.load().count == 199, "removal persisted")
    }
}
