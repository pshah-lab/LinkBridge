import Foundation
import Network

/// What a device says about itself while discovering peers. Field names equal the Bonjour TXT
/// keys, so the same JSON works over the offline hello and inside the QR payload. The TXT record
/// has no `port` key because Bonjour carries it; here `port` is the plain listener port.
public struct DiscoveryRecord: Codable, Equatable {
    public var rid: String
    public var tags: String
    public var deviceName: String
    public var platform: String
    public var protocolVersion: Int
    public var port: UInt16
    public var tlsPort: UInt16?
    public var features: String

    public init(rid: String, tags: String, deviceName: String, platform: String, protocolVersion: Int,
                port: UInt16, tlsPort: UInt16?, features: String) {
        self.rid = rid
        self.tags = tags
        self.deviceName = deviceName
        self.platform = platform
        self.protocolVersion = protocolVersion
        self.port = port
        self.tlsPort = tlsPort
        self.features = features
    }

    enum CodingKeys: String, CodingKey {
        case rid, tags, deviceName, platform
        case protocolVersion = "protocol"
        case port, tlsPort, features
    }

    /// The Bonjour TXT form. `port` is not included: Bonjour carries it.
    var txtRecord: NWTXTRecord {
        var txt = NWTXTRecord()
        txt["rid"] = rid
        txt["tags"] = tags
        txt["deviceName"] = deviceName
        txt["platform"] = platform
        txt["protocol"] = String(protocolVersion)
        if let tlsPort { txt["tlsPort"] = String(tlsPort) }
        txt["features"] = features
        return txt
    }

    /// Number of comma-separated tags (an empty string is zero).
    var tagCount: Int {
        tags.isEmpty ? 0 : tags.split(separator: ",", omittingEmptySubsequences: false).count
    }
}

/// The text an Android hotspot shows as a QR code: how to join its Wi-Fi, where its hello
/// endpoint is, and who it is.
///
/// Format: `linkbridge-offline:1:` + base64url (no padding) of the UTF-8 JSON
/// `{"ssid","pass","addr","hello","record":{DiscoveryRecord}}`. Shared vectors:
/// `docs/test-vectors/offline-link.json`.
public struct OfflineLinkPayload: Equatable {
    public var ssid: String
    public var passphrase: String
    public var address: IPv4Address
    public var helloPort: UInt16
    public var record: DiscoveryRecord

    public init(ssid: String, passphrase: String, address: IPv4Address, helloPort: UInt16, record: DiscoveryRecord) {
        self.ssid = ssid
        self.passphrase = passphrase
        self.address = address
        self.helloPort = helloPort
        self.record = record
    }

    static let prefix = "linkbridge-offline:1:"
    static let maxTextLength = 1024
    static let maxTags = 14

    private struct Wire: Codable {
        var ssid: String
        var pass: String
        var addr: String
        var hello: UInt16
        var record: DiscoveryRecord
    }

    private static let topKeys: Set<String> = ["ssid", "pass", "addr", "hello", "record"]
    private static let recordKeys: Set<String> = ["rid", "tags", "deviceName", "platform", "protocol", "port", "tlsPort", "features"]

    /// Returns nil for anything that is not a well-formed, in-limits payload.
    public static func parse(_ text: String) -> OfflineLinkPayload? {
        guard text.utf8.count <= maxTextLength, text.hasPrefix(prefix),
              let json = decodeBase64URL(String(text.dropFirst(prefix.count))),
              let object = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              Set(object.keys) == topKeys,
              let recordObject = object["record"] as? [String: Any],
              recordObject.keys.allSatisfy(recordKeys.contains),
              let wire = try? JSONDecoder().decode(Wire.self, from: json),
              let address = parseIPv4(wire.addr), isPrivate(address),
              (1...32).contains(wire.ssid.utf8.count),
              (8...63).contains(wire.pass.unicodeScalars.count),
              wire.hello != 0, wire.record.port != 0, wire.record.tlsPort != 0,
              wire.record.tagCount <= maxTags
        else { return nil }
        return OfflineLinkPayload(ssid: wire.ssid, passphrase: wire.pass, address: address,
                                  helloPort: wire.hello, record: wire.record)
    }

    public func encoded() -> String {
        let wire = Wire(ssid: ssid, pass: passphrase, addr: Self.format(address), hello: helloPort, record: record)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let json = (try? encoder.encode(wire)) ?? Data()
        return Self.prefix + Self.encodeBase64URL(json)
    }

    // MARK: Helpers

    static func format(_ address: IPv4Address) -> String {
        address.rawValue.map { String($0) }.joined(separator: ".")
    }

    /// Strict dotted quad: four decimal parts, no leading zeros, nothing else.
    static func parseIPv4(_ text: String) -> IPv4Address? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var bytes: [UInt8] = []
        for part in parts {
            guard (1...3).contains(part.count), part.allSatisfy({ $0 >= "0" && $0 <= "9" }),
                  part == "0" || part.first != "0", let value = UInt8(part) else { return nil }
            bytes.append(value)
        }
        return IPv4Address(Data(bytes))
    }

    /// RFC 1918 only: 10/8, 172.16/12, 192.168/16.
    static func isPrivate(_ address: IPv4Address) -> Bool {
        let b = [UInt8](address.rawValue)
        guard b.count == 4 else { return false }
        return b[0] == 10 || (b[0] == 172 && (16...31).contains(b[1])) || (b[0] == 192 && b[1] == 168)
    }

    static func encodeBase64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Rejects padding, the standard alphabet and any other stray character.
    static func decodeBase64URL(_ text: String) -> Data? {
        guard !text.isEmpty, text.utf8.count % 4 != 1,
              text.utf8.allSatisfy({ ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F })
        else { return nil }
        var standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        standard += String(repeating: "=", count: (4 - standard.count % 4) % 4)
        return Data(base64Encoded: standard)
    }
}

