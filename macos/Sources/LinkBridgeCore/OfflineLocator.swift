import Foundation
import Network
#if os(macOS)
import SystemConfiguration
#endif

/// Peers found by hello rather than Bonjour. They stay listed while they keep answering:
/// each is due for a fresh hello every `refreshInterval`, and is dropped when that fails.
/// An entry is keyed by its host address first: after pairing the same Android answers with new
/// tags (and so a deviceId), which must replace the unpaired entry rather than sit beside it.
public struct HelloPeerBook {
    public static let refreshInterval: TimeInterval = 30
    private var entries: [(peer: ResolvedPeer, refreshedAt: Date)] = []

    public init() {}

    public var peers: [ResolvedPeer] { entries.map(\.peer) }

    public mutating func record(_ peer: ResolvedPeer, at now: Date) {
        entries.removeAll { Self.matches($0.peer, peer) }
        entries.append((peer, now))
    }

    public func due(at now: Date) -> [ResolvedPeer] {
        entries.filter { now.timeIntervalSince($0.refreshedAt) >= Self.refreshInterval }.map(\.peer)
    }

    public mutating func drop(_ peer: ResolvedPeer) {
        entries.removeAll { Self.matches($0.peer, peer) }
    }

    private static func matches(_ a: ResolvedPeer, _ b: ResolvedPeer) -> Bool {
        a.host == b.host || OfflinePeers.isSameDevice(a, b)
    }
}

public enum LocateResult {
    case found(ResolvedPeer)
    case needsManualAddress
}

/// Finds the Android device after the user has joined its hotspot by hand: Bonjour first, then
/// the offline hello at the default gateway. When neither works the caller asks for the address.
/// `locate` and `connectManually` block (the hello does), so call them off the main thread.
public final class OfflineLocator {
    private let gateway: () -> IPv4Address?
    private let hello: (NWEndpoint.Host, DiscoveryRecord) throws -> DiscoveryRecord
    private let bonjourPeers: () -> [ResolvedPeer]
    private let trusted: () -> [TrustedPeer]
    private let clock: () -> Date
    private let sleep: (TimeInterval) -> Void

    public init(gateway: @escaping () -> IPv4Address?,
                hello: @escaping (NWEndpoint.Host, DiscoveryRecord) throws -> DiscoveryRecord,
                bonjourPeers: @escaping () -> [ResolvedPeer],
                trusted: @escaping () -> [TrustedPeer] = { [] },
                clock: @escaping () -> Date = Date.init,
                sleep: @escaping (TimeInterval) -> Void = { Thread.sleep(forTimeInterval: $0) }) {
        self.gateway = gateway
        self.hello = hello
        self.bonjourPeers = bonjourPeers
        self.trusted = trusted
        self.clock = clock
        self.sleep = sleep
    }

    static let pollInterval: TimeInterval = 0.25

    #if os(macOS)
    /// A locator wired to the real network: the system's default gateway and the plain-HTTP hello.
    public static func system(bonjourPeers: @escaping () -> [ResolvedPeer],
                              trusted: @escaping () -> [TrustedPeer]) -> OfflineLocator {
        OfflineLocator(gateway: { SystemGateway.current() },
                       hello: { host, record in try OfflineHelloClient.exchange(host: host, record: record) },
                       bonjourPeers: bonjourPeers, trusted: trusted)
    }
    #endif

    /// What `locate`'s `baseline` holds for a Bonjour peer.
    public static func baselineKey(_ peer: ResolvedPeer) -> String { peer.rid ?? peer.endpoint }

    /// One pass: waits up to `timeout` for a Bonjour peer that is an Android and was not already
    /// listed when the guide opened (`baseline`, from `baselineKey`), polling every 250 ms; then
    /// tries the hello at the gateway. With peer-to-peer Wi-Fi on, a nearby Mac or iPhone shows
    /// up in Bonjour too and must not count as the Android just joined.
    public func locate(localRecord: DiscoveryRecord, baseline: Set<String> = [],
                       timeout: TimeInterval = 3) -> LocateResult {
        let deadline = clock().addingTimeInterval(timeout)
        while true {
            if let peer = bonjourPeers().first(where: {
                $0.platform == "android" && !baseline.contains(Self.baselineKey($0))
            }) { return .found(peer) }
            if clock() >= deadline { break }
            sleep(Self.pollInterval)
        }
        guard let router = gateway(), OfflineLinkPayload.isPrivate(router) else { return .needsManualAddress }
        return helloPeer(at: router, localRecord: localRecord)
    }

    /// The address the user typed, under the same strict private-IPv4 rule as the QR payload.
    public func connectManually(address: String, localRecord: DiscoveryRecord) -> LocateResult {
        guard let parsed = OfflineLinkPayload.parseIPv4(address.trimmingCharacters(in: .whitespaces)),
              OfflineLinkPayload.isPrivate(parsed) else { return .needsManualAddress }
        return helloPeer(at: parsed, localRecord: localRecord)
    }

    private func helloPeer(at address: IPv4Address, localRecord: DiscoveryRecord) -> LocateResult {
        let host = OfflineLinkPayload.format(address)
        guard let record = try? hello(NWEndpoint.Host(host), localRecord), record.isValid else { return .needsManualAddress }
        return .found(OfflinePeers.peer(from: record, host: host, trusted: trusted()))
    }
}

#if os(macOS)
/// The default IPv4 gateway from the system configuration store. Nil on any failure.
public enum SystemGateway {
    public static func current() -> IPv4Address? {
        guard let global = SCDynamicStoreCopyValue(nil, "State:/Network/Global/IPv4" as CFString) as? [String: Any],
              let router = global["Router"] as? String else { return nil }
        return OfflineLinkPayload.parseIPv4(router)
    }
}
#endif
