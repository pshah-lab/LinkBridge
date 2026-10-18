import Foundation
@testable import LinkBridgeIOS
import Testing

private func item(_ id: UUID, sendable: Bool) -> DeviceListItem {
    DeviceListItem(id: id.uuidString, deviceId: id, name: "Mac", platform: .mac, isPaired: true,
                   isNearby: sendable, isSendable: sendable, canPair: false, subtitle: "")
}

@Test func preferredDeviceWinsWhenSendable() {
    let a = UUID(), b = UUID()
    let picked = DeviceSelection.resolve(current: a, preferred: b,
                                         items: [item(a, sendable: true), item(b, sendable: true)], isSending: false)
    #expect(picked == b)
}

@Test func emptyListKeepsCurrentSelection() {
    // Backgrounding stops discovery and reports no peers; the user's pick must survive.
    let b = UUID()
    #expect(DeviceSelection.resolve(current: b, preferred: b, items: [], isSending: false) == b)
}

@Test func sendingNeverChangesSelection() {
    let a = UUID(), b = UUID()
    let picked = DeviceSelection.resolve(current: b, preferred: b,
                                         items: [item(a, sendable: true), item(b, sendable: false)], isSending: true)
    #expect(picked == b)
}

@Test func fallsBackWhilePreferredIsAwayThenReturns() {
    let a = UUID(), b = UUID()
    let away = DeviceSelection.resolve(current: b, preferred: b,
                                       items: [item(a, sendable: true), item(b, sendable: false)], isSending: false)
    #expect(away == a)
    let back = DeviceSelection.resolve(current: away, preferred: b,
                                       items: [item(a, sendable: true), item(b, sendable: true)], isSending: false)
    #expect(back == b)
}

@Test func sendingStaysVisibleWhenTargetBlinksOut() {
    let blink = DeviceListItem(id: "x", deviceId: UUID(), name: "Mac", platform: .mac, isPaired: true,
                               isNearby: false, isSendable: false, canPair: false, subtitle: "")
    let state = ConnectionState.derive(nearbyCount: 0, selected: blink,
                                       sendPhase: .sending(fileName: "a", progress: 0.5), errorMessage: nil)
    #expect(state == .sending(deviceName: "Mac", progress: 0.5))
}

@Test func droppedWebLinkIsRejected() throws {
    #expect(try DroppedFile.stage(URL(string: "https://example.com")!) == nil)
}

@Test func droppedFileIsCopiedSoItOutlivesTheDrop() throws {
    let source = FileManager.default.temporaryDirectory.appendingPathComponent("drop-\(UUID()).txt")
    try Data("hello".utf8).write(to: source)
    let staged = try #require(try DroppedFile.stage(source))
    try FileManager.default.removeItem(at: source)
    #expect(staged.lastPathComponent == source.lastPathComponent)
    #expect(try Data(contentsOf: staged) == Data("hello".utf8))
}
