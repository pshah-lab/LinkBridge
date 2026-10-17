import Foundation

public struct TrustedPeer: Codable {
    public let deviceId: UUID
    public let deviceName: String
    public let platform: String
    public let protocolVersion: Int
    public let features: [String]
    public let pairedAt: Date
    public let authToken: String?

    public init(deviceId: UUID, deviceName: String, platform: String, protocolVersion: Int,
                features: [String], pairedAt: Date, authToken: String?) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.protocolVersion = protocolVersion
        self.features = features
        self.pairedAt = pairedAt
        self.authToken = authToken
    }
}

public final class TrustedPeerStore {
    private let fileURL: URL
    private let credentials = CredentialStore()

    public init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.fileURL = appSupport
            .appendingPathComponent("LinkBridge", isDirectory: true)
            .appendingPathComponent("trusted-peers.json")
    }

    func save(_ peer: TrustedPeer) {
        var peers = load().filter { $0.deviceId != peer.deviceId }
        peers.append(peer)

        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            for peer in peers {
                if let token = peer.authToken {
                    credentials.write(token, account: "peer.\(peer.deviceId.uuidString)")
                }
            }
            let data = try JSONEncoder().encode(peers.map(PersistedTrustedPeer.init))
            try data.write(to: fileURL, options: [.atomic])
            print("Paired peer: \(peer.deviceName) (\(peer.deviceId.uuidString))")
        } catch {
            print("Warning: could not persist trusted peer: \(error.localizedDescription)")
        }
    }

    public func load() -> [TrustedPeer] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        if let stored = try? JSONDecoder().decode([PersistedTrustedPeer].self, from: data) {
            return stored.compactMap { peer in
                guard let token = credentials.read(account: "peer.\(peer.deviceId.uuidString)") else { return nil }
                return peer.peer(authToken: token)
            }
        }
        guard let legacy = try? JSONDecoder().decode([TrustedPeer].self, from: data) else { return [] }
        for peer in legacy {
            if let token = peer.authToken {
                credentials.write(token, account: "peer.\(peer.deviceId.uuidString)")
            }
        }
        return legacy
    }

    public func find(deviceId: UUID) -> TrustedPeer? {
        load().first { $0.deviceId == deviceId }
    }
}

private struct PersistedTrustedPeer: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let pairedAt: Date

    init(_ peer: TrustedPeer) {
        deviceId = peer.deviceId
        deviceName = peer.deviceName
        platform = peer.platform
        protocolVersion = peer.protocolVersion
        features = peer.features
        pairedAt = peer.pairedAt
    }

    func peer(authToken: String) -> TrustedPeer {
        TrustedPeer(deviceId: deviceId, deviceName: deviceName, platform: platform,
                    protocolVersion: protocolVersion, features: features, pairedAt: pairedAt,
                    authToken: authToken)
    }
}
