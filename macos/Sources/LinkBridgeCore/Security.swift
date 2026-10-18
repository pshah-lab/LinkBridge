import CryptoKit
import Foundation

enum Security {
    static let encryptionScheme = "aes-gcm-chunked-v1"
    static let chunkSize = 1024 * 1024

    static func generateToken() -> String {
        let key = SymmetricKey(size: .bits256)
        return key.withUnsafeBytes { Data($0).base64EncodedString() }
    }

    static func hmacSHA256Base64(secret: String, message: String) -> String? {
        guard let keyData = Data(base64Encoded: secret) else {
            return nil
        }

        let key = SymmetricKey(data: keyData)
        let signature = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
        return Data(signature).base64EncodedString()
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

    static func encryptChunkedFile(
        inputURL: URL,
        outputURL: URL,
        secret: String,
        metadata: Data? = nil,
        onProgress: (UInt64) -> Void = { _ in }
    ) throws -> UInt64 {
        guard let keyData = Data(base64Encoded: secret) else {
            throw CryptoError.invalidSecret
        }

        let key = SymmetricKey(data: keyData)
        let input = try FileHandle(forReadingFrom: inputURL)
        defer { try? input.close() }

        FileManager.default.createFile(atPath: outputURL.path, contents: nil)
        let output = try FileHandle(forWritingTo: outputURL)
        defer { try? output.close() }

        var encryptedBytes: UInt64 = 0
        var plainBytes: UInt64 = 0

        if let metadata {
            encryptedBytes += try writeEncryptedRecord(metadata, key: key, output: output)
        }

        while true {
            let chunk = try input.read(upToCount: chunkSize) ?? Data()
            if chunk.isEmpty { break }

            encryptedBytes += try writeEncryptedRecord(chunk, key: key, output: output)
            plainBytes += UInt64(chunk.count)
            onProgress(plainBytes)
        }

        return encryptedBytes
    }

    private static func writeEncryptedRecord(_ data: Data, key: SymmetricKey, output: FileHandle) throws -> UInt64 {
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(data, using: key, nonce: nonce)
        let nonceData = nonce.withUnsafeBytes { Data($0) }
        let sealedData = sealed.ciphertext + sealed.tag
        let lengthData = UInt32(sealedData.count).bigEndianData

        try output.write(contentsOf: nonceData)
        try output.write(contentsOf: lengthData)
        try output.write(contentsOf: sealedData)

        return UInt64(nonceData.count + lengthData.count + sealedData.count)
    }
}

final class ChunkedDecryptor {
    private let key: SymmetricKey
    private var buffer = Data()

    init(secret: String) throws {
        guard let keyData = Data(base64Encoded: secret) else {
            throw CryptoError.invalidSecret
        }
        self.key = SymmetricKey(data: keyData)
    }

    func consume(_ data: Data, onPlaintext: (Data) throws -> Void) throws {
        buffer.append(data)

        while true {
            guard buffer.count >= 16 else { return }

            let sealedLength = Int(buffer[12..<16].uint32BigEndian)
            // At least the 16-byte tag; a shorter record would make the tag slice below trap.
            guard sealedLength >= 16 && sealedLength <= Security.chunkSize + 16 else {
                throw CryptoError.invalidChunk
            }

            let recordLength = 16 + sealedLength
            guard buffer.count >= recordLength else { return }

            let nonceData = Data(buffer[0..<12])
            let sealedData = Data(buffer[16..<recordLength])
            let tagStart = sealedData.count - 16
            let ciphertext = sealedData[..<tagStart]
            let tag = sealedData[tagStart...]
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            )
            let plaintext = try AES.GCM.open(box, using: key)
            try onPlaintext(plaintext)
            buffer.removeSubrange(0..<recordLength)
        }
    }

    func finalize() throws {
        guard buffer.isEmpty else {
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

private extension Data.SubSequence {
    var uint32BigEndian: UInt32 {
        reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}
