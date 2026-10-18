import CryptoKit
import Foundation
import Network
@testable import LinkBridgeCore

// Checks for LinkBridgeCore. Run with `swift run LinkBridgeCoreChecks`.
// Uses a plain executable because XCTest / Swift Testing need full Xcode.
// Move these into a test target once Xcode is installed.

// MARK: - Isolation

// The core reads and writes ~/Library/Application Support/LinkBridge and
// ~/Downloads/LinkBridge. Re-run this process with a throwaway home folder so
// the checks never touch the user's real keys, paired devices or downloads.
if ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"] == nil {
    let home = FileManager.default.temporaryDirectory.appendingPathComponent("linkbridge-checks-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: home) }
    let child = Process()
    child.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
    child.environment = ProcessInfo.processInfo.environment.merging(["CFFIXED_USER_HOME": home.path]) { $1 }
    try! child.run()
    child.waitUntilExit()
    exit(child.terminationStatus)
}

// MARK: - Tiny runner

var failures = 0
var passed = 0

func check(_ condition: @autoclosure () throws -> Bool, _ message: String, line: Int = #line) {
    do {
        if try condition() { passed += 1; return }
        print("  FAIL (line \(line)): \(message)")
    } catch {
        print("  FAIL (line \(line)): \(message) threw \(error)")
    }
    failures += 1
}

func section(_ name: String, _ body: () throws -> Void) {
    print("• \(name)")
    do { try body() } catch {
        print("  FAIL: \(name) threw \(error)")
        failures += 1
    }
}

// MARK: - Pairing code

section("Pairing code matches the independently computed vector") {
    // Same vector must be used by the Android implementation (PairingCode.kt).
    let initiatorKey = Data([4] + Array(1...64))
    let responderKey = Data([4] + Array(101...164))
    let initiatorNonce = Data(repeating: 0xAA, count: 32)
    let responderNonce = Data(repeating: 0x55, count: 32)
    let commitment = PairingCode.commitment(publicKey: responderKey, nonce: responderNonce)
    check(commitment.map { String(format: "%02x", $0) }.joined() == "d55bcd8aa0adb263e70397ba70563b77202762669bc2a9fc8f03387ed2e8c0d9", "commitment vector")
    let code = PairingCode.code(initiatorKey: initiatorKey, responderKey: responderKey, initiatorNonce: initiatorNonce, responderNonce: responderNonce)
    check(code == "894940", "code vector, got \(code)")
    check(PairingCode.display("894940") == "894 940", "display format")
    check(PairingCode.randomNonce().count == 32, "nonce length")
}

section("Public key parsing accepts X9.63 and legacy raw, rejects others") {
    let key = P256.KeyAgreement.PrivateKey().publicKey
    check((try? PairingKeyStore.parsePublicKey(key.x963Representation.base64EncodedString())) != nil, "65-byte X9.63")
    check((try? PairingKeyStore.parsePublicKey(key.rawRepresentation.base64EncodedString())) != nil, "64-byte raw")
    check((try? PairingKeyStore.parsePublicKey(Data(count: 33).base64EncodedString())) == nil, "33 bytes rejected")
    check((try? PairingKeyStore.parsePublicKey("not base64!")) == nil, "garbage rejected")
}

// MARK: - Encryption

func roundTrip(_ plaintext: Data, secret: String, metadata: Data?) throws -> [Data] {
    let dir = FileManager.default.temporaryDirectory
    let input = dir.appendingPathComponent(UUID().uuidString)
    let output = dir.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: input); try? FileManager.default.removeItem(at: output) }
    try plaintext.write(to: input)
    _ = try Security.encryptChunkedFile(inputURL: input, outputURL: output, secret: secret, metadata: metadata)
    let ciphertext = try Data(contentsOf: output)
    var records: [Data] = []
    let decryptor = try ChunkedDecryptor(secret: secret)
    // Feed in odd-sized pieces to exercise record reassembly.
    var offset = 0
    while offset < ciphertext.count {
        let end = min(offset + 70_001, ciphertext.count)
        try decryptor.consume(ciphertext.subdata(in: offset..<end)) { records.append($0) }
        offset = end
    }
    try decryptor.finalize()
    return records
}

section("Chunked AES-GCM round-trips across record boundaries") {
    let secret = Security.generateToken()
    let size = Security.chunkSize * 2 + 12_345
    let plaintext = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31) })
    let records = try roundTrip(plaintext, secret: secret, metadata: Data("{\"m\":1}".utf8))
    check(records.count == 4, "metadata + 3 data records, got \(records.count)")
    check(records.first == Data("{\"m\":1}".utf8), "metadata first")
    check(Data(records.dropFirst().joined()) == plaintext, "plaintext restored")
    check(try roundTrip(Data(), secret: secret, metadata: nil).isEmpty, "empty file")
}

