import Foundation
import LinkBridgeCore
import LinkBridgePresentation
@testable import LinkBridgeIOS
import Network
import Testing
import CryptoKit

private let qrAddress = "192.168.49.1"
private let androidRid = "000102030405060708090a0b0c0d0e0f"

private func androidRecord(port: UInt16 = 49152, tlsPort: UInt16? = 49153) -> DiscoveryRecord {
    DiscoveryRecord(rid: androidRid, tags: "", deviceName: "Pixel 8", platform: "android", protocolVersion: 2,
                    port: port, tlsPort: tlsPort, features: "file-transfer")
}

private func qrText(record: DiscoveryRecord = androidRecord()) -> String {
    OfflineLinkPayload(ssid: "DIRECT-lb-ab12", passphrase: "correct-horse", address: IPv4Address(qrAddress)!,
                       helloPort: 49154, record: record).encoded()
}

@MainActor
private final class FakeJoiner: HotspotJoining {
    var result: Result<Void, Error> = .success(())
    var joined: Bool? = true
    var configured: [String] = []
    /// When set, `join` waits for `finishJoin()`, like iOS showing its "Join network?" alert.
    var holdsJoin = false
    private(set) var joins: [(ssid: String, passphrase: String)] = []
    private(set) var leaves: [String] = []
    private(set) var events: [String] = []
    private var pending: CheckedContinuation<Void, Never>?

    func join(ssid: String, passphrase: String) async throws {
        joins.append((ssid, passphrase))
        if holdsJoin { await withCheckedContinuation { pending = $0 } }
        events.append("joined")
        try result.get()
    }
    func finishJoin() { pending?.resume(); pending = nil }
    func leave(ssid: String) { leaves.append(ssid); events.append("leave") }
    func isJoined(ssid: String) async -> Bool? { joined }
    func configuredSSIDs() async -> [String] { configured }
}

@MainActor
private final class FakeHello: OfflineHelloSending {
    var result: Result<DiscoveryRecord, Error> = .success(androidRecord())
    private(set) var calls: [(host: String, port: UInt16)] = []
    private(set) var callTimes: [Double] = []
    var now: () -> Double = { 0 }

    func hello(host: String, port: UInt16) async throws -> DiscoveryRecord {
        calls.append((host, port))
        callTimes.append(now())
        return try result.get()
    }
}

/// Virtual time: each sleep returns at once and moves `now` on. Past a minute it parks until
/// cancelled, so the connection watch can't spin forever in a test.
@MainActor
private final class FakeClock: JoinClock {
    private(set) var now: Double = 0
    /// Virtual seconds after which sleeps park.
    var parkAfter: Double = 60
    func sleep(for seconds: Double) async throws {
        now += seconds
        if now > parkAfter { try await Task.sleep(for: .seconds(3600)) } else { await Task.yield() }
    }
}

@MainActor
private func makeFlow(bonjour: @escaping (Double) -> [ResolvedPeer] = { _ in [] })
    -> (OfflineJoinFlow, FakeJoiner, FakeHello, FakeClock) {
    let joiner = FakeJoiner(), hello = FakeHello(), clock = FakeClock()
    hello.now = { clock.now }
    let flow = OfflineJoinFlow(joiner: joiner, hello: hello, browserPeers: { bonjour(clock.now) }, clock: clock,
                               trustedPeers: { [] })
    return (flow, joiner, hello, clock)
}

private func bonjourPixel(rid: String = androidRid) -> ResolvedPeer {
    ResolvedPeer(deviceName: "Pixel 8", deviceId: nil, platform: "android", host: "pixel.local", port: 49152,
                 tlsPort: 49153, rid: rid)
}

@MainActor
@Test func invalidCodeShowsNotLinkBridgeAndNeverJoins() async {
    let (flow, joiner, hello, _) = makeFlow()
    await flow.handleScanned("https://example.com/not-a-link")
    #expect(flow.phase == .notLinkBridge)
    // Valid shape, hostile values (Review Focus 2): port 0 inside the record.
    flow.scanAgain()
    await flow.handleScanned(qrText(record: androidRecord(port: 0)))
    #expect(flow.phase == .notLinkBridge)
    #expect(joiner.joins.isEmpty)
    #expect(hello.calls.isEmpty)
    #expect(JoinPhase.notLinkBridge.message == "This isn't a LinkBridge code")
}

@MainActor
@Test func cancelledJoinReturnsToScanning() async {
    let (flow, joiner, hello, _) = makeFlow()
    joiner.result = .failure(HotspotJoinError.cancelled)
    await flow.handleScanned(qrText())
    #expect(joiner.joins.map(\.ssid) == ["DIRECT-lb-ab12"])
    #expect(joiner.joins.map(\.passphrase) == ["correct-horse"])
    #expect(flow.phase == .scanning)
    #expect(hello.calls.isEmpty)
}

@MainActor
@Test func joinFailureRemovesConfig() async {
    let (flow, joiner, hello, _) = makeFlow()
    joiner.result = .failure(HotspotJoinError.failed("internal"))
    await flow.handleScanned(qrText())
    #expect(joiner.leaves == ["DIRECT-lb-ab12"])
    #expect(flow.phase == .failed("Couldn't join: move closer to the Android and scan again"))
    #expect(hello.calls.isEmpty)
}

