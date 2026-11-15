import Combine
import Foundation
import LinkBridgeCore
import Network

/// Sends the offline hello to the Android and returns its record. A protocol for tests.
@MainActor
protocol OfflineHelloSending: AnyObject {
    func hello(host: String, port: UInt16) async throws -> DiscoveryRecord
}

/// How the flow waits. A protocol so tests run on virtual time.
@MainActor
protocol JoinClock: AnyObject {
    func sleep(for seconds: Double) async throws
}

final class SystemJoinClock: JoinClock {
    func sleep(for seconds: Double) async throws { try await Task.sleep(for: .seconds(seconds)) }
}

/// The real hello: this iPhone's own record, with the ports its receive server listens on, to
/// `OfflineHelloClient` off the main thread.
final class LiveOfflineHello: OfflineHelloSending {
    private let localRecord: () -> DiscoveryRecord?

    init(localRecord: @escaping () -> DiscoveryRecord?) { self.localRecord = localRecord }

    func hello(host: String, port: UInt16) async throws -> DiscoveryRecord {
        // Without a running receive server the Android could not reach this iPhone back.
        guard let record = localRecord() else { throw OfflineHelloError.connectionFailed }
        return try await Task.detached {
            try OfflineHelloClient.exchange(host: NWEndpoint.Host(host), port: port, record: record)
        }.value
    }
}

enum JoinPhase: Equatable {
    case scanning
    case joining
    case connected(ResolvedPeer)
    case failed(String)
    case notLinkBridge
    /// iOS left the Android's network (it has no internet); the user can rejoin.
    case disconnected

    static let joinFailedMessage = "Couldn't join: move closer to the Android and scan again"

    var message: String? {
        switch self {
        case .failed(let message): message
        case .notLinkBridge: "This isn't a LinkBridge code"
        case .disconnected: "Disconnected from the Android network"
        case .scanning, .joining, .connected: nil
        }
    }

    static func == (lhs: JoinPhase, rhs: JoinPhase) -> Bool {
        switch (lhs, rhs) {
        case (.scanning, .scanning), (.joining, .joining), (.notLinkBridge, .notLinkBridge),
             (.disconnected, .disconnected):
            true
        case let (.failed(a), .failed(b)):
            a == b
        case let (.connected(a), .connected(b)):
            a.host == b.host && a.port == b.port && a.tlsPort == b.tlsPort && a.deviceId == b.deviceId && a.rid == b.rid
        default:
            false
        }
    }
}

/// iPhone side of an Android offline link: scan the QR code, join its Wi-Fi, then find the
/// Android. Bonjour gets about 3 s; after that the offline hello goes to the address in the code.
@MainActor
final class OfflineJoinFlow: ObservableObject {
    @Published private(set) var phase: JoinPhase = .scanning

    static let bonjourWait: Double = 3
    static let pollInterval: Double = 0.25
    static let helloAttempts = 2
    static let watchInterval: Double = 3

    private let joiner: HotspotJoining
    private let hello: OfflineHelloSending
    private let browserPeers: () -> [ResolvedPeer]
    private let clock: JoinClock
    private let trustedPeers: () -> [TrustedPeer]

    private var payload: OfflineLinkPayload?
    /// The network whose configuration is installed (or being installed) and must be removed.
    private var configuredSSID: String?
    /// Bumped by `end()`, so work started before it stops touching state.
    private var generation = 0
    private var watch: Task<Void, Never>?

    init(joiner: HotspotJoining, hello: OfflineHelloSending, browserPeers: @escaping () -> [ResolvedPeer],
         clock: JoinClock, trustedPeers: @escaping () -> [TrustedPeer] = { TrustedPeerStore().load() }) {
        self.joiner = joiner
        self.hello = hello
        self.browserPeers = browserPeers
        self.clock = clock
        self.trustedPeers = trustedPeers
    }

    /// The scanner read `text`. Anything that isn't a valid link is refused before any join.
    func handleScanned(_ text: String) async {
        switch phase {
        case .joining, .connected, .disconnected: return
        case .scanning, .failed, .notLinkBridge: break
        }
        guard let payload = OfflineLinkPayload.parse(text) else {
            phase = .notLinkBridge
            return
        }
        await connect(payload)
    }

    /// Back to the camera after a refused code or a failed join.
    func scanAgain() {
        switch phase {
        case .failed, .notLinkBridge: phase = .scanning
        default: break
        }
    }

