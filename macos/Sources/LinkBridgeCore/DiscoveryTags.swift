import CryptoKit
import Foundation

/// Bonjour TXT without a stable device ID. An advertiser publishes a random `rid` (new each
/// launch) and one short tag per paired device: HMAC(K, rid) truncated to 8 bytes, where K comes
/// from that pairing's secret. A paired device finds its own tag and so knows who this is;
/// everyone else sees random values that change every launch. Must match Android's DiscoveryTags.
public enum DiscoveryTags {
    /// A TXT value is at most 255 bytes: "tags=" plus 14 × 17 characters fits.
    static let maxTags = 14

    static func randomRid() -> String {
        SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }.map { String(format: "%02x", $0) }.joined()
    }

    /// The tag for one pairing secret, or nil for a bad secret or rid.
    static func tag(secret: String, rid: String) -> String? {
        guard let ikm = Data(base64Encoded: secret), let ridBytes = Data(hex: rid) else { return nil }
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: Data("linkbridge-discovery-v1".utf8),
                                         info: Data("tag".utf8), outputByteCount: 32)
        let mac = HMAC<SHA256>.authenticationCode(for: ridBytes, using: key)
        return Data(mac).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// TXT value for the given secrets, most recently paired first.
    static func tags(secrets: [String], rid: String) -> String {
        secrets.prefix(maxTags).compactMap { tag(secret: $0, rid: rid) }.joined(separator: ",")
    }

    /// The paired device whose tag is in `tags`, if any.
    static func match(rid: String, tags: String, peers: [TrustedPeer]) -> TrustedPeer? {
        let published = Set(tags.split(separator: ",").map(String.init))
        guard !published.isEmpty else { return nil }
        return peers.first { peer in
            peer.authToken.flatMap { tag(secret: $0, rid: rid) }.map(published.contains) ?? false
        }
    }
}

extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}
