import LinkBridgePresentation
import Foundation
@testable import LinkBridgeIOS
import Testing

private func item(sendable: Bool) -> DeviceListItem {
    DeviceListItem(id: "x", deviceId: UUID(), name: "Mac", platform: .mac, isPaired: true,
                   isNearby: true, isSendable: sendable, canPair: false, subtitle: "")
}

@Test func errorWinsOverEverything() {
    let state = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true),
                                       sendPhase: .sending(fileName: "a", progress: 0.4), errorMessage: "Boom")
    #expect(state == .error(message: "Boom"))
    #expect(state.dotLabel == "Error")
    #expect(state.dotAccent == .attention)
    #expect(state.title == "Boom")
}

@Test func sendingShowsProgress() {
    let state = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true),
                                       sendPhase: .sending(fileName: "a", progress: 0.4), errorMessage: nil)
    #expect(state == .sending(deviceName: "Mac", progress: 0.4))
    #expect(state.dotLabel == "Connected")
}

@Test func sendableSelectionIsReady() {
    let state = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true), sendPhase: .idle, errorMessage: nil)
    #expect(state == .ready(deviceName: "Mac"))
    #expect(state.dotAccent == .signal)
    #expect(state.title == "Mac")
}

@Test func nothingNearbyIsSearching() {
    let state = ConnectionState.derive(nearbyCount: 0, selected: nil, sendPhase: .idle, errorMessage: nil)
    #expect(state == .searching)
    #expect(state.title == "Looking for devices…")
    #expect(state.dotLabel == "Searching")
    #expect(state.dotAccent == nil)
}

@Test func nearbyButNoSendableSelectionIsNoPairedDevice() {
    let state = ConnectionState.derive(nearbyCount: 2, selected: item(sendable: false), sendPhase: .idle, errorMessage: nil)
    #expect(state == .noPairedDevice)
    #expect(state.title == "No paired device nearby")
}
