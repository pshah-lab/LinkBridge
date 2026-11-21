import Foundation
import LinkBridgeCore

func runProtocolCompatChecks() {
    section("Protocol compatibility") {
        let local = ProtocolLevel(current: 2, minimum: 2)
        check(ProtocolLevel.local == local, "local level is 2/2")
        check(ControlServer.minProtocolVersion == 2, "minimum protocol constant")
        check(ProtocolCompat.check(local: local, peer: ProtocolLevel(current: 1, minimum: 1)) == .peerTooOld, "older peer is too old")
        check(ProtocolCompat.check(local: local, peer: ProtocolLevel(current: 3, minimum: 3)) == .localTooOld, "peer needing 3 makes this side too old")
        check(ProtocolCompat.check(local: local, peer: ProtocolLevel(current: 3, minimum: 2)) == .ok, "newer peer still accepting 2 is fine")
        check(ProtocolCompat.check(local: local, peer: local) == .ok, "same level is fine")
    }

    section("Protocol level inference") {
        check(ProtocolLevel.fromTXT(protocol: "2", minProtocol: nil, hasTLSPort: true, hasDeviceId: false) == ProtocolLevel(current: 2, minimum: 2), "0.2.x TXT")
        check(ProtocolLevel.fromTXT(protocol: "3", minProtocol: "2", hasTLSPort: true, hasDeviceId: false) == ProtocolLevel(current: 3, minimum: 2), "explicit TXT values")
        check(ProtocolLevel.fromTXT(protocol: nil, minProtocol: nil, hasTLSPort: false, hasDeviceId: false) == ProtocolLevel(current: 1, minimum: 1), "pre-TLS advertiser")
        check(ProtocolLevel.fromTXT(protocol: "abc", minProtocol: "", hasTLSPort: true, hasDeviceId: false) == ProtocolLevel(current: 2, minimum: 2), "garbage TXT values count as missing")
        check(ProtocolLevel.fromJSON(protocol: nil, minProtocol: nil) == ProtocolLevel(current: 2, minimum: 2), "JSON without the fields is protocol 2")
        check(ProtocolLevel.fromJSON(protocol: 4, minProtocol: 3) == ProtocolLevel(current: 4, minimum: 3), "explicit JSON values")
        check(ProtocolLevel.fromJSON(protocol: 3, minProtocol: nil) == ProtocolLevel(current: 3, minimum: 2), "missing minimum is 2")
    }

    section("Protocol refusals") {
        check(ProtocolCompat.rejection(fromBody: Data(#"{"error":"update_required","update":"sender","protocol":2,"minProtocol":2}"#.utf8)) == .localTooOld, "sender must update")
        check(ProtocolCompat.rejection(fromBody: Data(#"{"error":"update_required","update":"receiver"}"#.utf8)) == .peerTooOld, "receiver must update")
        check(ProtocolCompat.rejection(fromBody: Data(#"{"error":"tls_required"}"#.utf8)) == nil, "other 426 is not a version refusal")
        check(ProtocolCompat.rejection(fromBody: Data("<html>".utf8)) == nil, "junk is not a version refusal")
        let body = ProtocolCompat.rejectionBody(.peerTooOld)
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        check(json?["error"] as? String == "update_required" && json?["update"] as? String == "sender"
              && json?["protocol"] as? Int == 2 && json?["minProtocol"] as? Int == 2, "refusal body names the sender when it is too old")
        check((try? JSONSerialization.jsonObject(with: ProtocolCompat.rejectionBody(.localTooOld))) .flatMap { ($0 as? [String: Any])?["update"] as? String } == "receiver", "refusal body names the receiver when it is too old")
    }

    section("Protocol update messages") {
        check(ProtocolCompat.message(.peerTooOld, peerName: "Pixel", localName: "this Mac") == "Update LinkBridge on Pixel", "peer too old copy")
        check(ProtocolCompat.message(.localTooOld, peerName: "Pixel", localName: "this Mac") == "Update LinkBridge on this Mac", "local too old copy")
        check(ProtocolCompat.message(.ok, peerName: "Pixel", localName: "this Mac") == nil, "no message when compatible")
    }
}
