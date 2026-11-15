import CryptoKit
import Foundation
import Network

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

    /// Where the peer's plain port is: a `host:port` string, or a Bonjour service to resolve.
    private enum Target {
        case endpoint(String)
        case service(NWEndpoint, port: UInt16)
    }

    private let target: Target
    private let session: URLSession
    private var pending: (peer: PublicDeviceIdentity, secret: String)?

    /// `endpoint` is the peer's plain `host:port` (`ResolvedPeer.endpoint`).
    public convenience init(endpoint: String) {
        self.init(target: .endpoint(endpoint))
    }

    /// Pairs with a discovered peer. A peer browsed over Bonjour is reached through its service,
    /// so pairing also works over peer-to-peer Wi-Fi; URLSession can't pin that interface, so
    /// those requests go over an `NWConnection`. Other peers use URLSession as before.
    public convenience init(peer: ResolvedPeer) {
        self.init(target: peer.service.map { .service($0, port: peer.port) } ?? .endpoint(peer.endpoint))
    }

    private init(target: Target) {
        self.target = target
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 150 // per-request timeouts below are shorter
        config.timeoutIntervalForResource = 180
        session = URLSession(configuration: config)
    }

    public func begin() async throws -> Started {
        let identity = IdentityStore().loadOrCreate()
        let peer = try JSONDecoder().decode(PublicDeviceIdentity.self, from: try await request("GET", "/device"))

        let body = try JSONEncoder().encode(PublicDeviceIdentity(
            deviceId: identity.deviceId, deviceName: identity.deviceName, platform: identity.platform,
            protocolVersion: ControlServer.protocolVersion, features: identity.features, publicKey: identity.publicKey,
            pairingVersion: PairingCode.pairingVersion
        ))
        let reply = try JSONDecoder().decode(PairRequestReply.self, from: try await request("POST", "/pair/request", body: body))
        guard let commitmentText = reply.commitment, let commitment = Data(base64Encoded: commitmentText) else {
            throw PairingClientError.peerNeedsUpdate
        }
        let peerKey = try PairingKeyStore.parsePublicKey(reply.publicKey).x963Representation
        let localKey = try PairingKeyStore.parsePublicKey(identity.publicKey).x963Representation
        let keys = PairingKeyStore()
        let v3 = (reply.pairingVersion ?? 1) >= 3
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

        let code: String
        let secret: String
        if v3 {
            code = PairingCode.code(initiatorKey: localKey, responderKey: peerKey, initiatorNonce: nonce, responderNonce: peerNonce,
                                    initiatorId: identity.deviceId, responderId: peer.deviceId)
            secret = PairingCode.secret(sharedSecret: shared, initiatorNonce: nonce, responderNonce: peerNonce,
                                        initiatorId: identity.deviceId, responderId: peer.deviceId)
        } else {
            code = PairingCode.code(initiatorKey: localKey, responderKey: peerKey, initiatorNonce: nonce, responderNonce: peerNonce)
            secret = Data(SHA256.hash(data: shared)).base64EncodedString()
        }
        pending = (peer, secret)
        return Started(code: code, peerName: DeviceNames.sanitize(peer.deviceName))
    }

    /// Waits (up to two minutes) for the peer user to approve, then saves the peer as trusted.
    public func finish() async throws -> TrustedPeer {
        guard let pending else { throw PairingClientError.notStarted }
        let identity = IdentityStore().loadOrCreate()
        let body = try JSONSerialization.data(withJSONObject: ["deviceId": identity.deviceId.uuidString])
        _ = try await request("POST", "/pair/finalize", body: body, timeout: 150)
        let peer = TrustedPeer(
            deviceId: pending.peer.deviceId, deviceName: DeviceNames.sanitize(pending.peer.deviceName), platform: pending.peer.platform,
            protocolVersion: pending.peer.protocolVersion, features: pending.peer.features,
            pairedAt: Date(), authToken: pending.secret
        )
        TrustedPeerStore().save(peer)
        self.pending = nil
        return peer
    }

    private func request(_ method: String, _ path: String, body: Data? = nil, timeout: TimeInterval = 15) async throws -> Data {
        let status: Int
        let data: Data
        switch target {
        case let .endpoint(endpoint):
            guard let url = URL(string: "http://\(endpoint)\(path)") else { throw PairingClientError.invalidEndpoint }
            var request = URLRequest(url: url, timeoutInterval: timeout)
            request.httpMethod = method
            if let body {
                request.httpBody = body
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            let (responseData, response) = try await session.data(for: request)
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            data = responseData
        case let .service(service, port):
            (status, data) = try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.global().async {
                    continuation.resume(with: Result {
                        try Self.directRequest(service: service, port: port, method: method, path: path, body: body, timeout: timeout)
                    })
                }
            }
        }
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

extension PairingClient {
    /// One HTTP/1.1 request to the peer's plain port over the interface its service resolved
    /// on. The server answers with `Connection: close`. Blocks; call off the main thread.
    private static func directRequest(service: NWEndpoint, port: UInt16, method: String, path: String,
                                      body: Data?, timeout: TimeInterval) throws -> (Int, Data) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw PairingClientError.invalidEndpoint }
        let route = try DirectRoute.resolve(service)
        let connection = NWConnection(host: route.host, port: nwPort,
                                      using: DirectRoute.tcpParameters(requiredInterface: route.interface))
        defer { connection.cancel() }
        try FileSender.waitUntilReady(connection, queue: DispatchQueue(label: "linkbridge.pairingclient"))
        var head = [
            "\(method) \(path) HTTP/1.1",
            "Host: \(FileSender.hostHeader(route.host))",
            "Content-Length: \(body?.count ?? 0)",
            "Connection: close"
        ]
        if body != nil { head.append("Content-Type: application/json") }
        try FileSender.sendSync(connection, Data((head + ["", ""]).joined(separator: "\r\n").utf8) + (body ?? Data()))
        return try FileSender.receiveResponse(connection, timeout: timeout)
    }
}

private struct PairRequestReply: Decodable {
    let publicKey: String
    let commitment: String?
    let pairingVersion: Int?
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
