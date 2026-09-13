import CryptoKit
import Foundation

final class PairingKeyStore {
    private let keychain = KeychainStore()
    private let account = "identity.pairingPrivateKey"

    var publicKeyBase64: String { privateKey.publicKey.rawRepresentation.base64EncodedString() }

    func deriveSecret(peerPublicKeyBase64: String) throws -> String {
        guard let data = Data(base64Encoded: peerPublicKeyBase64) else { throw PairingKeyError.invalidPublicKey }
        let peer = try P256.KeyAgreement.PublicKey(rawRepresentation: data)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
        let digest = SHA256.hash(data: shared.withUnsafeBytes { Data($0) })
        return Data(digest).base64EncodedString()
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

private enum PairingKeyError: Error { case invalidPublicKey }
