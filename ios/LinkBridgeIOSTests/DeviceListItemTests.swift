import LinkBridgePresentation
import Foundation
import LinkBridgeCore
@testable import LinkBridgeIOS
import Testing

private let now = Date(timeIntervalSince1970: 1_800_000_000)

private func peer(_ name: String, id: UUID? = UUID(), platform: String? = "macos",
                  host: String = "10.0.0.5", tlsPort: UInt16? = 49162) -> ResolvedPeer {
    ResolvedPeer(deviceName: name, deviceId: id, platform: platform, host: host, port: 49152, tlsPort: tlsPort)
}

private func trusted(_ name: String, id: UUID, platform: String = "macos") -> TrustedPeer {
    TrustedPeer(deviceId: id, deviceName: name, platform: platform, protocolVersion: 2,
                features: [], pairedAt: Date(timeIntervalSince1970: 1_760_000_000), authToken: "t")
}

@Test func orderIsNearbyPairedThenNearbyUnpairedThenOfflinePaired() {
    let a = UUID(), b = UUID(), c = UUID(), d = UUID()
    let items = DeviceListItem.merge(
        nearby: [peer("Zed Mac", id: a), peer("Unpaired Mac", id: c), peer("Alpha Mac", id: b)],
        trusted: [trusted("Zed Mac", id: a), trusted("Alpha Mac", id: b), trusted("Away Mac", id: d)],
        lastSeen: [:], now: now)
    #expect(items.map(\.name) == ["Alpha Mac", "Zed Mac", "Unpaired Mac", "Away Mac"])
}

@Test func pairedNearbyWithTLSIsSendable() {
    let id = UUID()
    let item = DeviceListItem.merge(nearby: [peer("Mac", id: id)], trusted: [trusted("Mac", id: id)],
                                    lastSeen: [:], now: now)[0]
    #expect(item.subtitle == "Paired · Nearby")
    #expect(item.isSendable)
    #expect(item.isPaired && item.isNearby)
}

@Test func pairedNearbyWithoutTLSNeedsUpdate() {
    let id = UUID()
    let item = DeviceListItem.merge(nearby: [peer("Mac", id: id, tlsPort: nil)], trusted: [trusted("Mac", id: id)],
                                    lastSeen: [:], now: now)[0]
    #expect(item.subtitle == "Paired · needs a LinkBridge update")
    #expect(!item.isSendable)
}

@Test func peerWithoutDeviceIdOrTLSUsesEndpointIdAndCannotPair() {
    let item = DeviceListItem.merge(nearby: [peer("Old Mac", id: nil, tlsPort: nil)], trusted: [], lastSeen: [:], now: now)[0]
    #expect(item.id == "10.0.0.5:49152")
    #expect(item.subtitle == "Needs a LinkBridge update")
    #expect(!item.canPair)
    #expect(!item.isSendable)
}

/// Protocol 3 Macs leave the device ID out of Bonjour; pairing reads it from `GET /device`.
@Test func unpairedV3MacWithoutDeviceIdCanPair() {
    let discovered = peer("New Mac", id: nil)
    let item = DeviceListItem.merge(nearby: [discovered], trusted: [], lastSeen: [:], now: now)[0]
    #expect(item.id == DeviceListItem.itemId(for: discovered))
    #expect(item.subtitle == "Not paired")
    #expect(item.canPair)
    #expect(!item.isSendable)
}

@Test func unpairedMacCanPair() {
    let item = DeviceListItem.merge(nearby: [peer("Mac")], trusted: [], lastSeen: [:], now: now)[0]
    #expect(item.subtitle == "Not paired")
    #expect(item.canPair)
    #expect(item.platform == .mac)
}

@Test func unpairedAndroidExplainsPairingDirection() {
    let item = DeviceListItem.merge(nearby: [peer("Phone", platform: "android")], trusted: [],
                                    lastSeen: [:], now: now)[0]
    #expect(item.subtitle == "Pair from the Android app")
    #expect(!item.canPair)
    #expect(item.platform == .android)
}

@Test func offlinePairedShowsLastSeenOrPairedDate() {
    let seen = UUID(), never = UUID()
    let items = DeviceListItem.merge(
        nearby: [], trusted: [trusted("A Mac", id: seen), trusted("B Mac", id: never)],
        lastSeen: [seen: now.addingTimeInterval(-300)], now: now)
    #expect(items[0].subtitle.hasPrefix("Last seen"))
    #expect(items[1].subtitle.hasPrefix("Paired "))
    #expect(items.allSatisfy { !$0.isNearby && !$0.isSendable && $0.isPaired })
}

@Test func testPairedIPhoneIsSendable() {
    let id = UUID()
    let item = DeviceListItem.merge(nearby: [peer("Phone", id: id, platform: "ios")],
                                    trusted: [trusted("Phone", id: id, platform: "ios")], lastSeen: [:], now: now)[0]
    #expect(item.platform == .iphone)
    #expect(item.isSendable)
    #expect(!item.subtitle.contains("coming soon"))
}

@Test func testUnpairedIPhoneSubtitle() {
    let item = DeviceListItem.merge(nearby: [peer("Phone", platform: "ios")], trusted: [], lastSeen: [:], now: now)[0]
    #expect(item.subtitle == "Pair from the iPhone app")
    #expect(!item.canPair)
}

@Test func macUnreachableIphoneMessage() {
    #expect(SendFailureMessage.text(platform: .iphone, peerName: "Phone", unreachable: true, failed: 1, total: 1)
            == "Open LinkBridge on the iPhone")
    #expect(SendFailureMessage.text(platform: .android, peerName: "Pixel", unreachable: true, failed: 1, total: 1)
            .hasPrefix("Couldn't reach Pixel"))
    #expect(SendFailureMessage.text(platform: .iphone, peerName: "Phone", unreachable: false, failed: 1, total: 1)
            .hasPrefix("Couldn't reach Phone"))
}

@Test func testUnpairedDevicesWithoutDeviceIdAreNotOldApps() {
    // Tag-based discovery: an unpaired device advertises no deviceId.
    let android = DeviceListItem.merge(nearby: [peer("Pixel", id: nil, platform: "android")], trusted: [],
                                       lastSeen: [:], now: now)[0]
    #expect(android.subtitle == "Pair from the Android app")
    let iphone = DeviceListItem.merge(nearby: [peer("Phone", id: nil, platform: "ios")], trusted: [],
                                      lastSeen: [:], now: now)[0]
    #expect(iphone.subtitle == "Pair from the iPhone app")
    let old = DeviceListItem.merge(nearby: [peer("Old", id: nil, platform: "android", tlsPort: nil)], trusted: [],
                                   lastSeen: [:], now: now)[0]
    #expect(old.subtitle == "Needs a LinkBridge update")
}
