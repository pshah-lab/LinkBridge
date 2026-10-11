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

// MARK: - Live server checks (run off the main thread; the server uses the main queue)

func startServer(preferredPort: UInt16, approve: @escaping () -> Bool, lastCode: @escaping (String) -> Void = { _ in }) -> (ControlServer, UInt16) {
    let identity = IdentityStore().loadOrCreate()
    let server = ControlServer(identity: identity, preferredPort: preferredPort, pairApproval: { request in
        lastCode(request.code)
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

func post(_ port: UInt16, _ path: String, _ json: [String: Any]) -> (Int, [String: Any]) {
    var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(path)")!)
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

/// Plays the phone's side of the three-step pairing protocol.
func pairAsPhone(port: UInt16) -> (finalizeStatus: Int, phoneCode: String, phoneSecret: String, deviceId: UUID, retryStatus: Int) {
    let phone = P256.KeyAgreement.PrivateKey()
    let phoneKey = phone.publicKey.x963Representation
    let id = UUID()
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
    var macCode = ""
    let (server, port) = startServer(preferredPort: 0, approve: { approve }, lastCode: { macCode = $0 })

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
        let raw = "GET /health HTTP/1.1\r\nHost: x\r\nX-Dup: 1\r\nX-Dup: 2\r\n\r\n"
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let done = DispatchSemaphore(value: 0)
        var reply = ""
        connection.start(queue: .global())
        connection.send(content: Data(raw.utf8), completion: .contentProcessed { _ in })
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
            reply = String(decoding: data ?? Data(), as: UTF8.self)
            done.signal()
        }
        _ = done.wait(timeout: .now() + 5)
        connection.cancel()
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
    }

    section("Pairing: decline path") {
        approve = false
        let result = pairAsPhone(port: port)
        check(result.finalizeStatus == 403, "finalize 403, got \(result.finalizeStatus)")
        check(TrustedPeerStore().find(deviceId: result.deviceId) == nil, "declined phone not trusted")
        let (status, _) = post(port, "/pair/confirm", ["deviceId": UUID().uuidString, "nonce": PairingCode.randomNonce().base64EncodedString()])
        check(status == 409, "confirm without request refused")
    }

    section("Busy preferred port falls back to another port") {
        let (second, secondPort) = startServer(preferredPort: port, approve: { false })
        check(secondPort != 0 && secondPort != port, "got a different port (\(secondPort))")
        DispatchQueue.main.sync { second.stop() }
    }

    section("Encrypted upload is received, verified and saved") {
        // Trust this device as its own peer so FileSender can sign the upload.
        let identity = IdentityStore().loadOrCreate()
        let secret = Security.generateToken()
        TrustedPeerStore().save(TrustedPeer(deviceId: identity.deviceId, deviceName: "Self", platform: "macos",
                                            protocolVersion: 1, features: [], pairedAt: Date(), authToken: secret))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("check-\(UUID().uuidString).bin")
        let payload = Data((0..<(Security.chunkSize + 999)).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        try payload.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        try FileSender.send(filePath: file.path, endpoint: "127.0.0.1:\(port)", secret: secret)
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LinkBridge").appendingPathComponent(file.lastPathComponent)
        check((try? Data(contentsOf: downloads)) == payload, "saved file matches")
        check(downloads.path.hasPrefix(ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"] ?? "/nonexistent"), "wrote inside the throwaway home")
    }

    DispatchQueue.main.sync { server.stop() }
    print(failures == 0 ? "\nAll \(passed) checks passed." : "\n\(failures) failed, \(passed) passed.")
    exit(failures == 0 ? 0 : 1)
}

dispatchMain()
