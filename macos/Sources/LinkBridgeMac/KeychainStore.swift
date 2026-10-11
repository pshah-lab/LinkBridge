import Foundation

/// Stores secrets (identity token, pairing private key, per-peer secrets) in
/// ~/Library/Application Support/LinkBridge/credentials.json, readable only by
/// the current user (0600, directory 0700).
///
/// This replaced the macOS Keychain: the app is ad-hoc signed, so every rebuild
/// looked like a new app and macOS asked for the login-keychain password again.
/// Old Keychain entries under "com.linkbridge.credentials" are no longer read.
final class KeychainStore {
    private static let lock = NSLock()
    private let fileURL: URL

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        fileURL = appSupport
            .appendingPathComponent("LinkBridge", isDirectory: true)
            .appendingPathComponent("credentials.json")
    }

    func read(account: String) -> String? {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        return load()[account]
    }

    @discardableResult
    func write(_ value: String, account: String) -> Bool {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        var items = load()
        items[account] = value
        do {
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            let data = try JSONEncoder().encode(items)
            // Create with 0600 before writing so the secrets are never world-readable.
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                FileManager.default.createFile(atPath: fileURL.path, contents: nil, attributes: [.posixPermissions: 0o600])
            }
            let handle = try FileHandle(forWritingTo: fileURL)
            defer { try? handle.close() }
            try handle.truncate(atOffset: 0)
            try handle.write(contentsOf: data)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
            return true
        } catch {
            print("Warning: could not save credential \(account): \(error.localizedDescription)")
            return false
        }
    }

    private func load() -> [String: String] {
        guard let data = try? Data(contentsOf: fileURL),
              let items = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return items
    }
}