public enum OfflineHelloError: Error, Equatable {
    case timedOut
    case connectionFailed
    /// The peer answered 429.
    case busy
    /// The peer answered 400.
    case rejected
    case unexpectedStatus(Int)
    /// Missing Content-Length, a body over the limit, or JSON that is not a DiscoveryRecord.
    case malformedResponse
    case requestTooLarge
}

/// Client for the plain-HTTP offline hello: `POST /offline/hello` with our `DiscoveryRecord`,
/// answered `200` with the peer's. Both bodies are at most `maxBody` bytes.
public enum OfflineHelloClient {
    public static let defaultPort: UInt16 = 49154
    public static let path = "/offline/hello"
    public static let maxBody = 4096
    static let maxHead = 8192

    /// Blocks the calling thread (not the main thread) for up to `timeout` seconds in total.
    public static func exchange(host: NWEndpoint.Host, port: UInt16 = defaultPort, record: DiscoveryRecord,
                                timeout: TimeInterval = 3) throws -> DiscoveryRecord {
        let body = try JSONEncoder().encode(record)
        guard body.count <= maxBody else { throw OfflineHelloError.requestTooLarge }
        guard let nwPort = NWEndpoint.Port(rawValue: port), port != 0 else { throw OfflineHelloError.connectionFailed }
        let head = [
            "POST \(path) HTTP/1.1",
            "Host: \(host):\(port)",
            "Content-Type: application/json",
            "Content-Length: \(body.count)",
            "Connection: close",
            "", "",
        ].joined(separator: "\r\n")
        var request = Data(head.utf8)
        request.append(body)

        let exchange = Exchange(connection: NWConnection(host: host, port: nwPort, using: DirectRoute.tcpParameters()), request: request)
        return try exchange.run(timeout: timeout)
    }

    /// One request/response over one connection. All mutable state lives on `queue`; `run` reads
    /// the outcome only after the semaphore, which orders it after the last write.
    private final class Exchange {
        let connection: NWConnection
        let request: Data
        let queue = DispatchQueue(label: "linkbridge.offlinehello")
        let done = DispatchSemaphore(value: 0)
        var buffer = Data()
        var outcome: Result<DiscoveryRecord, OfflineHelloError>?

        init(connection: NWConnection, request: Data) {
            self.connection = connection
            self.request = request
        }

        func run(timeout: TimeInterval) throws -> DiscoveryRecord {
            connection.stateUpdateHandler = { [self] state in
                switch state {
                case .ready:
                    connection.send(content: request, completion: .contentProcessed { [self] error in
                        if error != nil { finish(.failure(.connectionFailed)) }
                    })
                    receive()
                case .failed, .cancelled:
                    finish(.failure(.connectionFailed))
                case .waiting(.posix(.ECONNREFUSED)):
                    // Network.framework keeps retrying a refused connection; nothing is listening.
                    finish(.failure(.connectionFailed))
                default:
                    break
                }
            }
            connection.start(queue: queue)
            let timedOut = done.wait(timeout: .now() + timeout) == .timedOut
            connection.stateUpdateHandler = nil
            connection.cancel()
            if timedOut { throw OfflineHelloError.timedOut }
            return try outcome!.get()
        }

        func finish(_ result: Result<DiscoveryRecord, OfflineHelloError>) {
            guard outcome == nil else { return }
            outcome = result
            done.signal()
        }

        func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [self] data, _, isComplete, error in
                if let data { buffer.append(data) }
                switch evaluate(atEnd: isComplete || error != nil) {
                case .some(let result): finish(result)
                case .none: receive()
                }
            }
        }

        /// nil: need more bytes.
        func evaluate(atEnd: Bool) -> Result<DiscoveryRecord, OfflineHelloError>? {
            let separator = Data("\r\n\r\n".utf8)
            guard let headEnd = buffer.range(of: separator) else {
                return (atEnd || buffer.count > OfflineHelloClient.maxHead) ? .failure(.malformedResponse) : nil
            }
            let head = String(decoding: buffer[buffer.startIndex..<headEnd.lowerBound], as: UTF8.self)
            let lines = head.components(separatedBy: "\r\n")
            let statusParts = lines[0].split(separator: " ")
            guard statusParts.count >= 2, statusParts[0].hasPrefix("HTTP/1."), let status = Int(statusParts[1]) else {
                return .failure(.malformedResponse)
            }
            switch status {
            case 200: break
            case 429: return .failure(.busy)
            case 400: return .failure(.rejected)
            default: return .failure(.unexpectedStatus(status))
            }
            var length: Int?
            for line in lines.dropFirst() {
                let pair = line.split(separator: ":", maxSplits: 1)
                if pair.count == 2, pair[0].lowercased() == "content-length" {
                    guard length == nil, let value = Int(pair[1].trimmingCharacters(in: .whitespaces)), value >= 0 else {
                        return .failure(.malformedResponse)
                    }
                    length = value
                }
            }
            guard let length, length <= OfflineHelloClient.maxBody else { return .failure(.malformedResponse) }
            let bodyStart = headEnd.upperBound
            guard buffer.count - (bodyStart - buffer.startIndex) >= length else {
                return atEnd ? .failure(.malformedResponse) : nil
            }
            let body = buffer[bodyStart..<(bodyStart + length)]
            guard let record = try? JSONDecoder().decode(DiscoveryRecord.self, from: Data(body)) else {
                return .failure(.malformedResponse)
            }
            return .success(record)
        }
    }
}