@MainActor
@Test func noAndroidFoundRemovesConfig() async {
    let (flow, joiner, hello, _) = makeFlow()
    hello.result = .failure(OfflineHelloError.timedOut)
    await flow.handleScanned(qrText())
    #expect(!hello.calls.isEmpty)
    #expect(joiner.leaves == ["DIRECT-lb-ab12"])
    #expect(flow.phase == .failed("Couldn't join: move closer to the Android and scan again"))
}

@MainActor
@Test func bonjourWithin3sWins() async {
    let (flow, _, hello, clock) = makeFlow(bonjour: { now in now >= 1 ? [bonjourPixel()] : [] })
    await flow.handleScanned(qrText())
    guard case .connected(let peer) = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    #expect(peer.host == "pixel.local")
    #expect(hello.calls.isEmpty, "hello not needed once Bonjour answered")
    #expect(clock.now >= 1 && clock.now < 3)
    flow.end()
}

@MainActor
@Test func bonjourOfAnotherDeviceDoesNotCount() async {
    let (flow, _, hello, _) = makeFlow(bonjour: { _ in [bonjourPixel(rid: "ffff")] })
    await flow.handleScanned(qrText())
    #expect(hello.calls.count == 1, "another advertiser is not the Android from the code")
    flow.end()
}

@MainActor
@Test func helloAfter3sFallback() async {
    let (flow, _, hello, clock) = makeFlow()
    // The hello response, not the QR record, supplies the ports.
    hello.result = .success(androidRecord(port: 50000, tlsPort: 50001))
    await flow.handleScanned(qrText())
    #expect(hello.calls.map(\.host) == [qrAddress])
    #expect(hello.calls.map(\.port) == [49154])
    #expect(clock.now >= 3, "Bonjour gets about 3 s first")
    guard case .connected(let peer) = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    #expect(peer.host == qrAddress)
    #expect(peer.port == 50000 && peer.tlsPort == 50001)
    #expect(peer.service == nil)
    #expect(peer.rid == androidRid)
    flow.end()
}

@MainActor
@Test func endDuringJoinRemovesConfigOnceJoinFinishes() async {
    let (flow, joiner, hello, _) = makeFlow()
    joiner.holdsJoin = true
    let scan = Task { await flow.handleScanned(qrText()) }
    for _ in 0..<200 where joiner.joins.isEmpty { await Task.yield() }
    #expect(flow.phase == .joining)
    flow.end()
    #expect(joiner.leaves == ["DIRECT-lb-ab12"])
    // iOS finishes installing the network after the user cancelled.
    joiner.finishJoin()
    await scan.value
    #expect(joiner.events == ["leave", "joined", "leave"], "removed again after the late join")
    #expect(flow.phase == .scanning)
    #expect(hello.calls.isEmpty)
}

@MainActor
@Test func helloFromAnotherDeviceIsRejected() async {
    let (flow, joiner, hello, _) = makeFlow()
    var other = androidRecord()
    other.rid = "ffffffffffffffffffffffffffffffff"
    hello.result = .success(other)
    await flow.handleScanned(qrText())
    #expect(flow.phase == .failed("Couldn't join: move closer to the Android and scan again"))
    #expect(joiner.leaves == ["DIRECT-lb-ab12"])
}

@MainActor
@Test func invalidHelloResponseIsRejected() async {
    let (flow, _, hello, _) = makeFlow()
    hello.result = .success(androidRecord(port: 0))
    await flow.handleScanned(qrText())
    #expect(flow.phase == .failed("Couldn't join: move closer to the Android and scan again"))
}

@MainActor
@Test func bonjourAndHelloSameDeviceOneRow() async {
    let (flow, _, _, _) = makeFlow()
    await flow.handleScanned(qrText())
    guard case .connected(let helloPeer) = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    // Bonjour starts working later and lists the same Android (same rid, unpaired).
    let merged = OfflinePeers.merge(bonjour: [bonjourPixel()], hello: [helloPeer])
    #expect(merged.count == 1)
    #expect(merged.first?.host == "pixel.local")
    let rows = DeviceListItem.merge(nearby: merged, trusted: [], lastSeen: [:], now: Date())
    #expect(rows.count == 1)
    // A different device is still its own row.
    let other = ResolvedPeer(deviceName: "Mac", deviceId: UUID(), platform: "macos", host: "mac.local", port: 49152,
                             tlsPort: 49162, rid: "eeee")
    #expect(OfflinePeers.merge(bonjour: [other], hello: [helloPeer]).count == 2)
    flow.end()
}

@MainActor
@Test func endRemovesConfig() async {
    let (flow, joiner, _, _) = makeFlow()
    await flow.handleScanned(qrText())
    guard case .connected = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    #expect(joiner.leaves.isEmpty, "staying joined: the config is kept while connected")
    flow.end()
    #expect(joiner.leaves == ["DIRECT-lb-ab12"])
    #expect(flow.phase == .scanning)
}

