import CryptoKit
import Foundation
import Network

public enum FileSender {
    /// Sends to `host:tlsPort` (`endpoint` as in `ResolvedPeer.secureEndpoint`).
    public static func send(
        filePath: String,
        endpoint: String,
        secret: String,
        onProgress: @escaping (Int64, Int64) -> Void = { _, _ in }
    ) throws {
        let parts = endpoint.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let port = UInt16(parts[1]), let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw SenderError.invalidEndpoint
        }
        try send(filePath: filePath, host: NWEndpoint.Host(parts[0]), port: nwPort, interface: nil, secret: secret, onProgress: onProgress)
    }

    /// Sends to a discovered peer. A peer browsed over Bonjour is resolved first, so the file goes
    /// over whichever link reached it (Wi-Fi network or peer-to-peer); others use `host` directly.
    public static func send(
        filePath: String,
        peer: ResolvedPeer,
        secret: String,
        onProgress: @escaping (Int64, Int64) -> Void = { _, _ in }
    ) throws {
        guard let tlsPort = peer.tlsPort, let nwPort = NWEndpoint.Port(rawValue: tlsPort) else {
            throw SenderError.peerNeedsUpdate
        }
        if let service = peer.service {
            let route = try DirectRoute.resolve(service)
            try send(filePath: filePath, host: route.host, port: nwPort, interface: route.interface, secret: secret, onProgress: onProgress)
        } else {
            try send(filePath: filePath, host: NWEndpoint.Host(peer.host), port: nwPort, interface: nil, secret: secret, onProgress: onProgress)
        }
    }

    private static func send(
        filePath: String,
        host: NWEndpoint.Host,
        port: NWEndpoint.Port,
        interface: NWInterface?,
        secret: String,
        onProgress: @escaping (Int64, Int64) -> Void
    ) throws {
        let fileURL = URL(fileURLWithPath: filePath)
        let fileName = fileURL.lastPathComponent
        let fileSize = try fileSize(at: fileURL)
        let sha256 = try sha256Hex(of: fileURL)
        let identity = IdentityStore().loadOrCreate()
        let transferUUID = UUID()
        let transferId = transferUUID.uuidString
        let metadata = try JSONSerialization.data(withJSONObject: [
            "transferId": transferId,
            "fileName": fileName,
            "bytes": fileSize,
            "sha256": sha256
        ])
        let encryptedURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("linkbridge-upload-\(UUID().uuidString).enc")
        let encryptedSize = try Security.encryptChunkedFile(
            inputURL: fileURL,
            outputURL: encryptedURL,
            secret: secret,
            metadata: metadata,
            transferId: transferUUID
        )
        defer { try? FileManager.default.removeItem(at: encryptedURL) }

        let timestamp = String(Int(Date().timeIntervalSince1970 * 1000))
        let signaturePayload = [
            "POST",
            "/transfer/upload",
            identity.deviceId.uuidString,
            timestamp,
            transferId,
            Security.encryptionScheme,
            String(encryptedSize)
        ].joined(separator: "\n")
        guard let signature = Security.uploadSignature(secret: secret, scheme: Security.encryptionScheme, message: signaturePayload) else {
            throw SenderError.signatureFailed
        }

        guard let psk = SecureTransport.psk(fromSecret: secret) else {
            throw SenderError.signatureFailed
        }

        let head = [
            "POST /transfer/upload HTTP/1.1",
            "Host: \(hostHeader(host))",
            "Content-Type: application/octet-stream",
            "Content-Length: \(encryptedSize)",
            "X-LinkBridge-Device-Id: \(identity.deviceId.uuidString)",
            "X-LinkBridge-Timestamp: \(timestamp)",
            "X-LinkBridge-Transfer-Id: \(transferId)",
            "X-LinkBridge-Encryption: \(Security.encryptionScheme)",
            "X-LinkBridge-Signature: \(signature)",
            "Connection: close",
            "",
            ""
        ].joined(separator: "\r\n")

        let parameters = SecureTransport.clientParameters(
            identity: SecureTransport.identity(for: identity.deviceId.uuidString),
            psk: psk
        )
        parameters.requiredInterface = interface
        let connection = NWConnection(host: host, port: port, using: parameters)
        let queue = DispatchQueue(label: "linkbridge.filesender")
        defer { connection.cancel() }
        try waitUntilReady(connection, queue: queue)
        try sendSync(connection, Data(head.utf8))

        let input = try FileHandle(forReadingFrom: encryptedURL)
        defer { try? input.close() }
        while true {
            let chunk = try input.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            try sendSync(connection, chunk)
            onProgress(Int64(input.offsetInFile), Int64(encryptedSize))
        }

        let (status, responseData) = try receiveResponse(connection)
        guard (200...299).contains(status) else {
            if status == 426 { throw SenderError.peerNeedsUpdate }
            throw SenderError.httpStatus(status, responseData)
        }
        let responseText = String(decoding: responseData, as: UTF8.self)
        if !responseText.contains(sha256) {
            throw SenderError.checksumMissing
        }

        print("Sent \(fileName) to \(host):\(port) over TLS\(interface.map { " via \($0.name)" } ?? "")")
        print("Bytes: \(fileSize)")
        print("SHA-256: \(sha256)")
    }

    private static let timeout: DispatchTimeInterval = .seconds(15)

    /// `Host:` header value: the address without its interface zone (`%en0`), bracketed if IPv6.
    static func hostHeader(_ host: NWEndpoint.Host) -> String {
        let text = "\(host)"
        let bare = text.split(separator: "%", maxSplits: 1).first.map(String.init) ?? text
        if case .ipv6 = host { return "[\(bare)]" }
        return bare
    }

    static func waitUntilReady(_ connection: NWConnection, queue: DispatchQueue) throws {
        let ready = DispatchSemaphore(value: 0)
        var failure: Error?
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case let .failed(error), let .waiting(error):
                // .waiting covers TLS rejections (wrong key: bad MAC) as well as refused connections.
                failure = error
                ready.signal()
            default:
                break
            }
        }
        connection.start(queue: queue)
        guard ready.wait(timeout: .now() + timeout) == .success else { throw SenderError.timedOut }
        connection.stateUpdateHandler = nil
        if let failure { throw SenderError.connectionFailed(failure) }
    }

    static func sendSync(_ connection: NWConnection, _ data: Data) throws {
        let done = DispatchSemaphore(value: 0)
        var failure: Error?
        connection.send(content: data, completion: .contentProcessed { error in
            failure = error
            done.signal()
        })
        // Generous: the receiver may be waiting for the user to accept the file.
        guard done.wait(timeout: .now() + .seconds(120)) == .success else { throw SenderError.timedOut }
        if let failure { throw SenderError.connectionFailed(failure) }
    }

    /// Reads a `Connection: close` HTTP response; returns the status code and body.
    static func receiveResponse(_ connection: NWConnection, timeout: TimeInterval = 120) throws -> (Int, Data) {
        var buffer = Data()
        while true {
            let done = DispatchSemaphore(value: 0)
            var chunk: Data?
            var finished = false
            var failure: Error?
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                chunk = data
                finished = isComplete
                failure = error
                done.signal()
            }
            guard done.wait(timeout: .now() + timeout) == .success else { throw SenderError.timedOut }
            if let chunk { buffer.append(chunk) }
            if finished || failure != nil || buffer.count > 1024 * 1024 { break }
        }
        guard let separator = buffer.range(of: Data("\r\n\r\n".utf8)) else { throw SenderError.badResponse }
        let statusLine = String(decoding: buffer[..<separator.lowerBound], as: UTF8.self).components(separatedBy: "\r\n").first ?? ""
        let fields = statusLine.split(separator: " ")
        guard fields.count >= 2, let status = Int(fields[1]) else { throw SenderError.badResponse }
        return (status, Data(buffer[separator.upperBound...]))
    }

    private static func fileSize(at fileURL: URL) throws -> UInt64 {
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        return attributes[.size] as? UInt64 ?? UInt64(attributes[.size] as? Int ?? 0)
    }

    private static func sha256Hex(of fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }

        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

private enum SenderError: Error, CustomStringConvertible {
    case invalidEndpoint
    case httpStatus(Int, Data)
    case checksumMissing
    case signatureFailed
    case timedOut
    case connectionFailed(Error)
    case badResponse
    case peerNeedsUpdate

    var description: String {
        switch self {
        case .invalidEndpoint:
            return "Endpoint must look like 192.168.1.10:49153"
        case let .httpStatus(status, body):
            return "Upload failed with HTTP \(status): \(String(decoding: body, as: UTF8.self))"
        case .checksumMissing:
            return "Upload response did not include the expected checksum"
        case .signatureFailed:
            return "Could not sign upload request"
        case .timedOut:
            return "The phone did not respond. Check it is on the same Wi-Fi with LinkBridge open."
        case let .connectionFailed(error):
            return "Secure connection failed (\(error)). If this keeps happening, pair the phone again."
        case .badResponse:
            return "The phone sent an unreadable response"
        case .peerNeedsUpdate:
            return "Update LinkBridge on the phone: it needs the secure transfer version."
        }
    }
}
