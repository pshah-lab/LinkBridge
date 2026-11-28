import CryptoKit
import Foundation

/// Initiator side of pairing (what the Android app does against the Mac), for
/// apps that pair with a peer's plain port. See `PairingCode` for the protocol.
///
/// Usage: `begin()` returns the code to show. Once the user has typed it on the peer,
/// `awaitApproval()` waits for the peer user's decision and checks the peer's confirmation.
/// Then ask the user whether the peer now lists this device as paired, and call `save()` only
/// on yes: a MITM holds this side's secret, so only the peer's screen proves the codes matched.
/// `cancel()` at any point means nothing is saved.
public final class PairingClient {
    /// Whether this device already trusts the peer's device ID.
    public enum ExistingPairing: Equatable {
        case none
        /// Same pairing key as before (or an older entry without one): re-pairing replaces it.
        case samePeer
        /// The device ID is trusted under a different key: a reinstall, or an impostor.
        case keyChanged
    }

    public struct Started {
        /// Six-digit code; show it with `PairingCode.display(_:)`.
        public let code: String
        public let peerName: String
        public let existing: ExistingPairing
    }

    private struct Pending {
        let peer: PublicDeviceIdentity
        let secret: String
        let peerKey: String
        let expectedConfirmation: Data?
        var approved = false
    }

    private let endpoint: String
    private let session: URLSession
    private let trustedPeerStore: TrustedPeerStore
    private var pending: Pending?
    private var peerName = "the other device"

    /// `endpoint` is the peer's plain `host:port` (`ResolvedPeer.endpoint`).
    public init(endpoint: String, trustedPeerStore: TrustedPeerStore = TrustedPeerStore()) {
        self.endpoint = endpoint
        self.trustedPeerStore = trustedPeerStore
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 150 // per-request timeouts below are shorter
        config.timeoutIntervalForResource = 180
        session = URLSession(configuration: config)
    }

    public func begin() async throws -> Started {
        let identity = IdentityStore().loadOrCreate()
        let peer = try JSONDecoder().decode(PublicDeviceIdentity.self, from: try await request("GET", "/device"))
        peerName = peer.deviceName
        if let message = ProtocolCompat.message(ProtocolCompat.check(peer: peer.level), peerName: peer.deviceName,
                                                localName: ProtocolCompat.localName) {
            throw PairingClientError.updateRequired(message)
        }

        let body = try JSONEncoder().encode(PublicDeviceIdentity(
            deviceId: identity.deviceId, deviceName: identity.deviceName, platform: identity.platform,
            protocolVersion: ControlServer.protocolVersion, features: identity.features, publicKey: identity.publicKey,
            pairingVersion: PairingCode.pairingVersion,
            protocolLevel: ControlServer.protocolVersion,
            minProtocol: ControlServer.minProtocolVersion
        ))
        let reply = try JSONDecoder().decode(PairRequestReply.self, from: try await request("POST", "/pair/request", body: body))
        guard let commitmentText = reply.commitment, let commitment = Data(base64Encoded: commitmentText) else {
            throw PairingClientError.peerNeedsUpdate
        }
        let peerKey = try PairingKeyStore.parsePublicKey(reply.publicKey).x963Representation
        let keys = PairingKeyStore()
        // The stored key, not the cached one: the key in the code must be the one ECDH uses.
        let localKey = try PairingKeyStore.parsePublicKey(keys.publicKeyBase64).x963Representation
        // The reply is plain HTTP and the version isn't covered by the code, so a missing or
        // lower version may be an on-path downgrade. Refuse anything older than ours; the
        // version is also inside the confirmation MAC, so a rewritten one fails there.
        guard let replyVersion = reply.pairingVersion, replyVersion >= PairingCode.pairingVersion else {
            throw PairingClientError.peerNeedsUpdate
        }
        let shared = try keys.sharedSecret(peerPublicKeyBase64: reply.publicKey)

        let nonce = PairingCode.randomNonce()
        let confirmBody = try JSONSerialization.data(withJSONObject: [
            "deviceId": identity.deviceId.uuidString, "nonce": nonce.base64EncodedString()
        ])
        let confirm = try JSONDecoder().decode(PairConfirmReply.self, from: try await request("POST", "/pair/confirm", body: confirmBody))
        guard let peerNonce = Data(base64Encoded: confirm.nonce),
              PairingCode.commitment(publicKey: peerKey, nonce: peerNonce) == commitment else {
            throw PairingClientError.commitmentMismatch
        }

        let code = PairingCode.code(initiatorKey: localKey, responderKey: peerKey, initiatorNonce: nonce, responderNonce: peerNonce,
                                    initiatorId: identity.deviceId, responderId: peer.deviceId)
        let secret = PairingCode.secret(sharedSecret: shared, initiatorNonce: nonce, responderNonce: peerNonce,
                                        initiatorId: identity.deviceId, responderId: peer.deviceId)
        let expected = PairingCode.confirmation(
            secret: secret, version: replyVersion, initiatorKey: localKey, responderKey: peerKey,
            initiatorNonce: nonce, responderNonce: peerNonce, initiatorId: identity.deviceId, responderId: peer.deviceId
        )
        let peerKeyBase64 = peerKey.base64EncodedString()
        pending = Pending(peer: peer, secret: secret, peerKey: peerKeyBase64, expectedConfirmation: expected)
        return Started(code: code, peerName: DeviceNames.sanitize(peer.deviceName),
                       existing: Self.existing(trustedPeerStore.find(deviceId: peer.deviceId), peerKey: peerKeyBase64))
    }

