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
        check(ProtocolLevel.fromJSON(protocol: nil, minProtocol: nil, pairingVersion: 3) == ProtocolLevel(current: 2, minimum: 2), "0.2.x JSON")
        check(ProtocolLevel.fromJSON(protocol: nil, minProtocol: nil, pairingVersion: nil) == ProtocolLevel(current: 1, minimum: 1), "pre-v3 JSON")
        check(ProtocolLevel.fromJSON(protocol: 4, minProtocol: 3, pairingVersion: 3) == ProtocolLevel(current: 4, minimum: 3), "explicit JSON values")
    }

    section("Protocol update messages") {
        check(ProtocolCompat.message(.peerTooOld, peerName: "Pixel", localName: "this Mac") == "Update LinkBridge on Pixel", "peer too old copy")
        check(ProtocolCompat.message(.localTooOld, peerName: "Pixel", localName: "this Mac") == "Update LinkBridge on this Mac", "local too old copy")
        check(ProtocolCompat.message(.ok, peerName: "Pixel", localName: "this Mac") == nil, "no message when compatible")
    }
}
