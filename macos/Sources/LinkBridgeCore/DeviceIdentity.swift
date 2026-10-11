import Foundation

public struct DeviceIdentity: Codable {
    public let deviceId: UUID
    public let deviceName: String
    public let platform: String
    public let protocolVersion: Int
    public let features: [String]
    public let authToken: String
    public let publicKey: String
}

struct PublicDeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let publicKey: String?
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
                return stored.identity(authToken: token, publicKey: pairingKeys.publicKeyBase64)
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
            deviceName: Host.current().localizedName ?? "Mac",
            platform: "macos",
            protocolVersion: 1,
            features: ["file-transfer", "display-send", "display-receive"],
            authToken: Security.generateToken(),
            publicKey: pairingKeys.publicKeyBase64
        )

        persist(identity)
        return identity
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