@MainActor
@Test func leavingTheNetworkOffersRejoin() async {
    let (flow, joiner, _, _) = makeFlow()
    await flow.handleScanned(qrText())
    guard case .connected = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    joiner.joined = false
    for _ in 0..<200 where flow.phase != .disconnected { await Task.yield() }
    #expect(flow.phase == .disconnected)
    #expect(joiner.leaves == ["DIRECT-lb-ab12"], "no saved network outlives the session")
    #expect(JoinPhase.disconnected.message == "Disconnected from the Android network")
    joiner.joined = true
    await flow.rejoin()
    #expect(joiner.joins.count == 2)
    guard case .connected = flow.phase else { Issue.record("not reconnected: \(flow.phase)"); return }
    flow.end()
}

@MainActor
@Test func helloIsRefreshedEveryMinuteWhileConnected() async {
    let (flow, _, hello, clock) = makeFlow()
    clock.parkAfter = 200
    await flow.handleScanned(qrText())
    guard case .connected = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    #expect(hello.calls.count == 1)
    for _ in 0..<2000 where hello.calls.count < 3 { await Task.yield() }
    #expect(hello.calls.count == 3, "two refreshes within 200 virtual seconds")
    let gaps = zip(hello.callTimes.dropFirst(), hello.callTimes).map { $0 - $1 }
    #expect(gaps.allSatisfy { $0 >= 60 && $0 < 64 }, "about every 60 s: \(gaps)")
    flow.end()
}

@MainActor
@Test func helloAfterPairingAcceptsTheSameDeviceWithANewRid() async {
    // Paired: the Android's tags now match this trusted peer.
    let secret = Data((0..<32).map { UInt8($0 + 1) }).base64EncodedString()
    let androidId = UUID()
    let trusted = TrustedPeer(deviceId: androidId, deviceName: "Pixel 8", platform: "android", protocolVersion: 2,
                              features: [], pairedAt: Date(), authToken: secret)
    let joiner = FakeJoiner(), hello = FakeHello(), clock = FakeClock()
    var paired = false
    let flow = OfflineJoinFlow(joiner: joiner, hello: hello, browserPeers: { [] }, clock: clock,
                               trustedPeers: { paired ? [trusted] : [] })
    await flow.handleScanned(qrText())
    guard case .connected(let before) = flow.phase else { Issue.record("not connected: \(flow.phase)"); return }
    #expect(before.deviceId == nil)

    // After pairing, the reply carries the tag for this iPhone (and here a new rid as well).
    paired = true
    let newRid = "ffffffffffffffffffffffffffffffff"
    var record = androidRecord()
    record.rid = newRid
    record.tags = discoveryTag(secret: secret, rid: newRid)
    hello.result = .success(record)
    await flow.refreshHelloPeer()
    guard case .connected(let afterNewRid) = flow.phase else { Issue.record("dropped: \(flow.phase)"); return }
    // Unknown deviceId before: the new rid is refused, the row is unchanged.
    #expect(afterNewRid.deviceId == nil)

    // Same rid, matching tag: the Android gets its deviceId.
    record.rid = androidRid
    record.tags = discoveryTag(secret: secret, rid: androidRid)
    #expect(record.tags == "e715ba5b9a4483e8", "the shared tag vector")
    hello.result = .success(record)
    await flow.refreshHelloPeer()
    guard case .connected(let paired1) = flow.phase else { Issue.record("dropped: \(flow.phase)"); return }
    #expect(paired1.deviceId == androidId)

    // Now known by deviceId: a reply with a new rid (the Android app restarted) is still accepted.
    record.rid = newRid
    record.tags = discoveryTag(secret: secret, rid: newRid)
    hello.result = .success(record)
    await flow.refreshHelloPeer()
    guard case .connected(let paired2) = flow.phase else { Issue.record("dropped: \(flow.phase)"); return }
    #expect(paired2.deviceId == androidId)
    #expect(paired2.rid == newRid)
    flow.end()
}

@MainActor
@Test func leftoverNetworksAreRemovedAtLaunch() async {
    let (flow, joiner, _, _) = makeFlow()
    joiner.configured = ["DIRECT-old-1", "DIRECT-old-2"]
    await flow.removeLeftoverNetworks()
    #expect(joiner.leaves == ["DIRECT-old-1", "DIRECT-old-2"])
}

/// DiscoveryTags.tag, which LinkBridgeCore keeps internal: HMAC(HKDF(secret), rid), 8 bytes, hex.
private func discoveryTag(secret: String, rid: String) -> String {
    let ridBytes = Data(stride(from: 0, to: rid.count, by: 2).map {
        UInt8(rid[rid.index(rid.startIndex, offsetBy: $0)...].prefix(2), radix: 16)!
    })
    let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(base64Encoded: secret)!),
                                     salt: Data("linkbridge-discovery-v1".utf8), info: Data("tag".utf8),
                                     outputByteCount: 32)
    return Data(HMAC<SHA256>.authenticationCode(for: ridBytes, using: key)).prefix(8)
        .map { String(format: "%02x", $0) }.joined()
}
