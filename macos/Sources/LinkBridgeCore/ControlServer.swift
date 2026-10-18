import Foundation
import Network
import CryptoKit

public final class ControlServer {
    public static let serviceType = "_linkbridge._tcp"
    /// 2: transfers go over the TLS-PSK port advertised as `tlsPort`; the plain port only
    /// serves device info and pairing.
    public static let protocolVersion = 2

    public let identity: DeviceIdentity
    /// Plain port (device info and pairing). Equals the preferred port unless it was taken.
    public private(set) var port: UInt16
    /// TLS-PSK port for transfers between paired devices.
    public private(set) var securePort: UInt16

    private let preferredPort: UInt16
    private let preferredSecurePort: UInt16
    private var listener: NWListener?
    private var secureListener: NWListener?
    private let trustedPeerStore = TrustedPeerStore()
    private let pairDecision: PairDecision
    private let transferDecision: TransferDecision
    private let transferProgress: (Int64, Int64) -> Void
    private let transferReceived: (IncomingTransferRequest, URL) -> Void
    private let transferWithdrawn: (UUID) -> Void
    private static let replayCache = ReplayCache()
    private static let pendingPairings = PendingPairings()
    /// Main queue only, like all request handling.
    private static var approvalInProgress = false
    /// How long a pairing waits for the Mac user. Phones give up on /pair/finalize after 120 s,
    /// so this stays below that; a later Accept would pair only the Mac's side.
    /// Variable so the checks can shorten it.
    static var pairingLifetime: TimeInterval = 110
    /// How long an incoming file waits for approval before it is declined. Senders time out
    /// after 120 s, so the Mac gives up first and clears the request.
    public internal(set) static var transferApprovalTimeout: TimeInterval = 110

    /// Answers a pairing request by calling the completion with the user's decision. Called on
    /// the main queue; the completion may be called from any thread, and only its first call counts.
    public typealias PairDecision = (IncomingPairRequest, @escaping (Bool) -> Void) -> Void
    /// Same contract for incoming files.
    public typealias TransferDecision = (IncomingTransferRequest, @escaping (Bool) -> Void) -> Void

    public init(
        identity: DeviceIdentity,
        preferredPort: UInt16 = 49152,
        preferredSecurePort: UInt16 = 49162,
        pairDecision: @escaping PairDecision,
        transferDecision: @escaping TransferDecision,
        transferProgress: @escaping (Int64, Int64) -> Void = { _, _ in },
        transferReceived: @escaping (IncomingTransferRequest, URL) -> Void = { _, _ in },
        transferWithdrawn: @escaping (UUID) -> Void = { _ in }
    ) {
        self.identity = identity
        self.preferredPort = preferredPort
        self.preferredSecurePort = preferredSecurePort
        self.port = preferredPort
        self.securePort = preferredSecurePort
        self.pairDecision = pairDecision
        self.transferDecision = transferDecision
        self.transferProgress = transferProgress
        self.transferReceived = transferReceived
        self.transferWithdrawn = transferWithdrawn
    }

    /// Synchronous approvals, for callers that can answer on the spot (CLI, checks).
    public convenience init(
        identity: DeviceIdentity,
        preferredPort: UInt16 = 49152,
        preferredSecurePort: UInt16 = 49162,
        pairApproval: @escaping (IncomingPairRequest) -> Bool = { _ in false },
        transferApproval: @escaping (IncomingTransferRequest) -> Bool = { _ in true },
        transferProgress: @escaping (Int64, Int64) -> Void = { _, _ in },
        transferCompleted: @escaping (IncomingTransferRequest) -> Void = { _ in }
    ) {
        self.init(identity: identity, preferredPort: preferredPort, preferredSecurePort: preferredSecurePort,
                  pairDecision: { request, done in done(pairApproval(request)) },
                  transferDecision: { request, done in done(transferApproval(request)) },
                  transferProgress: transferProgress,
                  transferReceived: { request, _ in transferCompleted(request) })
    }

    /// Starts the TLS listener, then the plain listener, each on its preferred port or any
    /// free port if taken, and advertises `_linkbridge._tcp` with both ports.
    /// `onReady` runs on the main queue with the plain port, or nil on failure.
    public func start(onReady: @escaping (UInt16?) -> Void = { _ in }) {
        startSecure(on: NWEndpoint.Port(rawValue: preferredSecurePort) ?? .any) { [weak self] securePort in
            guard let self, securePort != nil else { onReady(nil); return }
            self.startPlain(on: NWEndpoint.Port(rawValue: self.preferredPort) ?? .any, onReady: onReady)
        }
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        secureListener?.cancel()
        secureListener = nil
    }

