import Foundation
import Security

/// Stores secrets (identity token, pairing private key, per-peer secrets).
///
/// iOS, and Mac builds signed with a Team ID (Developer ID or App Store): the data-protection
/// Keychain, readable only while the device is unlocked, never synced and not restored to
/// another device.
///
/// Ad-hoc signed Mac builds (development, the checks): a 0600 file. With ad-hoc signing every
/// rebuild looks like a new app, so the Keychain asked for the login password again and again.
/// The first launch of a signed build moves the file's secrets into the Keychain and deletes it.
final class CredentialStore {
    private let backend: SecretBackend

    init() {
        backend = Self.defaultBackend
    }

    init(backend: SecretBackend) {
        self.backend = backend
    }

    func read(account: String) -> String? {
        backend.read(account: account)
    }

    /// Like `read`, but tells a missing item apart from a store that can't be read right now
    /// (locked device, corrupt file). Never create a replacement secret on `.unavailable`.
    func lookup(account: String) -> SecretLookup {
        backend.lookup(account: account)
    }

    @discardableResult
    func write(_ value: String, account: String) -> Bool {
        backend.write(value, account: account)
    }

    func delete(account: String) {
        backend.delete(account: account)
    }

    /// Chosen once per process.
    static let defaultBackend: SecretBackend = {
        #if os(iOS)
        return KeychainSecretBackend()
        #else
        let file = FileSecretBackend()
        guard hasTeamIdentifier() else { return file }
        let keychain = KeychainSecretBackend()
        // A signed build without the Keychain entitlement would fail every read and silently
        // look unpaired, so check the Keychain works before trusting it.
        guard keychain.probe() else {
            print("Warning: Keychain unavailable; keeping secrets in the local file store")
            return file
        }
        if migrate(from: file, to: keychain) {
            deleteLegacyLoginKeychainItems()
        }
        return keychain
        #endif
    }()

    #if os(macOS)
    /// Copies every file item into `target`, then deletes the file, but only if every item
    /// was written and reads back unchanged. Returns true when the file is gone.
    @discardableResult
    static func migrate(from file: FileSecretBackend, to target: SecretBackend) -> Bool {
        guard let items = file.allItems() else { return true }
        for (account, value) in items {
            guard target.write(value, account: account), target.read(account: account) == value else {
                print("Warning: could not move credential \(account) to the Keychain; keeping the file")
                return false
            }
        }
        return file.removeFile()
    }

    /// Before the 0600 file, ad-hoc builds kept secrets in the login keychain under this
    /// service. Nothing reads them, so delete them once the Keychain store is in use. Only on
    /// signed builds: on ad-hoc builds touching the login keychain prompts for the password.
    static func deleteLegacyLoginKeychainItems() {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: "com.linkbridge.credentials"]
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            print("Warning: could not delete old login keychain items: OSStatus \(status)")
        }
    }

    /// True when this process is signed with a Team ID, so its Keychain items keep a stable
    /// owner across updates.
    static func hasTeamIdentifier() -> Bool {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return false }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return false }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let info = info as? [String: Any] else { return false }
        return (info[kSecCodeInfoTeamIdentifier as String] as? String)?.isEmpty == false
    }
    #endif
}

enum SecretLookup: Equatable {
    case found(String)
    case notFound
    /// The store exists but can't be read (locked device, corrupt file, Keychain error).
    case unavailable
}

protocol SecretBackend: AnyObject {
    func lookup(account: String) -> SecretLookup
    @discardableResult func write(_ value: String, account: String) -> Bool
    func delete(account: String)
}

extension SecretBackend {
    func read(account: String) -> String? {
        if case let .found(value) = lookup(account: account) { return value }
        return nil
    }
}

/// Data-protection Keychain items, readable only while unlocked and never synced or migrated.
final class KeychainSecretBackend: SecretBackend {
    private let service: String

    init(service: String = "com.pshahlab.linkbridge.credentials") {
        self.service = service
    }