section("Decryption rejects tampering, truncation and the wrong key") {
    let secret = Security.generateToken()
    let dir = FileManager.default.temporaryDirectory
    let input = dir.appendingPathComponent(UUID().uuidString)
    let output = dir.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: input); try? FileManager.default.removeItem(at: output) }
    try Data(repeating: 7, count: 5000).write(to: input)
    _ = try Security.encryptChunkedFile(inputURL: input, outputURL: output, secret: secret)
    let good = try Data(contentsOf: output)

    var flipped = good
    flipped[flipped.count - 1] ^= 0x01
    check((try? ChunkedDecryptor(secret: secret).consume(flipped) { _ in }) == nil, "bit flip rejected")

    let truncated = try ChunkedDecryptor(secret: secret)
    try truncated.consume(good.prefix(good.count - 10)) { _ in }
    check((try? truncated.finalize()) == nil, "truncation rejected")

    check((try? ChunkedDecryptor(secret: Security.generateToken()).consume(good) { _ in }) == nil, "wrong key rejected")
}

// MARK: - Presentation (PresentationChecks.swift)

section("A record shorter than its tag is refused, not a crash") {
    for length in [1, 15] {
        let decryptor = try ChunkedDecryptor(secret: Security.generateToken())
        var record = Data(repeating: 0, count: 12)
        record.append(contentsOf: [0, 0, 0, UInt8(length)])
        record.append(Data(repeating: 7, count: length))
        var threw = false
        do { try decryptor.consume(record) { _ in } } catch { threw = true }
        check(threw, "\(length)-byte record rejected")
    }
}

section("File names can't hide their extension, be hidden, or run long") {
    // Same cases as Android's FileNamesTest.
    let cases: [(String, String)] = [
        ("report\u{202E}fdp.command", "reportfdp.command"),
        ("a\u{2066}b\u{2069}c\u{200B}.txt", "abc.txt"),
        ("nul\u{0}and\ttab\n.txt", "nulandtab.txt"),
        (".hidden", "hidden"),
        ("...env", "env"),
        ("../../etc/passwd", "-..-etc-passwd"),
        ("a:b\\c", "a-b-c"),
        ("  .  ", "received-file"),
        ("\u{202E}", "received-file"),
        ("photo.jpg", "photo.jpg"),
        ("café ☕️.png", "café ☕️.png"),
    ]
    for (input, expected) in cases {
        let got = ControlServer.sanitizeFileName(input)
        check(got == expected, "\(input.debugDescription) -> \(expected.debugDescription), got \(got.debugDescription)")
    }
    let long = ControlServer.sanitizeFileName(String(repeating: "é", count: 300) + ".pdf")
    check(long.utf8.count <= 200 && long.hasSuffix(".pdf"), "long name capped at 200 bytes keeping .pdf, got \(long.utf8.count)")
}

section("Device names are one short line with no hidden characters") {
    let cases: [(String, String)] = [
        ("Pixel 8\nwants to send you \"Q3 report.pdf\" (2 MB)\n", "Pixel 8 wants to send you \"Q3 report.pdf"),
        ("Pixel\u{202E}8", "Pixel 8"),
        ("  Pixel\t\t8  ", "Pixel 8"),
        ("A\u{200B}B", "A B"),
        ("\n\n\u{202E}", DeviceNames.fallback),
        ("Priya’s Mac 🍎", "Priya’s Mac 🍎"),
    ]
    for (input, expected) in cases {
        check(DeviceNames.sanitize(input) == expected, "\(input.debugDescription) -> \(DeviceNames.sanitize(input).debugDescription)")
    }
    check(DeviceNames.sanitize(String(repeating: "x", count: 500)).count == DeviceNames.maxLength, "capped at \(DeviceNames.maxLength)")
}

// MARK: - Credential storage

final class MemorySecrets: SecretBackend {
    var items: [String: String] = [:]
    var failWrites = false
    var unavailable = false
    func lookup(account: String) -> SecretLookup {
        if unavailable { return .unavailable }
        return items[account].map(SecretLookup.found) ?? .notFound
    }
    func write(_ value: String, account: String) -> Bool {
        guard !failWrites else { return false }
        items[account] = value
        return true
    }
    func delete(account: String) { items[account] = nil }
}

section("Credentials: ad-hoc builds use the file, signed builds migrate it to the Keychain") {
    // This binary is ad-hoc signed, so it must not touch the Keychain (that prompts for a password).
    check(!CredentialStore.hasTeamIdentifier(), "checks binary has no Team ID")
    check(CredentialStore.defaultBackend is FileSecretBackend, "ad-hoc build uses the file store")

    let url = FileManager.default.temporaryDirectory.appendingPathComponent("creds-\(UUID().uuidString)/credentials.json")
    let file = FileSecretBackend(fileURL: url)
    file.write("private-key", account: "identity.pairingPrivateKey")
    file.write("peer-secret", account: "peer.ABC")
    let perms = (try? FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? Int) ?? -1
    check(perms == 0o600, "file is 0600, got \(String(perms, radix: 8))")

    let failing = MemorySecrets()
    failing.failWrites = true
    check(!CredentialStore.migrate(from: file, to: failing), "failed migration reports failure")
    check(FileManager.default.fileExists(atPath: url.path), "failed migration keeps the file")

    let keychain = MemorySecrets()
    check(CredentialStore.migrate(from: file, to: keychain), "migration succeeds")
    check(keychain.items == ["identity.pairingPrivateKey": "private-key", "peer.ABC": "peer-secret"], "every item moved")
    check(!FileManager.default.fileExists(atPath: url.path), "plaintext file deleted after migration")
    check(CredentialStore.migrate(from: file, to: keychain), "nothing to migrate is a success")
    check(CredentialStore(backend: keychain).read(account: "peer.ABC") == "peer-secret", "store reads through its backend")
}

