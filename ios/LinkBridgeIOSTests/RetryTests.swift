import Foundation
@testable import LinkBridgeIOS
import Testing

@MainActor
@Test func retryWithUnresolvableBookmarkReportsMissingFile() {
    let model = LinkBridgeModel()
    let record = TransferRecord(fileName: "gone.txt", peerName: "Mac", bytes: 1, date: Date(),
                                succeeded: false, detail: "x", bookmark: Data([0x01, 0x02, 0x03]))
    model.retry(record)
    #expect(model.lastError == "That file is no longer available")
    #expect(model.sendPhase == .idle)
}
