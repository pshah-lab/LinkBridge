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

    /// From `/device`, `/pair/request` or upload headers. The older `protocolVersion` field is stale
    /// on every platform and is ignored. A missing `protocol` means protocol 2: TLS transfers shipped
    /// before pairing v3, so even a v1-pairing phone may speak protocol 2.
    public static func fromJSON(protocol current: Int?, minProtocol: Int?) -> ProtocolLevel {
        guard let current, current > 0 else { return ProtocolLevel(current: 2, minimum: 2) }
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

    /// What a receiver tells its own user after refusing a sender; `compatibility` is
    /// `check(local: receiver, peer: sender)`.
    public static func receiverNotice(_ compatibility: Compatibility, senderName: String, localName: String) -> String? {
        switch compatibility {
        case .ok: return nil
        case .peerTooOld: return "Update LinkBridge on \(senderName) to receive its files."
        case .localTooOld: return "Update LinkBridge on \(localName) to receive files from \(senderName)."
        }
    }

    /// Reads a `426 update_required` reply, from the point of view of the device that sent the request.
    /// Nil for any other body, such as `tls_required`.
    public static func rejection(fromBody body: Data) -> Compatibility? {
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              json["error"] as? String == "update_required" else { return nil }
        switch json["update"] as? String {
        case "sender": return .localTooOld
        case "receiver": return .peerTooOld
        default: return nil
        }
    }

    /// The `426 update_required` body a receiver sends, given `check(local: receiver, peer: sender)`.
    public static func rejectionBody(_ compatibility: Compatibility, local: ProtocolLevel = .local) -> Data {
        let update = compatibility == .localTooOld ? "receiver" : "sender"
        return Data(#"{"error":"update_required","update":"\#(update)","protocol":\#(local.current),"minProtocol":\#(local.minimum)}"#.utf8)
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
