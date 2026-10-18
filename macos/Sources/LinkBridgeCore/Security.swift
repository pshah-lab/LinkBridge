import CryptoKit
import Foundation

/// Upload encryption inside the TLS session: chunked AES-GCM records plus an HMAC over the
/// request head.
///
/// v2 (what senders use): separate encryption and MAC keys from HKDF-SHA256 of the pairing
/// secret (salt "linkbridge-chunk-v2", info "enc" / "mac"). Each record's AAD is
/// transferId (16 bytes) || record index (UInt64, big-endian) || the record's 4-byte length
/// field, whose top bit marks the last record. Records can't be reordered, dropped, spliced in
/// from another transfer, or cut off after a record boundary.
///
/// v1 (still accepted from older senders): the raw secret for both, no AAD.
enum Security {
    static let encryptionScheme = "aes-gcm-chunked-v2"
    static let legacyEncryptionScheme = "aes-gcm-chunked-v1"
    static let supportedSchemes: Set<String> = [encryptionScheme, legacyEncryptionScheme]
    static let chunkSize = 1024 * 1024
    /// Top bit of a v2 record's length field.
    static let lastRecordFlag: UInt32 = 0x8000_0000

    static func generateToken() -> String {
        let key = SymmetricKey(size: .bits256)
        return key.withUnsafeBytes { Data($0).base64EncodedString() }
    }

    static func hmacSHA256Base64(secret: String, message: String) -> String? {
        guard let keyData = Data(base64Encoded: secret) else {
            return nil
        }
        return hmacSHA256Base64(key: SymmetricKey(data: keyData), message: message)
    }

    static func hmacSHA256Base64(key: SymmetricKey, message: String) -> String {
        Data(HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)).base64EncodedString()
    }

    /// The upload head's signature for `scheme`.
    static func uploadSignature(secret: String, scheme: String, message: String) -> String? {
        guard let keys = keys(secret: secret, scheme: scheme) else { return nil }
        return hmacSHA256Base64(key: keys.mac, message: message)
    }

    /// Encryption and MAC keys for `scheme`, or nil for a bad secret or unknown scheme.
    static func keys(secret: String, scheme: String) -> (encryption: SymmetricKey, mac: SymmetricKey)? {
        guard let keyData = Data(base64Encoded: secret) else { return nil }
        let raw = SymmetricKey(data: keyData)
        switch scheme {
        case legacyEncryptionScheme:
            return (raw, raw)
        case encryptionScheme:
            func derive(_ info: String) -> SymmetricKey {
                HKDF<SHA256>.deriveKey(inputKeyMaterial: raw, salt: Data("linkbridge-chunk-v2".utf8),
                                       info: Data(info.utf8), outputByteCount: 32)
            }
            return (derive("enc"), derive("mac"))
        default:
            return nil
        }
    }

    /// AAD for a v2 record.
    static func recordAAD(transferId: UUID, index: UInt64, lengthField: UInt32) -> Data {
        transferId.bytes + index.bigEndianData + lengthField.bigEndianData
    }

    static func timingSafeEquals(_ left: String, _ right: String) -> Bool {
        let a = Array(left.utf8)
        let b = Array(right.utf8)
        guard a.count == b.count else { return false }
        var result: UInt8 = 0
        for index in a.indices {
            result |= a[index] ^ b[index]
        }
        return result == 0
    }

    /// Writes the metadata record (if any) and the file's chunks as v2 records.
    static func encryptChunkedFile(
        inputURL: URL,
        outputURL: URL,
        secret: String,
        metadata: Data? = nil,
        transferId: UUID,
        onProgress: (UInt64) -> Void = { _ in }
    ) throws -> UInt64 {
        guard let key = keys(secret: secret, scheme: encryptionScheme)?.encryption else {
            throw CryptoError.invalidSecret
        }

        let input = try FileHandle(forReadingFrom: inputURL)
        defer { try? input.close() }

        FileManager.default.createFile(atPath: outputURL.path, contents: nil)
        let output = try FileHandle(forWritingTo: outputURL)
        defer { try? output.close() }

        var encryptedBytes: UInt64 = 0
        var plainBytes: UInt64 = 0
        var index: UInt64 = 0

        func write(_ data: Data, last: Bool) throws {
            encryptedBytes += try writeEncryptedRecord(data, key: key, transferId: transferId, index: index, last: last, output: output)
            index += 1
        }

        // Read one chunk ahead so the last record can be marked.
        var next = try input.read(upToCount: chunkSize) ?? Data()
        if let metadata {
            try write(metadata, last: next.isEmpty)
        }
        while !next.isEmpty {
            let chunk = next
            next = try input.read(upToCount: chunkSize) ?? Data()
            try write(chunk, last: next.isEmpty)
            plainBytes += UInt64(chunk.count)
            onProgress(plainBytes)
        }
        if index == 0 {
            // Nothing at all: one empty last record, so the receiver sees a complete stream.
            try write(Data(), last: true)
        }

        return encryptedBytes
    }

    private static func writeEncryptedRecord(_ data: Data, key: SymmetricKey, transferId: UUID, index: UInt64,
                                             last: Bool, output: FileHandle) throws -> UInt64 {
        let record = try record(data, key: key, nonce: AES.GCM.Nonce(), transferId: transferId, index: index, last: last)
        try output.write(contentsOf: record)
        return UInt64(record.count)
    }

    /// One v2 record: nonce || length field || ciphertext || tag.
    static func record(_ data: Data, key: SymmetricKey, nonce: AES.GCM.Nonce, transferId: UUID, index: UInt64, last: Bool) throws -> Data {
        let lengthField = UInt32(data.count + 16) | (last ? lastRecordFlag : 0)
        let aad = recordAAD(transferId: transferId, index: index, lengthField: lengthField)
        let sealed = try AES.GCM.seal(data, using: key, nonce: nonce, authenticating: aad)
        return nonce.withUnsafeBytes { Data($0) } + lengthField.bigEndianData + sealed.ciphertext + sealed.tag
    }
}

