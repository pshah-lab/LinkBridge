import Foundation
import LinkBridgeCore
import LinkBridgePresentation
@testable import LinkBridgeIOS
import Network
import Testing

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
    private(set) var joins: [(ssid: String, passphrase: String)] = []
    private(set) var leaves: [String] = []

    func join(ssid: String, passphrase: String) async throws {
        joins.append((ssid, passphrase))
        try result.get()
    }
    func leave(ssid: String) { leaves.append(ssid) }
    func isJoined(ssid: String) async -> Bool? { joined }
}

@MainActor
private final class FakeHello: OfflineHelloSending {
    var result: Result<DiscoveryRecord, Error> = .success(androidRecord())
    private(set) var calls: [(host: String, port: UInt16)] = []

    func hello(host: String, port: UInt16) async throws -> DiscoveryRecord {
        calls.append((host, port))
        return try result.get()
    }
}

/// Virtual time: each sleep returns at once and moves `now` on. Past a minute it parks until
/// cancelled, so the connection watch can't spin forever in a test.
@MainActor
private final class FakeClock: JoinClock {
    private(set) var now: Double = 0
    func sleep(for seconds: Double) async throws {
        now += seconds
        if now > 60 { try await Task.sleep(for: .seconds(3600)) } else { await Task.yield() }
    }
}

@MainActor
private func makeFlow(bonjour: @escaping (Double) -> [ResolvedPeer] = { _ in [] })
    -> (OfflineJoinFlow, FakeJoiner, FakeHello, FakeClock) {
    let joiner = FakeJoiner(), hello = FakeHello(), clock = FakeClock()
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
}

@MainActor
@Test func bonjourOfAnotherDeviceDoesNotCount() async {
    let (flow, _, hello, _) = makeFlow(bonjour: { _ in [bonjourPixel(rid: "ffff")] })
    await flow.handleScanned(qrText())
    #expect(hello.calls.count == 1, "another advertiser is not the Android from the code")
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
    #expect(JoinPhase.disconnected.message == "Disconnected from the Android network")
    joiner.joined = true
    await flow.rejoin()
    #expect(joiner.joins.count == 2)
    guard case .connected = flow.phase else { Issue.record("not reconnected: \(flow.phase)"); return }
    flow.end()
}
