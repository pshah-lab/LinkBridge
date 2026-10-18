import CryptoKit
import Foundation

/// Six-digit comparison code for pairing, using a commit-then-reveal nonce
/// exchange so a man-in-the-middle cannot grind keys to force matching codes.
///
/// 1. Android -> /pair/request: its public key. Mac replies with its public key
///    and commitment = SHA256(tag || macKey || macNonce).
/// 2. Android -> /pair/confirm: its nonce. Mac replies with macNonce; Android
///    checks the commitment. Both sides now show the same code.
/// 3. Android -> /pair/finalize: waits for the Mac user's decision.
public enum PairingCode {
    static func commitment(publicKey: Data, nonce: Data) -> Data {
        var hasher = SHA256()
        hasher.update(data: Data("linkbridge-commit-v1".utf8))
        hasher.update(data: publicKey)
        hasher.update(data: nonce)
        return Data(hasher.finalize())
    }

    static func code(initiatorKey: Data, responderKey: Data, initiatorNonce: Data, responderNonce: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: Data("linkbridge-code-v1".utf8))
        hasher.update(data: initiatorKey)
        hasher.update(data: responderKey)
        hasher.update(data: initiatorNonce)
        hasher.update(data: responderNonce)
        let value = Data(hasher.finalize()).prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", value % 1_000_000)
    }

    /// Pairing v3: the code also covers both device IDs, so a MITM that rewrites the ID in
    /// `/device` (to overwrite another trusted peer) makes the two codes differ.
    static func code(initiatorKey: Data, responderKey: Data, initiatorNonce: Data, responderNonce: Data,
                     initiatorId: UUID, responderId: UUID) -> String {
        var hasher = SHA256()
        hasher.update(data: Data("linkbridge-code-v2".utf8))
        hasher.update(data: initiatorKey)
        hasher.update(data: responderKey)
        hasher.update(data: initiatorNonce)
        hasher.update(data: responderNonce)
        hasher.update(data: initiatorId.bytes)
        hasher.update(data: responderId.bytes)
        let value = Data(hasher.finalize()).prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", value % 1_000_000)
    }

    /// Pairing v3 secret: HKDF-SHA256 over the raw ECDH output, salted with both nonces and
    /// bound to both device IDs. Unlike v1 (SHA-256 of the ECDH output alone), every pairing
    /// of the same two devices gives a new secret, so re-pairing revokes a leaked one.
    static func secret(sharedSecret: Data, initiatorNonce: Data, responderNonce: Data,
                       initiatorId: UUID, responderId: UUID) -> String {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: initiatorNonce + responderNonce,
            info: Data("linkbridge-pair-v3".utf8) + initiatorId.bytes + responderId.bytes,
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }.base64EncodedString()
    }

    /// Pairing protocol this build speaks. Sent in /pair/request; a responder that also speaks
    /// it echoes it back, otherwise both sides use v1.
    public static let pairingVersion = 3

    static func randomNonce() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    /// "123456" -> "123 456"
    public static func display(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return "\(code.prefix(3)) \(code.suffix(3))"
    }
}

extension UUID {
    /// The 16 bytes in RFC 4122 order, as Java's most/least significant longs give them.
    var bytes: Data {
        withUnsafeBytes(of: uuid) { Data($0) }
    }
}
