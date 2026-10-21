import CryptoKit
import Foundation
import Network
import Security

/// TLS for traffic between paired devices, keyed from the pairing secret (TLS-PSK).
///
/// Certificates would need a SecIdentity, which on macOS lives in the Keychain; ad-hoc
/// signed builds then prompt for the login-keychain password on every rebuild. PSK needs no
/// certificates and authenticates both sides by possession of the pairing secret.
///
/// Settings verified against BouncyCastle (Android): TLS 1.2 only (Network.framework does
/// not complete TLS 1.3 external-PSK handshakes) with ECDHE-PSK for forward secrecy.
public enum SecureTransport {
    /// Derives the TLS pre-shared key from a pairing secret, separate from the upload
    /// encryption/HMAC key. HKDF-SHA256, salt "linkbridge-tls-v1", info "psk", 32 bytes.
    public static func psk(fromSecret secret: String) -> Data? {
        guard let ikm = Data(base64Encoded: secret) else { return nil }
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: Data("linkbridge-tls-v1".utf8),
            info: Data("psk".utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }

    /// PSK identity a device presents when it connects: its device ID, lowercased so the
    /// Mac's uppercase UUIDs and Android's lowercase ones compare equal.
    public static func identity(for deviceId: String) -> String {
        deviceId.lowercased()
    }

    /// Server options accepting any of the given peers, selected by the client's identity.
    /// The identity each client used is recorded for `negotiatedIdentity(of:)`.
    static func serverParameters(keys: [(identity: String, psk: Data)]) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        configure(options)
        // Network.framework has no getter for the PSK a server negotiated, but this block is
        // given the client's identity during the handshake. Answering with that identity picks
        // its key as usual (a wrong key still fails); remembering it lets the upload handler
        // check the device ID it claims. Runs on the main queue, like request handling.
        sec_protocol_options_set_pre_shared_key_selection_block(options, { metadata, identity, complete in
            if let identity {
                let name = String(decoding: Data(identity as DispatchData), as: UTF8.self)
                negotiatedIdentities.setObject(name as NSString, forKey: metadata as AnyObject)
            }
            complete(identity)
        }, .main)
        // With no paired devices, still install one random key so the listener is valid;
        // nobody can complete a handshake with it.
        let entries = keys.isEmpty ? [(identity: UUID().uuidString, psk: SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })] : keys
        for entry in entries {
            sec_protocol_options_add_pre_shared_key(options, dispatchData(entry.psk), dispatchData(Data(entry.identity.utf8)))
        }
        return NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
    }

    /// Metadata object -> PSK identity, dropped with the connection. Main queue only.
    private static let negotiatedIdentities = NSMapTable<AnyObject, NSString>(keyOptions: .weakMemory, valueOptions: .strongMemory)

    /// The PSK identity the client of a server connection authenticated with, or nil.
    /// Main queue only.
    static func negotiatedIdentity(of connection: NWConnection) -> String? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        return negotiatedIdentities.object(forKey: metadata.securityProtocolMetadata as AnyObject) as String?
    }

    static func clientParameters(identity: String, psk: Data) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        configure(options)
        sec_protocol_options_add_pre_shared_key(options, dispatchData(psk), dispatchData(Data(identity.utf8)))
        return NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
    }

    private static func configure(_ options: sec_protocol_options_t) {
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(options, .TLSv12)
        sec_protocol_options_append_tls_ciphersuite(
            options, tls_ciphersuite_t(rawValue: UInt16(TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256))!
        )
    }

    private static func dispatchData(_ data: Data) -> __DispatchData {
        data.withUnsafeBytes { DispatchData(bytes: $0) } as __DispatchData
    }
}
