import Foundation
import Network
#if os(macOS)
import SystemConfiguration
#endif

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

    public init(gateway: @escaping () -> IPv4Address?,
                hello: @escaping (NWEndpoint.Host, DiscoveryRecord) throws -> DiscoveryRecord,
                bonjourPeers: @escaping () -> [ResolvedPeer],
                trusted: @escaping () -> [TrustedPeer] = { [] },
                clock: @escaping () -> Date = Date.init) {
        self.gateway = gateway
        self.hello = hello
        self.bonjourPeers = bonjourPeers
        self.trusted = trusted
        self.clock = clock
    }

    /// A locator wired to the real network: the system's default gateway and the plain-HTTP hello.
    public static func system(bonjourPeers: @escaping () -> [ResolvedPeer],
                              trusted: @escaping () -> [TrustedPeer]) -> OfflineLocator {
        OfflineLocator(gateway: { SystemGateway.current() },
                       hello: { host, record in try OfflineHelloClient.exchange(host: host, record: record) },
                       bonjourPeers: bonjourPeers, trusted: trusted)
    }

    /// One pass. `timeout` is the budget for the whole pass: the hello is skipped when Bonjour
    /// and the gateway lookup already used it up.
    public func locate(localRecord: DiscoveryRecord, timeout: TimeInterval = 3) -> LocateResult {
        let deadline = clock().addingTimeInterval(timeout)
        if let peer = bonjourPeers().first { return .found(peer) }
        guard let router = gateway(), OfflineLinkPayload.isPrivate(router), clock() < deadline else {
            return .needsManualAddress
        }
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
