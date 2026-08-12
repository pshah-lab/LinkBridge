import Foundation

struct DeviceIdentity: Codable {
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
        if let data = try? Data(contentsOf: fileURL),
           let identity = try? JSONDecoder().decode(DeviceIdentity.self, from: data) {
            return identity
        }

        let identity = DeviceIdentity(
            deviceId: UUID(),
            deviceName: Host.current().localizedName ?? "Mac",
            platform: "macos",
            protocolVersion: 1,
            features: ["file-transfer", "display-send", "display-receive"]
        )

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

        return identity
    }
}

