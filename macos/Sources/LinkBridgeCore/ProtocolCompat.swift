import Foundation

/// The protocol a device speaks and the oldest protocol it still accepts from a peer.
/// See "Versions" in docs/protocol.md.
public struct ProtocolLevel: Equatable, Sendable {
    public let current: Int
    public let minimum: Int

    public init(current: Int, minimum: Int) {
        self.current = current
        self.minimum = minimum
    }

    public static let local = ProtocolLevel(current: ControlServer.protocolVersion, minimum: ControlServer.minProtocolVersion)
    static let legacy = ProtocolLevel(current: 1, minimum: 1)

    /// From a Bonjour TXT record. Advertisers before this field send `protocol=2` and a TLS port;
    /// apps older than protocol 2 send neither a TLS port nor (on the Mac) a device ID.
    public static func fromTXT(protocol current: String?, minProtocol: String?, hasTLSPort: Bool, hasDeviceId: Bool) -> ProtocolLevel {
        let parsedCurrent = current.flatMap { Int($0) }.flatMap { $0 > 0 ? $0 : nil }
        // Without a TLS port the peer cannot receive transfers, whatever else it advertises.
        if !hasTLSPort && parsedCurrent == nil { return legacy }
        let value = parsedCurrent ?? 2
        let minimum = minProtocol.flatMap { Int($0) }.flatMap { $0 > 0 ? $0 : nil } ?? min(2, value)
        return ProtocolLevel(current: value, minimum: minimum)
    }

    /// From `/device`, `/pair/request` or an upload. The older `protocolVersion` field is stale on
    /// every platform, so a missing `protocol` is inferred from the pairing version.
    public static func fromJSON(protocol current: Int?, minProtocol: Int?, pairingVersion: Int?) -> ProtocolLevel {
        guard let current, current > 0 else {
            return (pairingVersion ?? 1) >= 3 ? ProtocolLevel(current: 2, minimum: 2) : legacy
        }
        let minimum = minProtocol.flatMap { $0 > 0 ? $0 : nil } ?? min(2, current)
        return ProtocolLevel(current: current, minimum: minimum)
    }
}

public enum Compatibility: Equatable, Sendable {
    case ok, peerTooOld, localTooOld
}

public enum ProtocolCompat {
    public static func check(local: ProtocolLevel = .local, peer: ProtocolLevel) -> Compatibility {
        if peer.current < local.minimum { return .peerTooOld }
        if local.current < peer.minimum { return .localTooOld }
        return .ok
    }

    /// `localName` is "this Mac" on macOS and "this device" on iOS.
    public static func message(_ compatibility: Compatibility, peerName: String, localName: String) -> String? {
        switch compatibility {
        case .ok: return nil
        case .peerTooOld: return "Update LinkBridge on \(peerName)"
        case .localTooOld: return "Update LinkBridge on \(localName)"
        }
    }

    public static var localName: String {
        #if os(macOS)
        return "this Mac"
        #else
        return "this device"
        #endif
    }
}
