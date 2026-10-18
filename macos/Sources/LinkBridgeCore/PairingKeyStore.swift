import CryptoKit
import Foundation

final class PairingKeyStore {
    private let credentials: CredentialStore
    private let account = "identity.pairingPrivateKey"

    init(credentials: CredentialStore = CredentialStore()) {
        self.credentials = credentials
    }

    /// Uncompressed X9.63 point (0x04 || X || Y), matching what Android sends. Empty if the
    /// key store can't be read right now, so pairing fails instead of using a new key.
    var publicKeyBase64: String { (try? privateKey())?.publicKey.x963Representation.base64EncodedString() ?? "" }

    func deriveSecret(peerPublicKeyBase64: String) throws -> String {
        let peer = try PairingKeyStore.parsePublicKey(peerPublicKeyBase64)
        let shared = try privateKey().sharedSecretFromKeyAgreement(with: peer)
        let digest = SHA256.hash(data: shared.withUnsafeBytes { Data($0) })
        return Data(digest).base64EncodedString()
    }

    /// Accepts both the 65-byte X9.63 form and the legacy 64-byte raw form.
    static func parsePublicKey(_ base64: String) throws -> P256.KeyAgreement.PublicKey {
        guard let data = Data(base64Encoded: base64) else { throw PairingKeyError.invalidPublicKey }
        switch data.count {
        case 65: return try P256.KeyAgreement.PublicKey(x963Representation: data)
        case 64: return try P256.KeyAgreement.PublicKey(rawRepresentation: data)
        default: throw PairingKeyError.invalidPublicKey
        }
    }

    /// Creates a key only when none is stored. A store that can't be read (locked device,
    /// corrupt file) or a stored key that doesn't parse is an error, not a reason to replace
    /// the key and silently break every pairing.
    private func privateKey() throws -> P256.KeyAgreement.PrivateKey {
        switch credentials.lookup(account: account) {
        case let .found(stored):
            guard let data = Data(base64Encoded: stored),
                  let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) else {
                throw PairingKeyError.keyUnavailable
            }
            return key
        case .notFound:
            let key = P256.KeyAgreement.PrivateKey()
            guard credentials.write(key.rawRepresentation.base64EncodedString(), account: account) else {
                throw PairingKeyError.keyUnavailable
            }
            return key
        case .unavailable:
            throw PairingKeyError.keyUnavailable
        }
    }
}

enum PairingKeyError: Error {
    case invalidPublicKey
    case keyUnavailable
}
