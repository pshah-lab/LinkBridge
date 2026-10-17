import Foundation
@testable import LinkBridgeIOS
import Testing

private func record(succeeded: Bool, bookmark: Data? = nil) -> TransferRecord {
    TransferRecord(fileName: "photo.jpg", peerName: "Mac", bytes: 1_200_000, date: Date(),
                   succeeded: succeeded, detail: succeeded ? nil : "Connection lost", bookmark: bookmark)
}

@Test func successfulRow() {
    let row = HistoryRowContent(record(succeeded: true))
    #expect(row.title == "photo.jpg")
    #expect(row.detail.hasPrefix("To Mac · 1.2 MB · "))
    #expect(row.symbol == "arrow.up.circle.fill")
    #expect(row.accent == .signal)
    #expect(row.failureReason == nil)
    #expect(!row.canRetry)
}

@Test func failedRowWithBookmarkCanRetry() {
    let row = HistoryRowContent(record(succeeded: false, bookmark: Data([1])))
    #expect(row.canRetry)
    #expect(row.accent == .attention)
    #expect(row.symbol == "exclamationmark.circle.fill")
    #expect(row.failureReason == "Connection lost")
}

@Test func failedRowWithoutBookmarkCannotRetry() {
    #expect(!HistoryRowContent(record(succeeded: false)).canRetry)
}

@Test func legacyHistoryJSONDecodes() throws {
    let json = #"[{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","fileName":"a.txt","peerName":"Mac","bytes":3,"date":0,"succeeded":true,"detail":null}]"#
    let records = try JSONDecoder().decode([TransferRecord].self, from: Data(json.utf8))
    #expect(records.count == 1)
    #expect(records[0].bookmark == nil)
    #expect(records[0].fileName == "a.txt")
}
