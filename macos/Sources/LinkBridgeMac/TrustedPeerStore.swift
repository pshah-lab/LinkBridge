import Foundation

struct TrustedPeer: Codable {
    let deviceId: UUID
    let deviceName: String
    let platform: String
    let protocolVersion: Int
    let features: [String]
    let pairedAt: Date
}

final class TrustedPeerStore {
    private let fileURL: URL

    init() {
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
            let data = try JSONEncoder().encode(peers)
            try data.write(to: fileURL, options: [.atomic])
            print("Paired peer: \(peer.deviceName) (\(peer.deviceId.uuidString))")
        } catch {
            print("Warning: could not persist trusted peer: \(error.localizedDescription)")
        }
    }

    func load() -> [TrustedPeer] {
        guard let data = try? Data(contentsOf: fileURL),
              let peers = try? JSONDecoder().decode([TrustedPeer].self, from: data) else {
            return []
        }

        return peers
    }
}

