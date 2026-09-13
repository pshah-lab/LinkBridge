import Foundation
import Network
import CryptoKit

final class ControlServer {
    let identity: DeviceIdentity
    let port: UInt16

    private let listener: NWListener
    private let trustedPeerStore = TrustedPeerStore()
    private let pairApproval: (IncomingPairRequest) -> Bool
    private let transferApproval: (IncomingTransferRequest) -> Bool
    private static let replayCache = ReplayCache()

    init(
        identity: DeviceIdentity,
        preferredPort: UInt16 = 49152,
        pairApproval: @escaping (IncomingPairRequest) -> Bool = { _ in true },
        transferApproval: @escaping (IncomingTransferRequest) -> Bool = { _ in true }
    ) {
        self.identity = identity
        self.port = preferredPort
        self.pairApproval = pairApproval
        self.transferApproval = transferApproval

        do {
            self.listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: preferredPort)!)
        } catch {
            fatalError("Could not start control server on port \(preferredPort): \(error)")
        }
    }

    func start() {
        listener.newConnectionHandler = { [identity, trustedPeerStore, pairApproval, transferApproval] connection in
            connection.start(queue: .main)
            Self.handle(connection: connection, identity: identity, trustedPeerStore: trustedPeerStore, pairApproval: pairApproval, transferApproval: transferApproval)
        }

        listener.stateUpdateHandler = { state in
            print("Control server: \(state)")
        }

        listener.start(queue: .main)
    }

    private static func handle(
        connection: NWConnection,
        identity: DeviceIdentity,
        trustedPeerStore: TrustedPeerStore,
        pairApproval: @escaping (IncomingPairRequest) -> Bool,
        transferApproval: @escaping (IncomingTransferRequest) -> Bool
    ) {
        receiveRequestHead(connection: connection) { head in
            guard let head else {
                connection.cancel()
                return
            }

            if head.startLine.hasPrefix("POST /transfer/upload") {
                handleUpload(connection: connection, head: head, transferApproval: transferApproval)
                return
            }

            receiveSmallBodyIfNeeded(connection: connection, head: head) { request in
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
                        protocolVersion: identity.protocolVersion,
                        features: identity.features,
                        publicKey: identity.publicKey
                    )
                    responseBody = (try? JSONEncoder().encode(publicIdentity)) ?? Data()
                    status = "200 OK"
                } else if request.startLine.hasPrefix("POST /pair/request") {
                    let pairResponse = handlePairRequest(body: request.body, trustedPeerStore: trustedPeerStore, pairApproval: pairApproval)
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
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, _, error in
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

                receiveMore()
            }
        }

        receiveMore()
    }

    private static func handlePairRequest(
        body: Data,
        trustedPeerStore: TrustedPeerStore,
        pairApproval: @escaping (IncomingPairRequest) -> Bool
    ) -> HTTPResponse {
        guard !body.isEmpty else {
            return HTTPResponse(status: "400 Bad Request", body: #"{"error":"missing_body"}"#.data(using: .utf8)!)
        }

        do {
            let peerIdentity = try JSONDecoder().decode(PairRequestIdentity.self, from: body)
            let request = IncomingPairRequest(
                deviceName: peerIdentity.deviceName,
                deviceId: peerIdentity.deviceId,
                platform: peerIdentity.platform
            )

            guard pairApproval(request) else {
                return HTTPResponse(status: "403 Forbidden", body: #"{"error":"pair_declined"}"#.data(using: .utf8)!)
            }

            let macIdentity = IdentityStore().loadOrCreate()
            guard let peerPublicKey = peerIdentity.publicKey,
                  let sharedSecret = try? PairingKeyStore().deriveSecret(peerPublicKeyBase64: peerPublicKey) else {
                return HTTPResponse(status: "400 Bad Request", body: #"{"error":"missing_public_key"}"#.data(using: .utf8)!)
            }

            trustedPeerStore.save(
                TrustedPeer(
                    deviceId: peerIdentity.deviceId,
                    deviceName: peerIdentity.deviceName,
                    platform: peerIdentity.platform,
                    protocolVersion: peerIdentity.protocolVersion,
                    features: peerIdentity.features,
                    pairedAt: Date(),
                    authToken: sharedSecret
                )
            )

            let json = #"{"status":"paired","publicKey":"\#(macIdentity.publicKey)"}"#
            return HTTPResponse(status: "200 OK", body: Data(json.utf8))
        } catch {
            let escaped = error.localizedDescription.replacingOccurrences(of: "\"", with: "\\\"")
            return HTTPResponse(status: "400 Bad Request", body: #"{"error":"invalid_pair_request","message":"\#(escaped)"}"#.data(using: .utf8)!)
        }
    }

    private static func handleUpload(
        connection: NWConnection,
        head: HTTPRequestHead,
        transferApproval: @escaping (IncomingTransferRequest) -> Bool
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
            var destination: URL?
            var fileHandle: FileHandle?

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
                    if metadata == nil {
                        let parsed = try JSONDecoder().decode(IncomingFileMetadata.self, from: plaintext)
                        guard parsed.transferId == transferUUID else { throw UploadError.invalidMetadata }
                        let fileName = sanitizeFileName(parsed.fileName)
                        guard !fileName.isEmpty else { throw UploadError.invalidMetadata }
                        let request = IncomingTransferRequest(
                            senderName: peer.deviceName,
                            senderId: senderUUID,
                            fileName: fileName,
                            bytes: parsed.bytes
                        )
                        guard transferApproval(request) else { throw UploadError.transferDeclined }

                        let fileURL = uniqueDestination(in: directory, fileName: fileName)
                        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
                        fileHandle = try FileHandle(forWritingTo: fileURL)
                        destination = fileURL
                        metadata = IncomingFileMetadata(transferId: parsed.transferId, fileName: fileName, bytes: parsed.bytes, sha256: parsed.sha256)
                        return
                    }

                    try consumePlaintext(plaintext)
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

                    let json = #"{"status":"received","fileName":"\#(destination.lastPathComponent)","bytes":\#(received),"sha256":"\#(sha256)"}"#
                    sendJSON(connection: connection, status: "200 OK", body: Data(json.utf8))
                } catch {
                    sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"close_failed"}"#.data(using: .utf8)!)
                }
            }

            func receiveMore() {
                if receivedEncrypted >= contentLength {
                    finish()
                    return
                }

                connection.receive(minimumIncompleteLength: 1, maximumLength: 1024 * 1024) { data, _, _, error in
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
                        receiveMore()
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

            receiveMore()
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
        self.headers = Dictionary(
            uniqueKeysWithValues: lines.dropFirst().compactMap { line in
                let parts = line.split(separator: ":", maxSplits: 1)
                guard parts.count == 2 else { return nil }
                return (
                    parts[0].lowercased(),
                    parts[1].trimmingCharacters(in: .whitespaces)
                )
            }
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
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let publicKey: String?
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

struct IncomingPairRequest {
    let deviceName: String
    let deviceId: UUID
    let platform: String
}

struct IncomingTransferRequest {
    let senderName: String
    let senderId: UUID
    let fileName: String
    let bytes: Int
}
