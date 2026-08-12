import Foundation
import Network
import CryptoKit

final class ControlServer {
    let identity: DeviceIdentity
    let port: UInt16

    private let listener: NWListener
    private let trustedPeerStore = TrustedPeerStore()

    init(identity: DeviceIdentity, preferredPort: UInt16 = 49152) {
        self.identity = identity
        self.port = preferredPort

        do {
            self.listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: preferredPort)!)
        } catch {
            fatalError("Could not start control server on port \(preferredPort): \(error)")
        }
    }

    func start() {
        listener.newConnectionHandler = { [identity, trustedPeerStore] connection in
            connection.start(queue: .main)
            Self.handle(connection: connection, identity: identity, trustedPeerStore: trustedPeerStore)
        }

        listener.stateUpdateHandler = { state in
            print("Control server: \(state)")
        }

        listener.start(queue: .main)
    }

    private static func handle(connection: NWConnection, identity: DeviceIdentity, trustedPeerStore: TrustedPeerStore) {
        receiveRequestHead(connection: connection) { head in
            guard let head else {
                connection.cancel()
                return
            }

            if head.startLine.hasPrefix("POST /transfer/upload") {
                handleUpload(connection: connection, head: head)
                return
            }

            receiveSmallBodyIfNeeded(connection: connection, head: head) { request in
                let responseBody: Data
                let status: String

                if request.startLine.hasPrefix("GET /health") {
                    responseBody = #"{"status":"ok"}"#.data(using: .utf8)!
                    status = "200 OK"
                } else if request.startLine.hasPrefix("GET /device") {
                    responseBody = (try? JSONEncoder().encode(identity)) ?? Data()
                    status = "200 OK"
                } else if request.startLine.hasPrefix("POST /pair/request") {
                    responseBody = handlePairRequest(body: request.body, trustedPeerStore: trustedPeerStore)
                    status = "200 OK"
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

    private static func handlePairRequest(body: Data, trustedPeerStore: TrustedPeerStore) -> Data {
        guard !body.isEmpty else {
            return #"{"error":"missing_body"}"#.data(using: .utf8)!
        }

        do {
            let peerIdentity = try JSONDecoder().decode(DeviceIdentity.self, from: body)
            trustedPeerStore.save(
                TrustedPeer(
                    deviceId: peerIdentity.deviceId,
                    deviceName: peerIdentity.deviceName,
                    platform: peerIdentity.platform,
                    protocolVersion: peerIdentity.protocolVersion,
                    features: peerIdentity.features,
                    pairedAt: Date()
                )
            )

            return #"{"status":"paired"}"#.data(using: .utf8)!
        } catch {
            let escaped = error.localizedDescription.replacingOccurrences(of: "\"", with: "\\\"")
            return #"{"error":"invalid_pair_request","message":"\#(escaped)"}"#.data(using: .utf8)!
        }
    }

    private static func handleUpload(connection: NWConnection, head: HTTPRequestHead) {
        guard let rawFileName = head.headers["x-file-name"], !rawFileName.isEmpty else {
            sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"missing_file_name"}"#.data(using: .utf8)!)
            return
        }

        let fileName = sanitizeFileName(rawFileName.removingPercentEncoding ?? rawFileName)
        guard !fileName.isEmpty else {
            sendJSON(connection: connection, status: "400 Bad Request", body: #"{"error":"invalid_file_name"}"#.data(using: .utf8)!)
            return
        }

        guard let contentLength = head.contentLength, contentLength >= 0 else {
            sendJSON(connection: connection, status: "411 Length Required", body: #"{"error":"missing_content_length"}"#.data(using: .utf8)!)
            return
        }

        do {
            let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            let directory = downloads.appendingPathComponent("LinkBridge", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

            let destination = uniqueDestination(in: directory, fileName: fileName)
            FileManager.default.createFile(atPath: destination.path, contents: nil)
            let fileHandle = try FileHandle(forWritingTo: destination)
            var hasher = SHA256()
            var received = 0

            func consume(_ data: Data) throws {
                guard !data.isEmpty else { return }
                try fileHandle.write(contentsOf: data)
                hasher.update(data: data)
                received += data.count
            }

            try consume(head.initialBody.prefix(contentLength))

            func finish() {
                do {
                    try fileHandle.close()
                    let sha256 = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                    print("Received file: \(destination.path) (\(received) bytes, sha256: \(sha256))")

                    let json = #"{"status":"received","fileName":"\#(destination.lastPathComponent)","bytes":\#(received),"sha256":"\#(sha256)"}"#
                    sendJSON(connection: connection, status: "200 OK", body: Data(json.utf8))
                } catch {
                    sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"close_failed"}"#.data(using: .utf8)!)
                }
            }

            func receiveMore() {
                if received >= contentLength {
                    finish()
                    return
                }

                connection.receive(minimumIncompleteLength: 1, maximumLength: 1024 * 1024) { data, _, _, error in
                    if error != nil {
                        try? fileHandle.close()
                        try? FileManager.default.removeItem(at: destination)
                        sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"receive_failed"}"#.data(using: .utf8)!)
                        return
                    }

                    do {
                        if let data {
                            let remaining = contentLength - received
                            try consume(data.prefix(remaining))
                        }
                        receiveMore()
                    } catch {
                        try? fileHandle.close()
                        try? FileManager.default.removeItem(at: destination)
                        sendJSON(connection: connection, status: "500 Internal Server Error", body: #"{"error":"write_failed"}"#.data(using: .utf8)!)
                    }
                }
            }

            receiveMore()
        } catch {
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