    func rejoin() async {
        guard phase == .disconnected, let payload else { return }
        await connect(payload)
    }

    /// Asks the Android again over the hello, for a peer learned that way. Call after pairing:
    /// the Android's tags only match once both sides have saved the new pairing.
    func refreshHelloPeer() async {
        guard case .connected(let current) = phase, current.service == nil, let payload else { return }
        let started = generation
        guard let peer = await helloPeer(payload), started == generation, case .connected = phase else { return }
        phase = .connected(peer)
    }

    /// Stops the link and removes the network configuration.
    func end() {
        generation += 1
        watch?.cancel()
        watch = nil
        if let configuredSSID { joiner.leave(ssid: configuredSSID) }
        configuredSSID = nil
        payload = nil
        phase = .scanning
    }

    private func connect(_ payload: OfflineLinkPayload) async {
        let started = generation
        self.payload = payload
        configuredSSID = payload.ssid
        phase = .joining
        do {
            try await joiner.join(ssid: payload.ssid, passphrase: payload.passphrase)
        } catch {
            guard started == generation else { return }
            if error as? HotspotJoinError == .cancelled {
                configuredSSID = nil
                phase = .scanning
            } else {
                fail()
            }
            return
        }
        guard started == generation else { return }

        if let peer = await bonjourPeer(for: payload, started: started) {
            connected(peer)
            return
        }
        guard started == generation else { return }
        if let peer = await helloPeer(payload) {
            guard started == generation else { return }
            connected(peer)
        } else if started == generation {
            // Bonjour may have caught up while the hello was out.
            if let peer = matchingBonjourPeer(payload) { connected(peer) } else { fail() }
        }
    }

    /// Polls the Bonjour browser for the Android in the code for up to `bonjourWait` seconds.
    private func bonjourPeer(for payload: OfflineLinkPayload, started: Int) async -> ResolvedPeer? {
        var waited: Double = 0
        while true {
            if let peer = matchingBonjourPeer(payload) { return peer }
            guard waited < Self.bonjourWait else { return nil }
            do { try await clock.sleep(for: Self.pollInterval) } catch { return nil }
            guard started == generation else { return nil }
            waited += Self.pollInterval
        }
    }

    /// The browsed peer that is the Android in the code: its matched deviceId, else its rid.
    private func matchingBonjourPeer(_ payload: OfflineLinkPayload) -> ResolvedPeer? {
        let expected = OfflinePeers.peer(from: payload.record, host: Self.host(payload), trusted: trustedPeers())
        return browserPeers().first { OfflinePeers.isSameDevice($0, expected) }
    }

    /// The Android as its hello describes it, at the address from the code. The response is
    /// untrusted: one outside the record limits counts as no answer.
    private func helloPeer(_ payload: OfflineLinkPayload) async -> ResolvedPeer? {
        let host = Self.host(payload)
        for attempt in 1...Self.helloAttempts {
            if let record = try? await hello.hello(host: host, port: payload.helloPort), record.isValid {
                return OfflinePeers.peer(from: record, host: host, trusted: trustedPeers())
            }
            // The address may still be coming up right after the join.
            if attempt < Self.helloAttempts { try? await clock.sleep(for: 1) }
        }
        return nil
    }

    private func connected(_ peer: ResolvedPeer) {
        phase = .connected(peer)
        watchNetwork(started: generation)
    }

    /// Checks now and then that iOS is still on the Android's network. It may leave it on its
    /// own, because the network has no internet.
    private func watchNetwork(started: Int) {
        watch?.cancel()
        guard let ssid = configuredSSID else { return }
        watch = Task { [weak self] in
            while let self, !Task.isCancelled {
                do { try await self.clock.sleep(for: Self.watchInterval) } catch { return }
                guard started == self.generation, case .connected = self.phase else { return }
                if await self.joiner.isJoined(ssid: ssid) == false, started == self.generation {
                    self.phase = .disconnected
                    return
                }
            }
        }
    }

    private func fail() {
        if let configuredSSID { joiner.leave(ssid: configuredSSID) }
        configuredSSID = nil
        phase = .failed(JoinPhase.joinFailedMessage)
    }

    private static func host(_ payload: OfflineLinkPayload) -> String {
        payload.address.rawValue.map { String($0) }.joined(separator: ".")
    }
}