    static func existing(_ trusted: TrustedPeer?, peerKey: String) -> ExistingPairing {
        guard let trusted else { return .none }
        guard let known = trusted.pairingKey else { return .samePeer }
        return known == peerKey ? .samePeer : .keyChanged
    }

    /// Waits (up to two minutes) for the peer user to approve, and checks the peer's
    /// confirmation. Saves nothing: call `save()` once the user confirms on this device.
    public func awaitApproval() async throws {
        guard pending != nil else { throw PairingClientError.notStarted }
        let identity = IdentityStore().loadOrCreate()
        let body = try JSONSerialization.data(withJSONObject: ["deviceId": identity.deviceId.uuidString])
        let data: Data
        do {
            data = try await request("POST", "/pair/finalize", body: body, timeout: 150)
        } catch {
            // Cancel declines on the other device too; report the cancel, not that decline.
            if pending == nil { throw PairingClientError.cancelled }
            throw error
        }
        // Cancelled while waiting: stay cancelled.
        guard var current = pending else { throw PairingClientError.cancelled }
        let reply = try? JSONDecoder().decode(PairFinalizeReply.self, from: data)
        guard PairingCode.isValidConfirmation(reply?.confirm.flatMap { Data(base64Encoded: $0) },
                                              expected: current.expectedConfirmation) else {
            self.pending = nil
            throw PairingClientError.confirmationFailed
        }
        current.approved = true
        pending = current
    }

    /// Saves the peer as trusted. Only after `awaitApproval()` succeeded and the user said the
    /// peer lists this device as paired.
    @discardableResult
    public func save() throws -> TrustedPeer {
        guard let pending else { throw PairingClientError.cancelled }
        guard pending.approved else { throw PairingClientError.notStarted }
        let peer = TrustedPeer(
            deviceId: pending.peer.deviceId, deviceName: DeviceNames.sanitize(pending.peer.deviceName), platform: pending.peer.platform,
            protocolVersion: pending.peer.protocolVersion, features: pending.peer.features,
            pairedAt: Date(), authToken: pending.secret, pairingKey: pending.peerKey
        )
        trustedPeerStore.save(peer)
        self.pending = nil
        return peer
    }

    /// Forgets the pairing: nothing is saved, even if an approval arrives later. Also tells the
    /// other device, so its dialog closes and a new pairing isn't refused while it waits.
    public func cancel() {
        guard pending != nil else { return }
        pending = nil
        let deviceId = IdentityStore().loadOrCreate().deviceId
        guard let body = try? JSONSerialization.data(withJSONObject: ["deviceId": deviceId.uuidString]) else { return }
        Task { _ = try? await request("POST", "/pair/cancel", body: body, timeout: 5) }
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
            if status == 426, let compatibility = ProtocolCompat.rejection(fromBody: data),
               let message = ProtocolCompat.message(compatibility, peerName: peerName, localName: ProtocolCompat.localName) {
                throw PairingClientError.updateRequired(message)
            }
            let detail = String(decoding: data, as: UTF8.self)
            if detail.contains("pair_declined") { throw PairingClientError.declined }
            if detail.contains("no_pending_pairing") { throw PairingClientError.expired }
            if detail.contains("pairing_busy") || detail.contains("pairing_unavailable") { throw PairingClientError.busy }
            throw PairingClientError.httpStatus(status)
        }
        return data
    }
}

private struct PairRequestReply: Decodable {
    let publicKey: String
    let commitment: String?
    let pairingVersion: Int?
}

private struct PairFinalizeReply: Decodable {
    let confirm: String?
}

private struct PairConfirmReply: Decodable {
    let nonce: String
}

public enum PairingClientError: Error, LocalizedError {
    case invalidEndpoint, peerNeedsUpdate, commitmentMismatch, notStarted, declined, expired, busy
    case confirmationFailed, cancelled
    case httpStatus(Int)
    case updateRequired(String)

    public var errorDescription: String? {
        switch self {
        case .invalidEndpoint: return "That device's address is not valid."
        case .peerNeedsUpdate: return "That device runs an older LinkBridge. Update it to pair."
        case .commitmentMismatch: return "Pairing check failed: the reply doesn't match its commitment. Someone may be intercepting this connection."
        case .notStarted: return "Start pairing first."
        case .confirmationFailed: return "Pairing check failed: the other device's confirmation doesn't match. Someone may be intercepting this connection."
        case .cancelled: return "Pairing was cancelled."
        case .declined: return "Pairing was declined on the other device."
        case .expired: return "Pairing expired. Start again."
        case .busy: return "The other device is still showing an earlier pairing request. Decline it there, or try again in two minutes."
        case let .httpStatus(status): return "The other device refused the request (error \(status)). Try again."
        case let .updateRequired(message): return message + " to pair."
        }
    }
}