section("Credentials: an unreadable store is never overwritten or re-keyed") {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("creds-\(UUID().uuidString)/credentials.json")
    let file = FileSecretBackend(fileURL: url)
    check(file.lookup(account: "x") == .notFound, "no file: not found")
    file.write("one", account: "peer.A")

    // A file restored with loose permissions is rewritten as 0600, never written in place.
    try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: url.path)
    file.write("two", account: "peer.B")
    let perms = (try FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? Int) ?? -1
    check(perms == 0o600, "rewritten file is 0600, got \(String(perms, radix: 8))")
    check(file.read(account: "peer.A") == "one" && file.read(account: "peer.B") == "two", "both secrets kept")
    let leftovers = try FileManager.default.contentsOfDirectory(atPath: url.deletingLastPathComponent().path)
    check(leftovers == ["credentials.json"], "no temporary files left, got \(leftovers)")

    try Data("{not json".utf8).write(to: url)
    check(file.lookup(account: "peer.A") == .unavailable, "corrupt file: unavailable, not missing")
    check(!file.write("three", account: "identity.pairingPrivateKey"), "refuses to write over a corrupt file")
    check((try? String(contentsOf: url, encoding: .utf8)) == "{not json", "corrupt file left untouched")

    let locked = MemorySecrets()
    locked.unavailable = true
    let lockedKeys = PairingKeyStore(credentials: CredentialStore(backend: locked))
    let peer = P256.KeyAgreement.PrivateKey().publicKey.x963Representation.base64EncodedString()
    check((try? lockedKeys.deriveSecret(peerPublicKeyBase64: peer)) == nil, "unreadable store: no secret derived")
    check(lockedKeys.publicKeyBase64.isEmpty, "unreadable store: no public key")
    check(locked.items.isEmpty, "unreadable store: no new key written")

    let empty = MemorySecrets()
    let keys = PairingKeyStore(credentials: CredentialStore(backend: empty))
    let first = keys.publicKeyBase64
    check(!first.isEmpty && empty.items.count == 1, "missing key: one is created")
    check(keys.publicKeyBase64 == first, "and reused afterwards")
}

runPresentationChecks()
runApprovalChecks()
runHistoryStoreChecks()

// MARK: - Live server checks (run off the main thread; the server uses the main queue)

func startServer(preferredPort: UInt16, approve: @escaping () -> Bool, lastRequest: @escaping (IncomingPairRequest) -> Void = { _ in }) -> (ControlServer, UInt16) {
    let identity = IdentityStore().loadOrCreate()
    // Port 0 for TLS too, so the checks never collide with a running LinkBridge app.
    let server = ControlServer(identity: identity, preferredPort: preferredPort, preferredSecurePort: 0, pairApproval: { request in
        lastRequest(request)
        return approve()
    })
    let ready = DispatchSemaphore(value: 0)
    var bound: UInt16?
    DispatchQueue.main.async {
        server.start { port in bound = port; ready.signal() }
    }
    ready.wait()
    return (server, bound ?? 0)
}

func post(_ port: UInt16, _ path: String, _ json: [String: Any], host: String = "127.0.0.1") -> (Int, [String: Any]) {
    var request = URLRequest(url: URL(string: "http://\(host):\(port)\(path)")!)
    request.httpMethod = "POST"
    request.httpBody = try! JSONSerialization.data(withJSONObject: json)
    request.timeoutInterval = 10
    let done = DispatchSemaphore(value: 0)
    var result: (Int, [String: Any]) = (0, [:])
    URLSession.shared.dataTask(with: request) { data, response, _ in
        let body = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
        result = ((response as? HTTPURLResponse)?.statusCode ?? 0, body ?? [:])
        done.signal()
    }.resume()
    done.wait()
    return result
}

/// Opens a TLS-PSK connection and sends GET /health. Returns the HTTP status, or nil if
/// the handshake was rejected.
func tlsHealth(port: UInt16, identity: String, psk: Data) -> Int? {
    let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!,
                                  using: SecureTransport.clientParameters(identity: identity, psk: psk))
    let done = DispatchSemaphore(value: 0)
    var status: Int?
    connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
            connection.send(content: Data("GET /health HTTP/1.1\r\nHost: x\r\n\r\n".utf8), completion: .contentProcessed { _ in })
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
                let line = String(decoding: data ?? Data(), as: UTF8.self).split(separator: " ")
                status = line.count > 1 ? Int(line[1]) : nil
                done.signal()
            }
        case .failed, .waiting:
            done.signal()
        default:
            break
        }
    }
    connection.start(queue: .global())
    _ = done.wait(timeout: .now() + 5)
    connection.cancel()
    return status
}

func rawRequest(port: UInt16, _ text: String) -> String {
    let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
    let done = DispatchSemaphore(value: 0)
    var reply = ""
    connection.start(queue: .global())
    connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in })
    connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
        reply = String(decoding: data ?? Data(), as: UTF8.self)
        done.signal()
    }
    _ = done.wait(timeout: .now() + 5)
    connection.cancel()
    return reply
}

