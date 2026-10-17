import Foundation

/// Initiator side of pairing (what the Android app does against the Mac), for
/// apps that pair with a peer's plain port. See `PairingCode` for the protocol.
///
/// Usage: `begin()` returns the code to show; once the user sees it matches the
/// peer's screen, `finish()` waits for the peer user's decision and saves the peer.
public final class PairingClient {
    public struct Started {
        /// Six-digit code; show it with `PairingCode.display(_:)`.
        public let code: String
        public let peerName: String
    }

    private let endpoint: String
    private let session: URLSession
    private var pending: (peer: PublicDeviceIdentity, secret: String)?

    /// `endpoint` is the peer's plain `host:port` (`ResolvedPeer.endpoint`).
    public init(endpoint: String) {
        self.endpoint = endpoint
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 180
        session = URLSession(configuration: config)
    }

    public func begin() async throws -> Started {
        let identity = IdentityStore().loadOrCreate()
        let peer = try JSONDecoder().decode(PublicDeviceIdentity.self, from: try await request("GET", "/device"))

        let body = try JSONEncoder().encode(PublicDeviceIdentity(
            deviceId: identity.deviceId, deviceName: identity.deviceName, platform: identity.platform,
            protocolVersion: ControlServer.protocolVersion, features: identity.features, publicKey: identity.publicKey
        ))
        let reply = try JSONDecoder().decode(PairRequestReply.self, from: try await request("POST", "/pair/request", body: body))
        guard let commitmentText = reply.commitment, let commitment = Data(base64Encoded: commitmentText) else {
            throw PairingClientError.peerNeedsUpdate
        }
        let peerKey = try PairingKeyStore.parsePublicKey(reply.publicKey).x963Representation
        let localKey = try PairingKeyStore.parsePublicKey(identity.publicKey).x963Representation
        let secret = try PairingKeyStore().deriveSecret(peerPublicKeyBase64: reply.publicKey)

        let nonce = PairingCode.randomNonce()
        let confirmBody = try JSONSerialization.data(withJSONObject: [
            "deviceId": identity.deviceId.uuidString, "nonce": nonce.base64EncodedString()
        ])
        let confirm = try JSONDecoder().decode(PairConfirmReply.self, from: try await request("POST", "/pair/confirm", body: confirmBody))
        guard let peerNonce = Data(base64Encoded: confirm.nonce),
              PairingCode.commitment(publicKey: peerKey, nonce: peerNonce) == commitment else {
            throw PairingClientError.commitmentMismatch
        }

        pending = (peer, secret)
        let code = PairingCode.code(initiatorKey: localKey, responderKey: peerKey, initiatorNonce: nonce, responderNonce: peerNonce)
        return Started(code: code, peerName: peer.deviceName)
    }

    /// Waits (up to two minutes) for the peer user to approve, then saves the peer as trusted.
    public func finish() async throws -> TrustedPeer {
        guard let pending else { throw PairingClientError.notStarted }
        let identity = IdentityStore().loadOrCreate()
        let body = try JSONSerialization.data(withJSONObject: ["deviceId": identity.deviceId.uuidString])
        _ = try await request("POST", "/pair/finalize", body: body, timeout: 150)
        let peer = TrustedPeer(
            deviceId: pending.peer.deviceId, deviceName: pending.peer.deviceName, platform: pending.peer.platform,
            protocolVersion: pending.peer.protocolVersion, features: pending.peer.features,
            pairedAt: Date(), authToken: pending.secret
        )
        TrustedPeerStore().save(peer)
        self.pending = nil
        return peer
    }

    private func request(_ method: String, _ path: String, body: Data? = nil, timeout: TimeInterval = 15) async throws -> Data {
        guard let url = URL(string: "http://\(endpoint)\(path)") else { throw PairingClientError.invalidEndpoint }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(status) else {
            let detail = String(decoding: data, as: UTF8.self)
            if detail.contains("pair_declined") { throw PairingClientError.declined }
            if detail.contains("no_pending_pairing") { throw PairingClientError.expired }
            if detail.contains("pairing_busy") { throw PairingClientError.busy }
            throw PairingClientError.httpStatus(status)
        }
        return data
    }
}

private struct PairRequestReply: Decodable {
    let publicKey: String
    let commitment: String?
}

private struct PairConfirmReply: Decodable {
    let nonce: String
}

public enum PairingClientError: Error, LocalizedError {
    case invalidEndpoint, peerNeedsUpdate, commitmentMismatch, notStarted, declined, expired, busy
    case httpStatus(Int)

    public var errorDescription: String? {
        switch self {
        case .invalidEndpoint: return "That device's address is not valid."
        case .peerNeedsUpdate: return "That device runs an older LinkBridge. Update it to pair."
        case .commitmentMismatch: return "Pairing check failed: the reply doesn't match its commitment. Someone may be intercepting this connection."
        case .notStarted: return "Start pairing first."
        case .declined: return "Pairing was declined on the other device."
        case .expired: return "Pairing expired. Start again."
        case .busy: return "The other device is already showing a pairing request."
        case let .httpStatus(status): return "Pairing failed (HTTP \(status))."
        }
    }
}