    /// TLS-PSK options are fixed when a listener starts, so restart the secure listener to
    /// pick up a newly paired device. Open transfers keep running on their connections.
    public func reloadTrustedPeers(completion: @escaping () -> Void = {}) {
        secureListener?.cancel()
        secureListener = nil
        startSecure(on: NWEndpoint.Port(rawValue: securePort) ?? .any) { [weak self] _ in
            self?.updateAdvertisement()
            completion()
        }
    }

    private func startPlain(on requestedPort: NWEndpoint.Port, onReady: @escaping (UInt16?) -> Void) {
        let listener: NWListener
        do {
            listener = try NWListener(using: .tcp, on: requestedPort)
        } catch {
            print("Control server: could not create listener: \(error)")
            onReady(nil)
            return
        }
        self.listener = listener
        listener.service = advertisedService()

        listener.newConnectionHandler = { [weak self, identity, trustedPeerStore, pairDecision] connection in
            connection.start(queue: .main)
            Self.handlePlain(
                connection: connection, identity: identity, trustedPeerStore: trustedPeerStore,
                pairDecision: pairDecision, onPaired: { self?.reloadTrustedPeers() }
            )
        }

        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = listener?.port?.rawValue ?? self.port
                print("Control server: listening on port \(self.port), TLS on \(self.securePort)")
                onReady(self.port)
            case let .failed(error):
                listener?.cancel()
                if case .posix(.EADDRINUSE) = error, requestedPort != .any {
                    print("Control server: port \(requestedPort) is in use, picking another")
                    self.startPlain(on: .any, onReady: onReady)
                } else {
                    print("Control server failed: \(error)")
                    onReady(nil)
                }
            default:
                break
            }
        }

        listener.start(queue: .main)
    }

    private func startSecure(on requestedPort: NWEndpoint.Port, onReady: @escaping (UInt16?) -> Void) {
        let keys = trustedPeerStore.load().compactMap { peer -> (identity: String, psk: Data)? in
            guard let secret = peer.authToken, let psk = SecureTransport.psk(fromSecret: secret) else { return nil }
            return (SecureTransport.identity(for: peer.deviceId.uuidString), psk)
        }
        let listener: NWListener
        do {
            listener = try NWListener(using: SecureTransport.serverParameters(keys: keys), on: requestedPort)
        } catch {
            print("Secure server: could not create listener: \(error)")
            onReady(nil)
            return
        }
        self.secureListener = listener

        listener.newConnectionHandler = { [transferDecision, transferProgress, transferReceived, transferWithdrawn] connection in
            connection.start(queue: .main)
            Self.handleSecure(
                connection: connection,
                transferDecision: transferDecision,
                transferProgress: transferProgress,
                transferReceived: transferReceived,
                transferWithdrawn: transferWithdrawn
            )
        }

        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.securePort = listener?.port?.rawValue ?? self.securePort
                onReady(self.securePort)
            case let .failed(error):
                listener?.cancel()
                if case .posix(.EADDRINUSE) = error, requestedPort != .any {
                    print("Secure server: port \(requestedPort) is in use, picking another")
                    self.startSecure(on: .any, onReady: onReady)
                } else {
                    print("Secure server failed: \(error)")
                    onReady(nil)
                }
            default:
                break
            }
        }

        listener.start(queue: .main)
    }

    private func updateAdvertisement() {
        listener?.service = advertisedService()
    }

    private func advertisedService() -> NWListener.Service {
        var txt = NWTXTRecord()
        txt["deviceId"] = identity.deviceId.uuidString
        txt["deviceName"] = identity.deviceName
        txt["platform"] = identity.platform
        txt["protocol"] = String(Self.protocolVersion)
        txt["tlsPort"] = String(securePort)
        txt["features"] = identity.features.joined(separator: ",")
        return NWListener.Service(name: identity.deviceName, type: Self.serviceType, domain: nil, txtRecord: txt)
    }

    /// TLS-PSK connections from paired devices: transfers only.
    private static func handleSecure(
        connection: NWConnection,
        transferDecision: @escaping TransferDecision,
        transferProgress: @escaping (Int64, Int64) -> Void,
        transferReceived: @escaping (IncomingTransferRequest, URL) -> Void,
        transferWithdrawn: @escaping (UUID) -> Void
    ) {
        receiveRequestHead(connection: connection) { head in
            guard let head else {
                connection.cancel()
                return
            }
            if head.startLine.hasPrefix("POST /transfer/upload") {
                handleUpload(
                    connection: connection,
                    head: head,
                    transferDecision: transferDecision,
                    transferProgress: transferProgress,
                    transferReceived: transferReceived,
                    transferWithdrawn: transferWithdrawn
                )
            } else if head.startLine.hasPrefix("GET /health") {
                sendJSON(connection: connection, status: "200 OK", body: Data(#"{"status":"ok","tls":true}"#.utf8))
            } else {
                sendJSON(connection: connection, status: "404 Not Found", body: Data(#"{"error":"not_found"}"#.utf8))
            }
        }
    }

    /// Plain connections: device info and pairing (protected by the six-digit code).
    private static func handlePlain(
        connection: NWConnection,
        identity: DeviceIdentity,
        trustedPeerStore: TrustedPeerStore,
        pairDecision: @escaping PairDecision,
        onPaired: @escaping () -> Void
    ) {
        receiveRequestHead(connection: connection) { head in
            guard let head else {
                connection.cancel()
                return
            }

            if head.startLine.hasPrefix("POST /transfer/upload") {
                // Older apps upload in plaintext; tell them to update instead of failing silently.
                sendJSON(connection: connection, status: "426 Upgrade Required", body: Data(#"{"error":"tls_required"}"#.utf8))
                return
            }

            receiveSmallBodyIfNeeded(connection: connection, head: head) { request in
                if request.startLine.hasPrefix("POST /pair/finalize") {
                    handlePairFinalize(body: request.body) { response in
                        sendJSON(connection: connection, status: response.status, body: response.body)
                    }
                    return
                }

                let responseBody: Data
                let status: String

                if request.startLine.hasPrefix("GET /health") {
                    responseBody = #"{"status":"ok"}"#.data(using: .utf8)!
                    status = "200 OK"
                } else if request.startLine.hasPrefix("GET /device") {
                    let publicIdentity = PublicDeviceIdentity(
                        deviceId: identity.deviceId,
                        deviceName: identity.deviceName,
                        platform: identity.platform,
                        protocolVersion: ControlServer.protocolVersion,
                        features: identity.features,
                        publicKey: identity.publicKey
                    )
                    responseBody = (try? JSONEncoder().encode(publicIdentity)) ?? Data()
                    status = "200 OK"
                } else if request.startLine.hasPrefix("POST /pair/request") {
                    let pairResponse = handlePairRequest(body: request.body, identity: identity, source: sourceHost(of: connection))
                    responseBody = pairResponse.body
                    status = pairResponse.status
                } else if request.startLine.hasPrefix("POST /pair/confirm") {
                    let pairResponse = handlePairConfirm(body: request.body, identity: identity, trustedPeerStore: trustedPeerStore, pairDecision: pairDecision, onPaired: onPaired)
                    responseBody = pairResponse.body
                    status = pairResponse.status
                } else {
                    responseBody = #"{"error":"not_found"}"#.data(using: .utf8)!
                    status = "404 Not Found"
                }

                sendJSON(connection: connection, status: status, body: responseBody)
            }
        }
    }

    private static func receiveRequestHead(connection: NWConnection, completion: @escaping (HTTPRequestHead?) -> Void) {
        var buffer = Data()
        let separator = Data("\r\n\r\n".utf8)

        func receiveMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let data {
                    buffer.append(data)
                }

                if buffer.count > 64 * 1024 {
                    completion(nil)
                    return
                }

                if error != nil {
                    completion(nil)
                    return
                }

                if let headerRange = buffer.range(of: separator) {
                    let head = HTTPRequestHead(
                        headerData: buffer[..<headerRange.lowerBound],
                        initialBody: Data(buffer[headerRange.upperBound...])
                    )
                    completion(head)
                    return
                }

                if isComplete {
                    completion(nil)
                    return
                }

                receiveMore()
            }
        }

        receiveMore()
    }

    private static func receiveSmallBodyIfNeeded(
        connection: NWConnection,
        head: HTTPRequestHead,
        completion: @escaping (HTTPRequest) -> Void
    ) {
        let expectedLength = head.contentLength ?? 0
        guard expectedLength >= 0, expectedLength <= 64 * 1024 else {
            completion(HTTPRequest(startLine: head.startLine, headers: head.headers, body: Data()))
            return
        }
        var body = head.initialBody

        guard body.count < expectedLength else {
            completion(HTTPRequest(startLine: head.startLine, headers: head.headers, body: body))
            return
        }

        func receiveMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if error != nil {
                    completion(HTTPRequest(startLine: head.startLine, headers: head.headers, body: body))
                    return
                }

                if let data {
                    body.append(data)
                }

                if body.count >= expectedLength {
                    completion(HTTPRequest(startLine: head.startLine, headers: head.headers, body: body))
                    return
                }

                // The sender closed before sending the whole body. Don't rely on the next read
                // failing: asking again after EOF could spin the main queue.
                if isComplete {
                    sendJSON(connection: connection, status: "400 Bad Request", body: Data(#"{"error":"truncated_body"}"#.utf8))
                    return
                }

                receiveMore()
            }
        }

        receiveMore()
    }

    /// Caps on what a pairing request may keep in memory until it expires.
    /// Variable so the checks can lower it.
    static var maxPendingPairings = 8
    static let maxDeviceNameLength = 64
    static let maxFeatures = 16
    static let maxFeatureLength = 32

    /// Main queue only. For the checks.
    static var pendingPairingCount: Int { pendingPairings.count }

    private static func handlePairRequest(body: Data, identity: DeviceIdentity, source: String) -> HTTPResponse {
        guard var peerIdentity = try? JSONDecoder().decode(PairRequestIdentity.self, from: body),
              peerIdentity.features.count <= maxFeatures,
              peerIdentity.features.allSatisfy({ $0.count <= maxFeatureLength }) else {
            return jsonError("400 Bad Request", "invalid_pair_request")
        }
        peerIdentity.deviceName = String(peerIdentity.deviceName.prefix(maxDeviceNameLength))
        guard let peerPublicKey = peerIdentity.publicKey,
              let peerKey = try? PairingKeyStore.parsePublicKey(peerPublicKey),
              let sharedSecret = try? PairingKeyStore().deriveSecret(peerPublicKeyBase64: peerPublicKey),
              let macKey = try? PairingKeyStore.parsePublicKey(identity.publicKey) else {
            return jsonError("400 Bad Request", "missing_public_key")
        }

        let nonce = PairingCode.randomNonce()
        let macKeyData = macKey.x963Representation
        let started = pendingPairings.begin(PendingPairing(
            peer: peerIdentity,
            source: source,
            peerKey: peerKey.x963Representation,
            macKey: macKeyData,
            sharedSecret: sharedSecret,
            nonce: nonce
        ), limit: maxPendingPairings)
        guard started else {
            return jsonError("429 Too Many Requests", "too_many_pairings")
        }

        let commitment = PairingCode.commitment(publicKey: macKeyData, nonce: nonce).base64EncodedString()
        let json = #"{"status":"pending","publicKey":"\#(macKeyData.base64EncodedString())","commitment":"\#(commitment)"}"#
        return HTTPResponse(status: "200 OK", body: Data(json.utf8))
    }

    private static func handlePairConfirm(
        body: Data,
        identity: DeviceIdentity,
        trustedPeerStore: TrustedPeerStore,
        pairDecision: @escaping PairDecision,
        onPaired: @escaping () -> Void
    ) -> HTTPResponse {
        guard let confirm = try? JSONDecoder().decode(PairConfirmBody.self, from: body),
              let peerNonce = Data(base64Encoded: confirm.nonce), peerNonce.count == 32 else {
            return jsonError("400 Bad Request", "invalid_pair_confirm")
        }
        // One pairing dialog at a time, so a second request can't stack a look-alike dialog
        // on top of the one the user is reading. Checked before the nonce is consumed.
        guard !approvalInProgress else {
            return jsonError("409 Conflict", "pairing_busy")
        }
        guard let pending = pendingPairings.confirm(deviceId: confirm.deviceId, peerNonce: peerNonce) else {
            return jsonError("409 Conflict", "no_pending_pairing")
        }

        let code = PairingCode.code(
            initiatorKey: pending.peerKey,
            responderKey: pending.macKey,
            initiatorNonce: peerNonce,
            responderNonce: pending.nonce
        )
        let request = IncomingPairRequest(
            id: UUID(),
            deviceName: pending.peer.deviceName,
            deviceId: pending.peer.deviceId,
            platform: pending.peer.platform,
            code: code,
            replacesExisting: trustedPeerStore.find(deviceId: pending.peer.deviceId) != nil,
            expiresAt: pending.expiresAt
        )

        // Reply first so the phone can show its code while the Mac user decides.
        approvalInProgress = true
        var decided = false
        // Main queue only. The first answer wins; the expiry timer answers "no" if the user doesn't.
        func decide(_ approved: Bool) {
            guard !decided else { return }
            decided = true
            approvalInProgress = false
            if approved {
                trustedPeerStore.save(
                    TrustedPeer(
                        deviceId: pending.peer.deviceId,
                        deviceName: pending.peer.deviceName,
                        platform: pending.peer.platform,
                        protocolVersion: pending.peer.protocolVersion,
                        features: pending.peer.features,
                        pairedAt: Date(),
                        authToken: pending.sharedSecret
                    )
                )
                onPaired()
            }
            pendingPairings.decide(deviceId: pending.peer.deviceId, approved: approved)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, pending.expiresAt.timeIntervalSinceNow)) { decide(false) }
        DispatchQueue.main.async {
            pairDecision(request) { approved in
                DispatchQueue.main.async { decide(approved && Date() < pending.expiresAt) }
            }
        }

        let json = #"{"status":"awaiting_approval","nonce":"\#(pending.nonce.base64EncodedString())"}"#
        return HTTPResponse(status: "200 OK", body: Data(json.utf8))
    }

    private static func handlePairFinalize(body: Data, completion: @escaping (HTTPResponse) -> Void) {
        guard let finalize = try? JSONDecoder().decode(PairFinalizeBody.self, from: body) else {
            completion(jsonError("400 Bad Request", "invalid_pair_finalize"))
            return
        }
        let waiting = pendingPairings.awaitDecision(deviceId: finalize.deviceId) { approved in
            completion(approved
                ? HTTPResponse(status: "200 OK", body: Data(#"{"status":"paired"}"#.utf8))
                : jsonError("403 Forbidden", "pair_declined"))
        }
        if !waiting {
            completion(jsonError("409 Conflict", "no_pending_pairing"))
        }
    }

    private static func jsonError(_ status: String, _ code: String) -> HTTPResponse {
        HTTPResponse(status: status, body: Data(#"{"error":"\#(code)"}"#.utf8))
    }

    private static func handleUpload(
        connection: NWConnection,
        head: HTTPRequestHead,
        transferDecision: @escaping TransferDecision,
        transferProgress: @escaping (Int64, Int64) -> Void,
        transferReceived: @escaping (IncomingTransferRequest, URL) -> Void,
        transferWithdrawn: @escaping (UUID) -> Void
    ) {
        guard let contentLength = head.contentLength, contentLength >= 0 else {
            sendJSON(connection: connection, status: "411 Length Required", body: #"{"error":"missing_content_length"}"#.data(using: .utf8)!)
            return
        }

        guard let senderId = head.headers["x-linkbridge-device-id"],
              let senderUUID = UUID(uuidString: senderId),
              let timestamp = head.headers["x-linkbridge-timestamp"],
              let transferId = head.headers["x-linkbridge-transfer-id"],
              let transferUUID = UUID(uuidString: transferId),
              let encryption = head.headers["x-linkbridge-encryption"],
              let suppliedSignature = head.headers["x-linkbridge-signature"],
              let peer = TrustedPeerStore().find(deviceId: senderUUID),
              let authToken = peer.authToken else {
            sendJSON(connection: connection, status: "401 Unauthorized", body: #"{"error":"unpaired_or_unsigned"}"#.data(using: .utf8)!)
            return
        }

        guard encryption == Security.encryptionScheme else {
            sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"encryption_required"}"#.data(using: .utf8)!)
            return
        }

        guard let timestampMillis = Int64(timestamp),
              abs(Date().timeIntervalSince1970 * 1000 - Double(timestampMillis)) <= 5 * 60 * 1000 else {
            sendJSON(connection: connection, status: "401 Unauthorized", body: #"{"error":"stale_transfer"}"#.data(using: .utf8)!)
            return
        }

        let signaturePayload = [
            "POST",
            "/transfer/upload",
            senderId,
            timestamp,
            transferId,
            Security.encryptionScheme,
            String(contentLength)
        ].joined(separator: "\n")

        guard let expectedSignature = Security.hmacSHA256Base64(secret: authToken, message: signaturePayload),
              Security.timingSafeEquals(expectedSignature, suppliedSignature) else {
            sendJSON(connection: connection, status: "401 Unauthorized", body: #"{"error":"invalid_signature"}"#.data(using: .utf8)!)
            return
        }

        let replayKey = "\(senderId):\(transferId)"
        guard Self.replayCache.reserve(replayKey) else {
            sendJSON(connection: connection, status: "409 Conflict", body: #"{"error":"replayed_transfer"}"#.data(using: .utf8)!)
            return
        }

        do {
            let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            let directory = downloads.appendingPathComponent("LinkBridge", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

            let decryptor = try ChunkedDecryptor(secret: authToken)
            var hasher = SHA256()
            var receivedEncrypted = 0
            var received = 0
            var metadata: IncomingFileMetadata?
            var completedRequest: IncomingTransferRequest?
            var destination: URL?
            var fileHandle: FileHandle?
            // While the user decides, reading pauses; plaintext already decrypted waits here.
            var awaiting: (request: IncomingTransferRequest, metadata: IncomingFileMetadata)?
            var heldPlaintext: [Data] = []
            var askedForDecision = false

            func consumePlaintext(_ data: Data) throws {
                guard !data.isEmpty else { return }
                guard let fileHandle else { throw UploadError.outputNotReady }
                try fileHandle.write(contentsOf: data)
                hasher.update(data: data)
                received += data.count
            }

            func consumeEncrypted(_ data: Data) throws {
                guard !data.isEmpty else { return }
                let remaining = contentLength - receivedEncrypted
                guard remaining > 0 else { return }
                let chunk = Data(data.prefix(remaining))
                receivedEncrypted += chunk.count
                try decryptor.consume(chunk) { plaintext in
                    if metadata == nil && awaiting == nil {
                        let parsed = try JSONDecoder().decode(IncomingFileMetadata.self, from: plaintext)
                        guard parsed.transferId == transferUUID else { throw UploadError.invalidMetadata }
                        let fileName = sanitizeFileName(parsed.fileName)
                        guard !fileName.isEmpty else { throw UploadError.invalidMetadata }
                        let request = IncomingTransferRequest(
                            id: transferUUID,
                            senderName: peer.deviceName,
                            senderId: senderUUID,
                            fileName: fileName,
                            bytes: parsed.bytes
                        )
                        awaiting = (request, IncomingFileMetadata(transferId: parsed.transferId, fileName: fileName, bytes: parsed.bytes, sha256: parsed.sha256))
                        return
                    }
                    if fileHandle == nil {
                        heldPlaintext.append(plaintext)
                        return
                    }

                    try consumePlaintext(plaintext)
                    if let metadata {
                        transferProgress(Int64(received), Int64(metadata.bytes))
                    }
                }
            }

            try consumeEncrypted(head.initialBody)

            func finish() {
                do {
                    try decryptor.finalize()
                    try fileHandle?.close()
                    let sha256 = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                    guard let metadata, let destination else {
                        sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"missing_encrypted_metadata"}"#.data(using: .utf8)!)
                        return
                    }
                    guard received == metadata.bytes else {
                        try? FileManager.default.removeItem(at: destination)
                        sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"plaintext_length_mismatch"}"#.data(using: .utf8)!)
                        return
                    }
                    guard sha256.caseInsensitiveCompare(metadata.sha256) == .orderedSame else {
                        try? FileManager.default.removeItem(at: destination)
                        sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"checksum_mismatch"}"#.data(using: .utf8)!)
                        return
                    }
                    print("Received file: \(destination.path) (\(received) bytes, sha256: \(sha256))")

                    if let completedRequest {
                        transferReceived(completedRequest, destination)
                    }

                    let json = #"{"status":"received","fileName":"\#(destination.lastPathComponent)","bytes":\#(received),"sha256":"\#(sha256)"}"#
                    sendJSON(connection: connection, status: "200 OK", body: Data(json.utf8))
                } catch {
                    sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"close_failed"}"#.data(using: .utf8)!)
                }
            }

            func discard() {
                try? fileHandle?.close()
                if let destination { try? FileManager.default.removeItem(at: destination) }
            }

            func decline() {
                discard()
                sendJSON(connection: connection, status: "403 Forbidden", body: #"{"error":"transfer_declined"}"#.data(using: .utf8)!)
            }

            /// Opens the destination and writes what arrived while the user was deciding.
            func accept(_ pending: (request: IncomingTransferRequest, metadata: IncomingFileMetadata)) throws {
                let fileURL = uniqueDestination(in: directory, fileName: pending.metadata.fileName)
                FileManager.default.createFile(atPath: fileURL.path, contents: nil)
                fileHandle = try FileHandle(forWritingTo: fileURL)
                destination = fileURL
                metadata = pending.metadata
                completedRequest = pending.request
                awaiting = nil
                for data in heldPlaintext { try consumePlaintext(data) }
                heldPlaintext = []
                transferProgress(Int64(received), Int64(pending.metadata.bytes))
            }

            /// Asks once, then resumes reading on approval. Main queue only.
            func askForDecision(_ pending: (request: IncomingTransferRequest, metadata: IncomingFileMetadata)) {
                guard !askedForDecision else { return }
                askedForDecision = true
                var answered = false
                DispatchQueue.main.asyncAfter(deadline: .now() + transferApprovalTimeout) {
                    guard !answered else { return }
                    answered = true
                    transferWithdrawn(pending.request.id)
                    decline()
                }
                transferDecision(pending.request) { approved in
                    DispatchQueue.main.async {
                        guard !answered else { return }
                        answered = true
                        guard approved else { return decline() }
                        do {
                            try accept(pending)
                            receiveMore()
                        } catch {
                            discard()
                            sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"write_failed"}"#.data(using: .utf8)!)
                        }
                    }
                }
            }

            func continueReading() {
                if let pending = awaiting {
                    askForDecision(pending)
                } else {
                    receiveMore()
                }
            }

            func receiveMore() {
                if receivedEncrypted >= contentLength {
                    finish()
                    return
                }

                connection.receive(minimumIncompleteLength: 1, maximumLength: 1024 * 1024) { data, _, isComplete, error in
                    if error != nil {
                        try? fileHandle?.close()
                        if let destination { try? FileManager.default.removeItem(at: destination) }
                        sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"receive_failed"}"#.data(using: .utf8)!)
                        return
                    }

                    do {
                        if let data {
                            let remaining = contentLength - receivedEncrypted
                            try consumeEncrypted(Data(data.prefix(remaining)))
                        }
                        // The sender closed before sending everything; asking again would spin.
                        if isComplete && receivedEncrypted < contentLength {
                            discard()
                            sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"truncated_upload"}"#.data(using: .utf8)!)
                            return
                        }
                        continueReading()
                    } catch {
                        try? fileHandle?.close()
                        if let destination { try? FileManager.default.removeItem(at: destination) }
                        if case UploadError.transferDeclined = error {
                            sendJSON(connection: connection, status: "403 Forbidden", body: #"{"error":"transfer_declined"}"#.data(using: .utf8)!)
                            return
                        }
                        sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"write_failed"}"#.data(using: .utf8)!)
                    }
                }
            }

            continueReading()
        } catch {
            if case UploadError.transferDeclined = error {
                sendJSON(connection: connection, status: "403 Forbidden", body: #"{"error":"transfer_declined"}"#.data(using: .utf8)!)
                return
            }
            let escaped = error.localizedDescription.replacingOccurrences(of: "\"", with: "\\\"")
            sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"save_failed","message":"\#(escaped)"}"#.data(using: .utf8)!)
        }
    }

    private static func sendJSON(connection: NWConnection, status: String, body responseBody: Data) {
        let header = [
            "HTTP/1.1 \(status)",
            "Content-Type: application/json",
            "Content-Length: \(responseBody.count)",
            "Connection: close",
            "",
            ""
        ].joined(separator: "\r\n")

        var response = Data(header.utf8)
        response.append(responseBody)

        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    /// The remote IP address, used to limit what one LAN host can hold open.
    static func sourceHost(of connection: NWConnection) -> String {
        if case let .hostPort(host, _) = connection.endpoint {
            return "\(host)"
        }
        return "\(connection.endpoint)"
    }

    private static func sanitizeFileName(_ fileName: String) -> String {
        fileName
            .components(separatedBy: CharacterSet(charactersIn: "/\\:"))
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func uniqueDestination(in directory: URL, fileName: String) -> URL {
        let baseURL = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: baseURL.path) else {
            return baseURL
        }

        let ext = baseURL.pathExtension
        let stem = baseURL.deletingPathExtension().lastPathComponent

        for index in 1...999 {
            let candidateName = ext.isEmpty ? "\(stem)-\(index)" : "\(stem)-\(index).\(ext)"
            let candidate = directory.appendingPathComponent(candidateName)
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }

        return directory.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
    }
}

private struct HTTPRequestHead {
    let startLine: String
    let headers: [String: String]
    let initialBody: Data

    var contentLength: Int? {
        headers["content-length"].flatMap(Int.init)
    }

    init(headerData: Data.SubSequence, initialBody: Data) {
        let headerText = String(decoding: headerData, as: UTF8.self)
        let lines = headerText.components(separatedBy: "\r\n")
        self.startLine = lines.first ?? ""
        // Keep the first value of a repeated header. uniqueKeysWithValues would trap, letting
        // any device on the network crash the app with one request.
        self.headers = Dictionary(
            lines.dropFirst().compactMap { line in
                let parts = line.split(separator: ":", maxSplits: 1)
                guard parts.count == 2 else { return nil }
                return (
                    parts[0].lowercased(),
                    parts[1].trimmingCharacters(in: .whitespaces)
                )
            },
            uniquingKeysWith: { first, _ in first }
        )
        self.initialBody = initialBody
    }
}

private struct HTTPRequest {
    let startLine: String
    let headers: [String: String]
    let body: Data
}

private struct HTTPResponse {
    let status: String
    let body: Data
}

private struct IncomingFileMetadata: Codable {
    let transferId: UUID
    let fileName: String
    let bytes: Int
    let sha256: String
}

private struct PairRequestIdentity: Codable {
    let deviceId: UUID
    var deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let publicKey: String?
}

private struct PairConfirmBody: Codable {
    let deviceId: UUID
    let nonce: String
}

private struct PairFinalizeBody: Codable {
    let deviceId: UUID
}

private struct PendingPairing {
    let peer: PairRequestIdentity
    /// IP address the request came from.
    let source: String
    let peerKey: Data
    let macKey: Data
    let sharedSecret: String
    let nonce: Data
    var peerNonce: Data?
    var decision: Bool?
    var waiters: [(Bool) -> Void] = []
    var expiresAt = Date().addingTimeInterval(ControlServer.pairingLifetime)

    init(peer: PairRequestIdentity, source: String, peerKey: Data, macKey: Data, sharedSecret: String, nonce: Data) {
        self.peer = peer
        self.source = source
        self.peerKey = peerKey
        self.macKey = macKey
        self.sharedSecret = sharedSecret
        self.nonce = nonce
    }
}

/// In-flight pairings keyed by the phone's device ID. Only touched on the main queue.
private final class PendingPairings {
    private var entries: [UUID: PendingPairing] = [:]

    /// Starts a pairing, replacing any not-yet-confirmed one from the same address, so one
    /// LAN host holds at most one unconfirmed entry. Returns false when `limit` entries are
    /// already pending, so pairing spam can't grow memory without bound.
    func begin(_ pairing: PendingPairing, limit: Int) -> Bool {
        prune()
        entries = entries.filter { $0.value.source != pairing.source || $0.value.peerNonce != nil }
        guard entries[pairing.peer.deviceId] != nil || entries.count < limit else { return false }
        entries[pairing.peer.deviceId] = pairing
        return true
    }

    var count: Int {
        prune()
        return entries.count
    }

    /// Records the phone's nonce. Only allowed once, so a MITM can't retry nonces.
    func confirm(deviceId: UUID, peerNonce: Data) -> PendingPairing? {
        prune()
        guard var entry = entries[deviceId], entry.peerNonce == nil else { return nil }
        entry.peerNonce = peerNonce
        entries[deviceId] = entry
        return entry
    }

    func decide(deviceId: UUID, approved: Bool) {
        guard var entry = entries[deviceId] else { return }
        entry.decision = approved
        let waiters = entry.waiters
        entry.waiters = []
        entries[deviceId] = entry
        waiters.forEach { $0(approved) }
        if !waiters.isEmpty { entries[deviceId] = nil }
    }

    /// Returns false if there is no confirmed pairing to wait on.
    func awaitDecision(deviceId: UUID, completion: @escaping (Bool) -> Void) -> Bool {
        prune()
        guard var entry = entries[deviceId], entry.peerNonce != nil else { return false }
        if let decision = entry.decision {
            entries[deviceId] = nil
            completion(decision)
        } else {
            entry.waiters.append(completion)
            entries[deviceId] = entry
        }
        return true
    }

    private func prune() {
        let now = Date()
        entries = entries.filter { $0.value.expiresAt > now || $0.value.decision == nil && !$0.value.waiters.isEmpty }
    }
}

private final class ReplayCache {
    private var entries: [String: Date] = [:]
    private let lock = NSLock()

    func reserve(_ key: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let now = Date()
        entries = entries.filter { $0.value > now }
        guard entries[key] == nil else { return false }
        entries[key] = now.addingTimeInterval(10 * 60)
        return true
    }
}

private enum UploadError: Error {
    case invalidMetadata
    case outputNotReady
    case transferDeclined
}

public struct IncomingPairRequest {
    /// Identifies this request in the UI and in notifications.
    public let id: UUID
    public let deviceName: String
    public let deviceId: UUID
    public let platform: String
    /// Six-digit pairing code; show it with `PairingCode.display(_:)`.
    public let code: String
    /// A device with this ID is already paired; approving replaces its key.
    public let replacesExisting: Bool
    /// After this the pairing is declined automatically.
    public let expiresAt: Date
}

public struct IncomingTransferRequest {
    /// The sender's transfer ID.
    public let id: UUID
    public let senderName: String
    public let senderId: UUID
    public let fileName: String
    public let bytes: Int
}
