import Foundation

public struct DeviceIdentity: Codable {
    public let deviceId: UUID
    public let deviceName: String
    public let platform: String
    public let protocolVersion: Int
    public let features: [String]
    public let authToken: String
    public let publicKey: String

    func renamed(_ name: String) -> DeviceIdentity {
        DeviceIdentity(deviceId: deviceId, deviceName: name, platform: platform, protocolVersion: protocolVersion,
                       features: features, authToken: authToken, publicKey: publicKey)
    }
}

struct PublicDeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let publicKey: String?
    /// Sent in /pair/request only; see `PairingCode.pairingVersion`.
    var pairingVersion: Int? = nil
}

public final class IdentityStore {
    private let fileURL: URL
    private let credentials = CredentialStore()
    private let pairingKeys = PairingKeyStore()

    public init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.fileURL = appSupport
            .appendingPathComponent("LinkBridge", isDirectory: true)
            .appendingPathComponent("identity.json")
    }

    public func loadOrCreate() -> DeviceIdentity {
        if let data = try? Data(contentsOf: fileURL) {
            if let stored = try? JSONDecoder().decode(PersistedIdentity.self, from: data),
               let token = credentials.read(account: "identity.authToken") {
                // Always derive from the stored private key; older builds persisted a 64-byte encoding.
                let identity = stored.identity(authToken: token, publicKey: pairingKeys.publicKeyBase64)
                // Older builds defaulted to the computer name, which usually holds the owner's
                // name and is advertised on every network. Replace it if it's still that default.
                let generic = Self.defaultDeviceName
                if identity.deviceName != generic, identity.deviceName == Self.computerName {
                    return rename(identity, to: generic)
                }
                return identity
            }
            if let legacy = try? JSONDecoder().decode(DeviceIdentity.self, from: data) {
                credentials.write(legacy.authToken, account: "identity.authToken")
                persist(legacy)
                return legacy
            }
            if let legacy = try? JSONDecoder().decode(LegacyDeviceIdentity.self, from: data) {
                let migrated = DeviceIdentity(
                    deviceId: legacy.deviceId,
                    deviceName: legacy.deviceName,
                    platform: legacy.platform,
                    protocolVersion: legacy.protocolVersion,
                    features: legacy.features,
                    authToken: Security.generateToken(),
                    publicKey: pairingKeys.publicKeyBase64
                )
                persist(migrated)
                return migrated
            }
        }

        let identity = DeviceIdentity(
            deviceId: UUID(),
            deviceName: Self.defaultDeviceName,
            platform: Self.platformName,
            protocolVersion: 1,
            features: Self.defaultFeatures,
            authToken: Security.generateToken(),
            publicKey: pairingKeys.publicKeyBase64
        )

        persist(identity)
        return identity
    }

    #if os(iOS)
    // iOS 16+ only reveals the user-assigned device name with a special entitlement.
    private static let defaultDeviceName = "iPhone"
    private static let computerName: String? = nil
    private static let platformName = "ios"
    private static let defaultFeatures = ["file-transfer"]
    #else
    private static var computerName: String? { Host.current().localizedName }
    private static var defaultDeviceName: String { DeviceNames.withoutOwner(computerName ?? "Mac") }
    private static let platformName = "macos"
    private static let defaultFeatures = ["file-transfer", "display-send", "display-receive"]
    #endif

    /// Saves a new display name and returns the updated identity.
    @discardableResult
    public func rename(_ identity: DeviceIdentity, to name: String) -> DeviceIdentity {
        let renamed = identity.renamed(DeviceNames.sanitize(name))
        persist(renamed)
        return renamed
    }

    private func persist(_ identity: DeviceIdentity) {
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            credentials.write(identity.authToken, account: "identity.authToken")
            let data = try JSONEncoder().encode(PersistedIdentity(identity: identity))
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("Warning: could not persist identity: \(error.localizedDescription)")
        }
    }
}

private struct PersistedIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let publicKey: String?

    init(identity: DeviceIdentity) {
        deviceId = identity.deviceId
        deviceName = identity.deviceName
        platform = identity.platform
        protocolVersion = identity.protocolVersion
        features = identity.features
        publicKey = identity.publicKey
    }

    func identity(authToken: String, publicKey: String) -> DeviceIdentity {
        DeviceIdentity(deviceId: deviceId, deviceName: deviceName, platform: platform,
                       protocolVersion: protocolVersion, features: features, authToken: authToken, publicKey: publicKey)
    }
}

private struct LegacyDeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
}
