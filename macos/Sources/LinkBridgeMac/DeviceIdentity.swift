import Foundation

struct DeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let authToken: String
}

struct PublicDeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
}

final class IdentityStore {
    private let fileURL: URL

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.fileURL = appSupport
            .appendingPathComponent("LinkBridge", isDirectory: true)
            .appendingPathComponent("identity.json")
    }

    func loadOrCreate() -> DeviceIdentity {
        if let data = try? Data(contentsOf: fileURL) {
            if let identity = try? JSONDecoder().decode(DeviceIdentity.self, from: data) {
                return identity
            }
            if let legacy = try? JSONDecoder().decode(LegacyDeviceIdentity.self, from: data) {
                let migrated = DeviceIdentity(
                    deviceId: legacy.deviceId,
                    deviceName: legacy.deviceName,
                    platform: legacy.platform,
                    protocolVersion: legacy.protocolVersion,
                    features: legacy.features,
                    authToken: Security.generateToken()
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
            authToken: Security.generateToken()
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
            let data = try JSONEncoder().encode(identity)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("Warning: could not persist identity: \(error.localizedDescription)")
        }
    }
}

private struct LegacyDeviceIdentity: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
}