/// Sends `text`, then half-closes the sending side. Returns the reply, or "" on timeout.
func halfClosedRequest(port: UInt16, _ text: String, timeout: TimeInterval = 3) -> String {
    let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
    let done = DispatchSemaphore(value: 0)
    var reply = ""
    connection.start(queue: .global())
    connection.send(content: Data(text.utf8), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in })
    connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
        reply = String(decoding: data ?? Data(), as: UTF8.self)
        done.signal()
    }
    _ = done.wait(timeout: .now() + timeout)
    connection.cancel()
    return reply
}

/// Opens a connection, sends `text` (if any) and waits for the server to close it.
/// Returns true if the server closed it within `timeout`, false if it was still open.
func serverCloses(port: UInt16, parameters: NWParameters = .tcp, _ text: String = "", timeout: TimeInterval = 3) -> Bool {
    let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: parameters)
    let done = DispatchSemaphore(value: 0)
    connection.stateUpdateHandler = { state in
        if case .ready = state {
            if !text.isEmpty { connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in }) }
            func read() {
                connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, isComplete, error in
                    if isComplete || error != nil { done.signal() } else if data != nil { read() }
                }
            }
            read()
        } else if case .failed = state {
            done.signal()
        }
    }
    connection.start(queue: .global())
    let closed = done.wait(timeout: .now() + timeout) == .success
    connection.cancel()
    return closed
}

/// Starts a server on the completion-based initializer. `pair` / `transfer` decide how each request is answered.
func startDecisionServer(
    pair: @escaping (IncomingPairRequest, @escaping (Bool) -> Void) -> Void,
    transfer: @escaping (IncomingTransferRequest, @escaping (Bool) -> Void) -> Void = { _, done in done(true) },
    received: @escaping (IncomingTransferRequest, URL) -> Void = { _, _ in },
    withdrawn: @escaping (UUID) -> Void = { _ in }
) -> (ControlServer, UInt16) {
    let server = ControlServer(identity: IdentityStore().loadOrCreate(), preferredPort: 0, preferredSecurePort: 0,
                               pairDecision: pair, transferDecision: transfer,
                               transferReceived: received, transferWithdrawn: withdrawn)
    let ready = DispatchSemaphore(value: 0)
    var bound: UInt16?
    DispatchQueue.main.async { server.start { port in bound = port; ready.signal() } }
    ready.wait()
    return (server, bound ?? 0)
}

/// Plays the phone's side of the three-step pairing protocol.
func pairAsPhone(port: UInt16, id: UUID = UUID()) -> (finalizeStatus: Int, phoneCode: String, phoneSecret: String, deviceId: UUID, retryStatus: Int) {
    let phone = P256.KeyAgreement.PrivateKey()
    let phoneKey = phone.publicKey.x963Representation
    let (_, r1) = post(port, "/pair/request", ["deviceId": id.uuidString, "deviceName": "Check Phone", "platform": "android",
                                               "protocolVersion": 1, "features": [], "publicKey": phoneKey.base64EncodedString()])
    let macKey = Data(base64Encoded: r1["publicKey"] as? String ?? "") ?? Data()
    let commitment = Data(base64Encoded: r1["commitment"] as? String ?? "") ?? Data()
    let shared = try! phone.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: macKey))
    let phoneSecret = Data(SHA256.hash(data: shared.withUnsafeBytes { Data($0) })).base64EncodedString()
    let nonce = PairingCode.randomNonce()
    let (_, r2) = post(port, "/pair/confirm", ["deviceId": id.uuidString, "nonce": nonce.base64EncodedString()])
    let macNonce = Data(base64Encoded: r2["nonce"] as? String ?? "") ?? Data()
    check(PairingCode.commitment(publicKey: macKey, nonce: macNonce) == commitment, "Mac nonce matches its commitment")
    let code = PairingCode.code(initiatorKey: phoneKey, responderKey: macKey, initiatorNonce: nonce, responderNonce: macNonce)
    let (retry, _) = post(port, "/pair/confirm", ["deviceId": id.uuidString, "nonce": nonce.base64EncodedString()])
    let (status, _) = post(port, "/pair/finalize", ["deviceId": id.uuidString])
    return (status, code, phoneSecret, id, retry)
}

