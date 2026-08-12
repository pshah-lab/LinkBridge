import CryptoKit
import Foundation

enum FileSender {
    static func send(filePath: String, endpoint: String) throws {
        let fileURL = URL(fileURLWithPath: filePath)
        let fileName = fileURL.lastPathComponent
        let encodedFileName = fileName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? fileName
        let fileSize = try fileSize(at: fileURL)
        let sha256 = try sha256Hex(of: fileURL)

        let parts = endpoint.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let port = UInt16(parts[1]) else {
            throw SenderError.invalidEndpoint
        }

        let host = parts[0]
        var request = URLRequest(url: URL(string: "http://\(host):\(port)/transfer/upload")!)
        request.httpMethod = "POST"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(encodedFileName, forHTTPHeaderField: "X-File-Name")
        request.setValue(String(fileSize), forHTTPHeaderField: "Content-Length")

        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Data, Error>?

        URLSession.shared.uploadTask(with: request, fromFile: fileURL) { body, response, error in
            if let error {
                result = .failure(error)
            } else if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                result = .failure(SenderError.httpStatus(http.statusCode, body ?? Data()))
            } else {
                result = .success(body ?? Data())
            }
            semaphore.signal()
        }.resume()

        semaphore.wait()

        let responseData = try result?.get() ?? Data()
        let responseText = String(decoding: responseData, as: UTF8.self)
        if !responseText.contains(sha256) {
            throw SenderError.checksumMissing
        }

        print("Sent \(fileName) to \(endpoint)")
        print("Bytes: \(fileSize)")
        print("SHA-256: \(sha256)")
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

    var description: String {
        switch self {
        case .invalidEndpoint:
            return "Endpoint must look like 192.168.1.10:49153"
        case let .httpStatus(status, body):
            return "Upload failed with HTTP \(status): \(String(decoding: body, as: UTF8.self))"
        case .checksumMissing:
            return "Upload response did not include the expected checksum"
        }
    }
}