    func lookup(account: String) -> SecretLookup {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else { return .unavailable }
            return .found(value)
        case errSecItemNotFound:
            return .notFound
        default:
            // For example errSecInteractionNotAllowed while the device is locked.
            print("Warning: could not read credential \(account): OSStatus \(status)")
            return .unavailable
        }
    }

    @discardableResult
    func write(_ value: String, account: String) -> Bool {
        let data = Data(value.utf8)
        let query = baseQuery(account: account)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = SecItemAdd(item as CFDictionary, nil)
        }
        if status != errSecSuccess {
            print("Warning: could not save credential \(account): OSStatus \(status)")
        }
        return status == errSecSuccess
    }

    func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }

    /// Writes, reads back and deletes a throwaway item.
    func probe() -> Bool {
        let account = "probe.\(UUID().uuidString)"
        defer { delete(account: account) }
        return write("ok", account: account) && read(account: account) == "ok"
    }

    private func baseQuery(account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         // The file-based login keychain is what prompted for passwords on macOS; the
         // data-protection keychain is the iOS-style one and is the default on iOS.
         kSecUseDataProtectionKeychain as String: true]
    }
}

#if os(macOS)
/// ~/Library/Application Support/LinkBridge/credentials.json, readable only by the current
/// user (0600, directory 0700). Used by ad-hoc signed builds only; see `CredentialStore`.
/// Old login-keychain entries under "com.linkbridge.credentials" are not read.
final class FileSecretBackend: SecretBackend {
    private static let lock = NSLock()
    private let fileURL: URL

    init(fileURL: URL? = nil) {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.fileURL = fileURL ?? appSupport
            .appendingPathComponent("LinkBridge", isDirectory: true)
            .appendingPathComponent("credentials.json")
    }

    func lookup(account: String) -> SecretLookup {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        guard let items = load() else { return .unavailable }
        return items[account].map(SecretLookup.found) ?? .notFound
    }

    /// Refuses to write over a file it can't read, which would drop every other secret.
    @discardableResult
    func write(_ value: String, account: String) -> Bool {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        guard var items = load() else {
            print("Warning: credentials file is unreadable; not saving \(account)")
            return false
        }
        items[account] = value
        return persist(items, account: account)
    }

    func delete(account: String) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        guard var items = load(), items.removeValue(forKey: account) != nil else { return }
        persist(items, account: account)
    }

    /// Every item, or nil if there is no file.
    func allItems() -> [String: String]? {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return load() ?? [:]
    }

    /// Deletes the file. Returns true if it is gone.
    func removeFile() -> Bool {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        try? FileManager.default.removeItem(at: fileURL)
        return !FileManager.default.fileExists(atPath: fileURL.path)
    }

    /// Caller holds the lock.
    @discardableResult
    private func persist(_ items: [String: String], account: String) -> Bool {
        do {
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            let data = try JSONEncoder().encode(items)
            // Write a fresh 0600 file and rename it over the old one, so the secrets never sit
            // in a file with looser permissions (for example one restored from a backup).
            let temporary = directory.appendingPathComponent(".credentials-\(UUID().uuidString).tmp")
            guard FileManager.default.createFile(atPath: temporary.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
                throw CocoaError(.fileWriteUnknown)
            }
            do {
                let handle = try FileHandle(forWritingTo: temporary)
                try handle.write(contentsOf: data)
                try handle.synchronize()
                try handle.close()
                guard rename(temporary.path, fileURL.path) == 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
            } catch {
                try? FileManager.default.removeItem(at: temporary)
                throw error
            }
            return true
        } catch {
            print("Warning: could not save credential \(account): \(error.localizedDescription)")
            return false
        }
    }

    /// Every item, `[:]` if there is no file, or nil if the file exists but can't be read.
    private func load() -> [String: String]? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [:] }
        guard let data = try? Data(contentsOf: fileURL),
              let items = try? JSONDecoder().decode([String: String].self, from: data) else {
            print("Warning: could not read \(fileURL.lastPathComponent)")
            return nil
        }
        return items
    }
}
#endif