DispatchQueue.global().async {
    var approve = true
    var lastRequest: IncomingPairRequest?
    var macCode: String { lastRequest?.code ?? "" }
    let (server, port) = startServer(preferredPort: 0, approve: { approve }, lastRequest: { lastRequest = $0 })

    section("Server binds and answers /health") {
        check(port != 0, "bound a port")
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/health")!)
        request.timeoutInterval = 5
        let done = DispatchSemaphore(value: 0)
        var status = 0
        URLSession.shared.dataTask(with: request) { _, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            done.signal()
        }.resume()
        done.wait()
        check(status == 200, "health 200, got \(status)")
    }

    section("Repeated header does not crash the server") {
        // Raw request: URLSession merges duplicate headers, so write the bytes directly.
        let reply = rawRequest(port: port, "GET /health HTTP/1.1\r\nHost: x\r\nX-Dup: 1\r\nX-Dup: 2\r\n\r\n")
        check(reply.hasPrefix("HTTP/1.1 200"), "server answered the request with a repeated header")
        let (status, _) = post(port, "/pair/confirm", ["deviceId": UUID().uuidString, "nonce": PairingCode.randomNonce().base64EncodedString()])
        check(status == 409, "server still running afterwards")
    }

    section("Pairing: approve path") {
        let result = pairAsPhone(port: port)
        check(result.retryStatus == 409, "second confirm refused")
        check(result.finalizeStatus == 200, "finalize 200, got \(result.finalizeStatus)")
        check(result.phoneCode == macCode, "phone and Mac show the same code")
        check(TrustedPeerStore().find(deviceId: result.deviceId)?.authToken == result.phoneSecret, "Mac stored the same secret")
        check(lastRequest?.replacesExisting == false, "first pairing is not flagged as replacing a device")

        // The TLS listener restarts after pairing; the new phone's key must work right away.
        let identity = SecureTransport.identity(for: result.deviceId.uuidString)
        let psk = SecureTransport.psk(fromSecret: result.phoneSecret)!
        var status: Int?
        for _ in 0..<20 where status == nil {
            status = tlsHealth(port: server.securePort, identity: identity, psk: psk)
            if status == nil { Thread.sleep(forTimeInterval: 0.15) }
        }
        check(status == 200, "newly paired phone connects over TLS, got \(String(describing: status))")
        check(tlsHealth(port: server.securePort, identity: identity, psk: Data(repeating: 9, count: 32)) == nil, "wrong key rejected over TLS")
        check(tlsHealth(port: server.securePort, identity: "stranger", psk: psk) == nil, "unknown identity rejected over TLS")
        check(tlsHealth(port: server.securePort, identity: identity.uppercased(), psk: psk) == nil, "identity is matched exactly (lowercase)")

        let again = pairAsPhone(port: port, id: result.deviceId)
        check(again.finalizeStatus == 200, "re-pairing the same device succeeds when approved")
        check(lastRequest?.replacesExisting == true, "re-pairing is flagged as replacing the existing device")
    }

    section("Pairing: decline path") {
        approve = false
        let result = pairAsPhone(port: port)
        check(result.finalizeStatus == 403, "finalize 403, got \(result.finalizeStatus)")
        check(TrustedPeerStore().find(deviceId: result.deviceId) == nil, "declined phone not trusted")
        let (status, _) = post(port, "/pair/confirm", ["deviceId": UUID().uuidString, "nonce": PairingCode.randomNonce().base64EncodedString()])
        check(status == 409, "confirm without request refused")
    }

    section("A half-closed short body is refused instead of spinning") {
        let reply = halfClosedRequest(port: port, "POST /pair/request HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n0123456789")
        check(reply.hasPrefix("HTTP/1.1 400"), "truncated body gets 400, got \(reply.prefix(40))")
        // If the main queue were spinning, this would never be answered.
        let main = DispatchSemaphore(value: 0)
        DispatchQueue.main.async { main.signal() }
        check(main.wait(timeout: .now() + 2) == .success, "main queue still responsive")
        check(rawRequest(port: port, "GET /health HTTP/1.1\r\nHost: x\r\n\r\n").hasPrefix("HTTP/1.1 200"), "server still answers")
    }

    section("Another LAN host can't cancel or hijack a pairing in progress") {
        let id = UUID()
        let phone = P256.KeyAgreement.PrivateKey()
        let (status, _) = post(port, "/pair/request", ["deviceId": id.uuidString, "deviceName": "Phone", "platform": "android",
                                                       "protocolVersion": 2, "features": [],
                                                       "publicKey": phone.publicKey.x963Representation.base64EncodedString()])
        check(status == 200, "request accepted")
        let nonce = PairingCode.randomNonce().base64EncodedString()
        let (other, _) = post(port, "/pair/confirm", ["deviceId": id.uuidString, "nonce": nonce], host: "[::1]")
        check(other == 409, "confirm from another address refused, got \(other)")
        let (own, _) = post(port, "/pair/confirm", ["deviceId": id.uuidString, "nonce": nonce])
        check(own == 200, "phone's own confirm still works, got \(own)")
        let (replace, _) = post(port, "/pair/request", ["deviceId": id.uuidString, "deviceName": "Evil", "platform": "android",
                                                        "protocolVersion": 2, "features": [],
                                                        "publicKey": P256.KeyAgreement.PrivateKey().publicKey.x963Representation.base64EncodedString()],
                                host: "[::1]")
        check(replace == 429, "confirmed pairing can't be replaced, got \(replace)")
        let (finalizeOther, _) = post(port, "/pair/finalize", ["deviceId": id.uuidString], host: "[::1]")
        check(finalizeOther == 409, "finalize from another address refused, got \(finalizeOther)")
        let (finalize, _) = post(port, "/pair/finalize", ["deviceId": id.uuidString])
        check(finalize == 200 || finalize == 403, "phone's own finalize gets the decision, got \(finalize)")
    }

    section("Pairing spam can't grow pending pairings without bound") {
        func request(_ host: String = "127.0.0.1", name: String = "Spam", features: [String] = []) -> Int {
            var request = URLRequest(url: URL(string: "http://\(host):\(port)/pair/request")!)
            request.httpMethod = "POST"
            request.timeoutInterval = 10
            request.httpBody = try! JSONSerialization.data(withJSONObject: [
                "deviceId": UUID().uuidString, "deviceName": name, "platform": "android", "protocolVersion": 2,
                "features": features, "publicKey": P256.KeyAgreement.PrivateKey().publicKey.x963Representation.base64EncodedString()])
            let done = DispatchSemaphore(value: 0)
            var status = 0
            URLSession.shared.dataTask(with: request) { _, response, _ in
                status = (response as? HTTPURLResponse)?.statusCode ?? 0
                done.signal()
            }.resume()
            done.wait()
            return status
        }
        let longName = String(repeating: "x", count: 30_000)
        let statuses = (0..<20).map { _ in request(name: longName) }
        check(statuses.allSatisfy { $0 == 200 }, "requests from one address are answered, got \(Set(statuses))")
        check(DispatchQueue.main.sync { ControlServer.pendingPairingCount } == 1, "one address holds one unconfirmed pairing")
        check(request(features: Array(repeating: "f", count: 17)) == 400, "too many features refused")
        check(request(features: [String(repeating: "f", count: 33)]) == 400, "over-long feature refused")

        // A second address can't add one once the total cap is reached.
        let limit = ControlServer.maxPendingPairings
        ControlServer.maxPendingPairings = 1
        defer { ControlServer.maxPendingPairings = limit }
        check(request("[::1]") == 429, "cap reached: a new address gets 429")
        check(request() == 200, "the same address can still replace its own request")
    }

    section("Busy preferred port falls back to another port") {
        let (second, secondPort) = startServer(preferredPort: port, approve: { false })
        check(secondPort != 0 && secondPort != port, "got a different port (\(secondPort))")
        DispatchQueue.main.sync { second.stop() }
    }

    section("TLS key derivation matches the independently computed vector") {
        // HKDF-SHA256 of the 0x01..0x20 test secret; computed with Python hmac/hashlib.
        let psk = SecureTransport.psk(fromSecret: Data((1...32).map { UInt8($0) }).base64EncodedString())
        check(psk?.map { String(format: "%02x", $0) }.joined() == "f2a4ca2992eb3c84f5f991f285f26f6d40bd530fdaf194a4a7e3b44051da76a1", "psk vector")
        check(SecureTransport.identity(for: "C9BBD429-A655-4891-9965-5C24D4C0EC98") == "c9bbd429-a655-4891-9965-5c24d4c0ec98", "identity is lowercased")
    }

    section("Plain port refuses uploads") {
        let reply = rawRequest(port: port, "POST /transfer/upload HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
        check(reply.hasPrefix("HTTP/1.1 426"), "plain upload gets 426 Upgrade Required")
        check(reply.contains("tls_required"), "response says TLS is required")
    }

    section("Encrypted upload is received over TLS, verified and saved") {
        // Trust this device as its own peer so FileSender can sign the upload.
        let identity = IdentityStore().loadOrCreate()
        let secret = Security.generateToken()
        TrustedPeerStore().save(TrustedPeer(deviceId: identity.deviceId, deviceName: "Self", platform: "macos",
                                            protocolVersion: 1, features: [], pairedAt: Date(), authToken: secret))
        let reloaded = DispatchSemaphore(value: 0)
        DispatchQueue.main.async { server.reloadTrustedPeers { reloaded.signal() } }
        reloaded.wait()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("check-\(UUID().uuidString).bin")
        let payload = Data((0..<(Security.chunkSize + 999)).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        try payload.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        do {
            try FileSender.send(filePath: file.path, endpoint: "127.0.0.1:\(port)", secret: secret)
            check(false, "upload to the plain port must fail")
        } catch {
            check("\(error)".contains("Secure connection failed") || "\(error)".contains("did not respond"), "upload to the plain port fails the TLS handshake")
        }
        do {
            try FileSender.send(filePath: file.path, endpoint: "127.0.0.1:\(server.securePort)", secret: Security.generateToken())
            check(false, "upload with an unpaired key must fail")
        } catch {
            check("\(error)".contains("Secure connection failed"), "upload with the wrong key fails the TLS handshake")
        }
        try FileSender.send(filePath: file.path, endpoint: "127.0.0.1:\(server.securePort)", secret: secret)
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LinkBridge").appendingPathComponent(file.lastPathComponent)
        check((try? Data(contentsOf: downloads)) == payload, "saved file matches")
        check(downloads.path.hasPrefix(ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"] ?? "/nonexistent"), "wrote inside the throwaway home")
        let quarantine = (try? downloads.resourceValues(forKeys: [.quarantinePropertiesKey]))?.quarantineProperties
        check(quarantine?[kLSQuarantineAgentNameKey as String] as? String == "LinkBridge", "received file is quarantined")
    }

    section("Slow or silent connections are closed and capped") {
        let requestTimeout = ControlServer.requestTimeout
        let idleTimeout = ControlServer.uploadIdleTimeout
        let perSource = ControlServer.maxConnectionsPerSource
        ControlServer.requestTimeout = 0.5
        ControlServer.uploadIdleTimeout = 0.5
        defer {
            ControlServer.requestTimeout = requestTimeout
            ControlServer.uploadIdleTimeout = idleTimeout
            ControlServer.maxConnectionsPerSource = perSource
        }

        check(serverCloses(port: port), "silent plain connection is closed")
        check(serverCloses(port: port, "GET /dev"), "half a header is closed")
        check(serverCloses(port: port, "POST /pair/request HTTP/1.1\r\nContent-Length: 100\r\n\r\n{"), "unfinished body is closed")

        // A signed upload head that never sends its body is dropped by the idle timer.
        let identity = IdentityStore().loadOrCreate()
        let secret = TrustedPeerStore().find(deviceId: identity.deviceId)?.authToken ?? ""
        let psk = SecureTransport.psk(fromSecret: secret)!
        let tls = SecureTransport.clientParameters(identity: SecureTransport.identity(for: identity.deviceId.uuidString), psk: psk)
        let timestamp = String(Int64(Date().timeIntervalSince1970 * 1000))
        let transferId = UUID().uuidString
        let signature = Security.hmacSHA256Base64(secret: secret, message: ["POST", "/transfer/upload", identity.deviceId.uuidString,
                                                                           timestamp, transferId, Security.encryptionScheme, "1000"].joined(separator: "\n"))!
        let head = ["POST /transfer/upload HTTP/1.1", "Content-Length: 1000",
                    "X-LinkBridge-Device-Id: \(identity.deviceId.uuidString)", "X-LinkBridge-Timestamp: \(timestamp)",
                    "X-LinkBridge-Transfer-Id: \(transferId)", "X-LinkBridge-Encryption: \(Security.encryptionScheme)",
                    "X-LinkBridge-Signature: \(signature)", "", ""].joined(separator: "\r\n")
        check(serverCloses(port: server.securePort, parameters: tls, head), "stalled upload is closed")
        check(serverCloses(port: server.securePort, parameters: tls), "silent TLS connection is closed")

        // Over the per-source cap, a new connection is refused at once.
        ControlServer.requestTimeout = 3
        ControlServer.maxConnectionsPerSource = 2
        let held = (0..<2).map { _ in NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp) }
        held.forEach { $0.start(queue: .global()) }
        Thread.sleep(forTimeInterval: 0.3)
        check(DispatchQueue.main.sync { server.openConnectionCounts.plain } == 2, "two connections held open")
        check(serverCloses(port: port, timeout: 1), "third connection from the same address refused")
        held.forEach { $0.cancel() }
        Thread.sleep(forTimeInterval: 0.3)
        check(DispatchQueue.main.sync { server.openConnectionCounts.plain } == 0, "closed connections are no longer counted")
        check(rawRequest(port: port, "GET /health HTTP/1.1\r\nHost: x\r\n\r\n").hasPrefix("HTTP/1.1 200"), "server still answers")
    }

    section("Async approvals: pairing") {
        var answer: (IncomingPairRequest, @escaping (Bool) -> Void) -> Void = { _, done in done(true) }
        var seen: IncomingPairRequest?
        let (asyncServer, asyncPort) = startDecisionServer(pair: { request, done in seen = request; answer(request, done) })
        defer { DispatchQueue.main.sync { asyncServer.stop() } }

        answer = { _, done in DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) { done(true) } }
        let accepted = pairAsPhone(port: asyncPort)
        check(accepted.finalizeStatus == 200, "accept after a delay pairs, got \(accepted.finalizeStatus)")
        check(TrustedPeerStore().find(deviceId: accepted.deviceId) != nil, "accepted peer saved")
        let lifetime = ControlServer.pairingLifetime
        check(seen.map { abs($0.expiresAt.timeIntervalSinceNow - lifetime) < 10 } == true, "request carries its expiry")
        // Phones stop waiting on /pair/finalize after 120 s; a later Accept would pair one side only.
        check(lifetime < 120, "pairing expires before the phone's 120 s finalize timeout, is \(lifetime)")

        answer = { _, done in done(false) }
        let declined = pairAsPhone(port: asyncPort)
        check(declined.finalizeStatus == 403, "decline refuses finalize, got \(declined.finalizeStatus)")
        check(TrustedPeerStore().find(deviceId: declined.deviceId) == nil, "declined peer not saved")

        answer = { _, done in done(false); done(true) }
        let firstWins = pairAsPhone(port: asyncPort)
        check(firstWins.finalizeStatus == 403 && TrustedPeerStore().find(deviceId: firstWins.deviceId) == nil,
              "first completion wins")

        ControlServer.pairingLifetime = 0.3
        defer { ControlServer.pairingLifetime = lifetime }
        answer = { _, done in DispatchQueue.global().asyncAfter(deadline: .now() + 0.8) { done(true) } }
        let late = pairAsPhone(port: asyncPort)
        check(late.finalizeStatus == 403, "expired pairing is declined, got \(late.finalizeStatus)")
        Thread.sleep(forTimeInterval: 0.8)
        check(TrustedPeerStore().find(deviceId: late.deviceId) == nil, "late accept after expiry is ignored")
    }

    section("Async approvals: transfers") {
        let secret = TrustedPeerStore().find(deviceId: IdentityStore().loadOrCreate().deviceId)?.authToken ?? ""
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("async-\(UUID().uuidString).bin")
        let payload = Data((0..<(Security.chunkSize * 2 + 17)).map { UInt8(truncatingIfNeeded: $0 &* 13) })
        try payload.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        var answer: (IncomingTransferRequest, @escaping (Bool) -> Void) -> Void = { _, done in done(true) }
        var requests: [IncomingTransferRequest] = []
        var savedTo: URL?
        var withdrawnIds: [UUID] = []
        // A partial file left by a crash is removed when the server starts.
        let received = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("LinkBridge")
        let leftover = received.appendingPathComponent(".linkbridge-\(UUID().uuidString).part")
        try Data("half".utf8).write(to: leftover)
        let (asyncServer, _) = startDecisionServer(pair: { _, done in done(false) },
                                                   transfer: { request, done in requests.append(request); answer(request, done) },
                                                   received: { _, url in savedTo = url },
                                                   withdrawn: { withdrawnIds.append($0) })
        defer { DispatchQueue.main.sync { asyncServer.stop() } }
        let endpoint = "127.0.0.1:\(asyncServer.securePort)"

        answer = { _, done in DispatchQueue.global().asyncAfter(deadline: .now() + 0.4) { done(true) } }
        try FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)
        check(savedTo.flatMap { try? Data(contentsOf: $0) } == payload, "accepted after a delay: file saved and URL reported")
        check(!FileManager.default.fileExists(atPath: leftover.path), "leftover partial file removed at start")
        check(savedTo?.lastPathComponent == file.lastPathComponent, "saved under its real name, got \(savedTo?.lastPathComponent ?? "nil")")
        let parts = ((try? FileManager.default.contentsOfDirectory(atPath: received.path)) ?? []).filter { $0.hasSuffix(".part") }
        check(parts.isEmpty, "no partial files after a finished transfer, got \(parts)")

        answer = { _, done in done(false) }
        check((try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)) == nil, "declined transfer fails")

        // A decline holds off that sender's next prompt.
        let askedBefore = requests.count
        answer = { _, done in done(true) }
        check((try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)) == nil, "upload right after a decline is refused")
        check(requests.count == askedBefore, "user not asked again right after declining")
        DispatchQueue.main.sync { ControlServer.resetTransferApprovalLimits() }

        // Not enough free space: refused before the user is asked.
        let capacity = ControlServer.availableCapacity
        ControlServer.availableCapacity = { _ in ControlServer.freeSpaceMargin + 10 }
        let asked = requests.count
        answer = { _, done in done(true) }
        // The server replies 507 and closes while the sender is still writing, so the sender
        // may see that as a broken pipe; either way the send fails.
        check((try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)) == nil, "transfer bigger than the free space fails")
        check(requests.count == asked, "user not asked about a file that can't fit")
        ControlServer.availableCapacity = capacity

        // Senders stop waiting after 120 s, so the Mac must give up first and clear the row.
        let timeout = ControlServer.transferApprovalTimeout
        check(timeout < 120, "file approval gives up before the sender's 120 s timeout, is \(timeout)")
        ControlServer.transferApprovalTimeout = 0.4
        defer { ControlServer.transferApprovalTimeout = timeout }
        answer = { _, _ in }
        check((try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)) == nil, "unanswered transfer fails")
        Thread.sleep(forTimeInterval: 0.2)
        check(withdrawnIds == [requests.last!.id], "timeout withdraws the waiting request")

        // One prompt per sender at a time: a second upload while one waits is refused unasked.
        ControlServer.transferApprovalTimeout = 1.5
        answer = { _, _ in }
        let firstDone = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            _ = try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)
            firstDone.signal()
        }
        Thread.sleep(forTimeInterval: 0.5)
        let waiting = requests.count
        let started = Date()
        check((try? FileSender.send(filePath: file.path, endpoint: endpoint, secret: secret)) == nil, "second upload while one waits is refused")
        check(Date().timeIntervalSince(started) < 1, "refused at once, not after the approval timeout")
        check(requests.count == waiting, "no second prompt from the same sender")
        firstDone.wait()
    }

    section("Unpair removes a trusted peer") {
        let id = UUID()
        TrustedPeerStore().save(TrustedPeer(deviceId: id, deviceName: "Gone", platform: "android", protocolVersion: 2,
                                            features: [], pairedAt: Date(), authToken: "secret"))
        check(TrustedPeerStore().find(deviceId: id) != nil, "saved")
        TrustedPeerStore().remove(deviceId: id)
        check(TrustedPeerStore().find(deviceId: id) == nil, "removed")
    }

    section("PairingClient pairs as the initiator") {
        var macCode: String?
        let (pairServer, pairPort) = startServer(preferredPort: 0, approve: { true }, lastRequest: { macCode = $0.code })
        defer { DispatchQueue.main.sync { pairServer.stop() } }
        let done = DispatchSemaphore(value: 0)
        var result: Result<(PairingClient.Started, TrustedPeer), Error>?
        Task.detached {
            do {
                let client = PairingClient(endpoint: "127.0.0.1:\(pairPort)")
                let started = try await client.begin()
                result = .success((started, try await client.finish()))
            } catch {
                result = .failure(error)
            }
            done.signal()
        }
        check(done.wait(timeout: .now() + 20) == .success, "pairing finished in time")
        let (started, peer) = try result!.get()
        check(started.code.count == 6 && started.code == macCode, "both sides show the same code")
        check(TrustedPeerStore().find(deviceId: peer.deviceId)?.authToken == peer.authToken, "peer saved with its secret")
    }

    DispatchQueue.main.sync { server.stop() }
    print(failures == 0 ? "\nAll \(passed) checks passed." : "\n\(failures) failed, \(passed) passed.")
    exit(failures == 0 ? 0 : 1)
}

dispatchMain()
