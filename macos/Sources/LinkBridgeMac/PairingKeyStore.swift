import CryptoKit
import Foundation

final class PairingKeyStore {
    private let keychain = KeychainStore()
    private let account = "identity.pairingPrivateKey"

    /// Uncompressed X9.63 point (0x04 || X || Y), matching what Android sends.
    var publicKeyBase64: String { privateKey.publicKey.x963Representation.base64EncodedString() }

    func deriveSecret(peerPublicKeyBase64: String) throws -> String {
        let peer = try PairingKeyStore.parsePublicKey(peerPublicKeyBase64)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
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

    private var privateKey: P256.KeyAgreement.PrivateKey {
        if let stored = keychain.read(account: account),
           let data = Data(base64Encoded: stored),
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) { return key }
        let key = P256.KeyAgreement.PrivateKey()
        keychain.write(key.rawRepresentation.base64EncodedString(), account: account)
        return key
    }
}

enum PairingKeyError: Error { case invalidPublicKey }
