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

    /// Pairing v4: the responder's `/pair/finalize` reply carries
    /// HMAC-SHA256(Kc, transcript), with Kc = HKDF(pairing secret, info "linkbridge-confirm-v4").
    /// It binds the "paired" answer to this key exchange and to the version, so a spoofed reply
    /// or a stripped version fails. It can't stop a MITM, who holds the initiator's secret:
    /// the initiator's user confirming that the responder listed it as paired does that.
    static func confirmation(secret: String, version: Int, initiatorKey: Data, responderKey: Data,
                             initiatorNonce: Data, responderNonce: Data, initiatorId: UUID, responderId: UUID) -> Data? {
        guard let secretBytes = Data(base64Encoded: secret), (0...255).contains(version) else { return nil }
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: secretBytes), salt: Data(),
            info: Data(confirmLabel.utf8), outputByteCount: 32
        )
        var transcript = Data(confirmLabel.utf8)
        transcript.append(UInt8(version))
        transcript += initiatorKey + responderKey + initiatorNonce + responderNonce + initiatorId.bytes + responderId.bytes
        return Data(HMAC<SHA256>.authenticationCode(for: transcript, using: key))
    }

    /// Constant-time check of a responder's confirmation.
    static func isValidConfirmation(_ received: Data?, expected: Data?) -> Bool {
        guard let received, let expected, received.count == expected.count else { return false }
        return zip(received, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    private static let confirmLabel = "linkbridge-confirm-v4"

    /// Pairing protocol this build speaks. Sent in /pair/request and echoed back; both sides
    /// refuse anything older, since the version arrives over plain HTTP.
    public static let pairingVersion = 4

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
