import Foundation
import LinkBridgeCore

struct TransferRecord: Codable, Identifiable {
    var id = UUID()
    let fileName: String
    let peerName: String
    let bytes: Int64
    let date: Date
    let succeeded: Bool
    let detail: String?
}

struct PairingPrompt: Identifiable {
    let id = UUID()
    let peerName: String
    let code: String
}

@MainActor
final class LinkBridgeModel: ObservableObject {
    @Published private(set) var nearby: [ResolvedPeer] = []
    @Published private(set) var trusted: [TrustedPeer] = []
    @Published var selectedPeerId: UUID?
    @Published var pairingPrompt: PairingPrompt?
    @Published private(set) var pairingPeerId: UUID?
    @Published private(set) var progress: Double?
    @Published private(set) var status = "Ready for private transfers over local Wi-Fi"
    @Published private(set) var history: [TransferRecord] = []

    let identity = IdentityStore().loadOrCreate()
    private let browser: PeerBrowser
    private let trustedStore = TrustedPeerStore()
    private var pairingClient: PairingClient?
    private static let historyKey = "transferHistory"

    init() {
        browser = PeerBrowser(localDeviceId: identity.deviceId)
        trusted = trustedStore.load()
        if let data = UserDefaults.standard.data(forKey: Self.historyKey),
           let saved = try? JSONDecoder().decode([TransferRecord].self, from: data) {
            history = saved
        }
        browser.onChange = { [weak self] peers in
            guard let self else { return }
            self.nearby = peers
            if self.selectedPeerId == nil {
                self.selectedPeerId = peers.first { self.isTrusted($0) && $0.secureEndpoint != nil }?.deviceId
            }
        }
    }

    var isSending: Bool { progress != nil }

    var selectedPeer: ResolvedPeer? {
        nearby.first { $0.deviceId != nil && $0.deviceId == selectedPeerId }
    }

    func startDiscovery() { browser.start() }
    func stopDiscovery() { browser.stop() }

    func isTrusted(_ peer: ResolvedPeer) -> Bool {
        guard let id = peer.deviceId else { return false }
        return trusted.contains { $0.deviceId == id }
    }

    /// Only the Mac answers pairing requests today; the Android app pairs by initiating.
    func canPair(_ peer: ResolvedPeer) -> Bool {
        peer.platform == "macos" && peer.deviceId != nil
    }

    func pair(with peer: ResolvedPeer) {
        guard pairingPeerId == nil else { return }
        let client = PairingClient(endpoint: peer.endpoint)
        pairingClient = client
        pairingPeerId = peer.deviceId
        status = "Contacting \(peer.deviceName)…"
        Task {
            do {
                let started = try await client.begin()
                pairingPrompt = PairingPrompt(peerName: started.peerName, code: PairingCode.display(started.code))
                status = "Check that \(started.peerName) shows the same code, then approve it there"
            } catch {
                pairingFinished(message: error.localizedDescription)
            }
        }
    }

    /// The user confirmed the codes match; wait for approval on the other device.
    func confirmPairing() {
        guard let client = pairingClient else { return }
        pairingPrompt = nil
        status = "Waiting for approval on the other device…"
        Task {
            do {
                let peer = try await client.finish()
                trusted = trustedStore.load()
                selectedPeerId = peer.deviceId
                pairingFinished(message: "Paired with \(peer.deviceName)")
            } catch {
                pairingFinished(message: error.localizedDescription)
            }
        }
    }

    func cancelPairing() {
        pairingPrompt = nil
        pairingFinished(message: "Pairing cancelled")
    }

    private func pairingFinished(message: String) {
        pairingClient = nil
        pairingPeerId = nil
        status = message
    }

    func send(fileAt url: URL) {
        guard let peer = selectedPeer, let endpoint = peer.secureEndpoint,
              let id = peer.deviceId, let secret = trustedStore.find(deviceId: id)?.authToken else {
            status = "Pair with a nearby device first"
            return
        }
        let fileName = url.lastPathComponent
        let accessing = url.startAccessingSecurityScopedResource()
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        progress = 0
        status = "Sending \(fileName) to \(peer.deviceName)…"

        Task.detached { [weak self] in
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            var failure: String?
            do {
                try FileSender.send(filePath: url.path, endpoint: endpoint, secret: secret) { sent, total in
                    let fraction = total > 0 ? Double(sent) / Double(total) : 0
                    Task { @MainActor in self?.progress = fraction }
                }
            } catch {
                failure = String(describing: error)
            }
            await self?.finishTransfer(fileName: fileName, peerName: peer.deviceName, bytes: bytes, failure: failure)
        }
    }

    private func finishTransfer(fileName: String, peerName: String, bytes: Int64, failure: String?) {
        progress = nil
        status = failure.map { "Could not send \(fileName): \($0)" } ?? "Sent \(fileName) to \(peerName)"
        history.insert(TransferRecord(fileName: fileName, peerName: peerName, bytes: bytes,
                                      date: Date(), succeeded: failure == nil, detail: failure), at: 0)
        history = Array(history.prefix(50))
        if let data = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(data, forKey: Self.historyKey)
        }
    }
}