final class ChunkedDecryptor {
    private let key: SymmetricKey
    private let scheme: String
    private let transferId: UUID
    private var buffer = Data()
    private var index: UInt64 = 0
    private var sawLastRecord = false

    init(secret: String, scheme: String = Security.encryptionScheme, transferId: UUID) throws {
        guard let key = Security.keys(secret: secret, scheme: scheme)?.encryption else {
            throw CryptoError.invalidSecret
        }
        self.key = key
        self.scheme = scheme
        self.transferId = transferId
    }

    private var isV2: Bool { scheme == Security.encryptionScheme }

    func consume(_ data: Data, onPlaintext: (Data) throws -> Void) throws {
        buffer.append(data)

        while true {
            guard buffer.count >= 16 else { return }
            // Nothing may follow the record marked last.
            guard !sawLastRecord else { throw CryptoError.invalidChunk }

            let lengthField = buffer[12..<16].uint32BigEndian
            let last = isV2 && lengthField & Security.lastRecordFlag != 0
            let sealedLength = Int(isV2 ? lengthField & ~Security.lastRecordFlag : lengthField)
            // At least the 16-byte tag; a shorter record would make the tag slice below trap.
            guard sealedLength >= 16 && sealedLength <= Security.chunkSize + 16 else {
                throw CryptoError.invalidChunk
            }

            let recordLength = 16 + sealedLength
            guard buffer.count >= recordLength else { return }

            let nonceData = Data(buffer[0..<12])
            let sealedData = Data(buffer[16..<recordLength])
            let tagStart = sealedData.count - 16
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: sealedData[..<tagStart],
                tag: sealedData[tagStart...]
            )
            let plaintext = isV2
                ? try AES.GCM.open(box, using: key,
                                   authenticating: Security.recordAAD(transferId: transferId, index: index, lengthField: lengthField))
                : try AES.GCM.open(box, using: key)
            buffer.removeSubrange(0..<recordLength)
            index += 1
            sawLastRecord = last
            try onPlaintext(plaintext)
        }
    }

    func finalize() throws {
        guard buffer.isEmpty, !isV2 || sawLastRecord else {
            throw CryptoError.incompleteCiphertext
        }
    }
}

enum CryptoError: Error {
    case invalidSecret
    case invalidChunk
    case incompleteCiphertext
}

private extension UInt32 {
    var bigEndianData: Data {
        var value = bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt32>.size)
    }
}

private extension UInt64 {
    var bigEndianData: Data {
        var value = bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt64>.size)
    }
}

private extension Data.SubSequence {
    var uint32BigEndian: UInt32 {
        reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}
