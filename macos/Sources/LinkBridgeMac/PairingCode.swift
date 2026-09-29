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
enum PairingCode {
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

    static func randomNonce() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    /// "123456" -> "123 456"
    static func display(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return "\(code.prefix(3)) \(code.suffix(3))"
    }
}
