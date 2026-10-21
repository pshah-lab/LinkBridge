import LinkBridgePresentation
import Foundation
import LinkBridgeCore

struct PairingPrompt: Identifiable {
    let id = UUID()
    let peerName: String
    let code: String
}

@MainActor
final class LinkBridgeModel: ObservableObject {
    @Published private(set) var nearby: [ResolvedPeer] = []
    @Published private(set) var trusted: [TrustedPeer] = []
    @Published private(set) var selectedPeerId: UUID?
    @Published var pairingPrompt: PairingPrompt?
    @Published private(set) var pairingPeerId: UUID?
    @Published private(set) var sendPhase: SendPhase = .idle
    /// Last pairing or send failure; cleared by the next user action.
    @Published private(set) var lastError: String?
    /// When each device was last discovered, this run of the app only.
    @Published private(set) var lastSeen: [UUID: Date] = [:]
    @Published private(set) var history: [TransferRecord] = []

    let identity = IdentityStore().loadOrCreate()
    private let browser: PeerBrowser
    private let trustedStore = TrustedPeerStore()
    private var pairingClient: PairingClient?
    private var selectedFile: (url: URL, bookmark: Data?, bytes: Int64)?
    /// The device the user picked; survives backgrounding and brief drop-outs.
    private var preferredPeerId: UUID?
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
            let now = Date()
            for id in peers.compactMap(\.deviceId) { self.lastSeen[id] = now }
            self.selectedPeerId = DeviceSelection.resolve(current: self.selectedPeerId, preferred: self.preferredPeerId,
                                                          items: self.deviceItems, isSending: self.isSending)
        }
    }

    var deviceItems: [DeviceListItem] {
        DeviceListItem.merge(nearby: nearby, trusted: trusted, lastSeen: lastSeen, now: Date())
    }

    var selectedItem: DeviceListItem? {
        guard let selectedPeerId else { return nil }
        return deviceItems.first { $0.deviceId == selectedPeerId }
    }

    var connectionState: ConnectionState {
        ConnectionState.derive(nearbyCount: nearby.count, selected: selectedItem,
                               sendPhase: sendPhase, errorMessage: lastError)
    }

    var isSending: Bool {
        if case .sending = sendPhase { return true }
        return false
    }

    func startDiscovery() { browser.start() }
    func stopDiscovery() { browser.stop() }

    func select(_ item: DeviceListItem) {
        guard item.isSendable else { return }
        selectedPeerId = item.deviceId
        preferredPeerId = item.deviceId
        lastError = nil
    }

    func pair(with peer: ResolvedPeer) {
        guard pairingPeerId == nil else { return }
        let client = PairingClient(endpoint: peer.endpoint)
        pairingClient = client
        pairingPeerId = peer.deviceId
        lastError = nil
        Task {
            do {
                let started = try await client.begin()
                pairingPrompt = PairingPrompt(peerName: started.peerName, code: PairingCode.display(started.code))
            } catch {
                pairingFinished(error: error.localizedDescription)
            }
        }
    }

    /// The user confirmed the codes match; wait for approval on the other device.
    func confirmPairing() {
        guard let client = pairingClient else { return }
        pairingPrompt = nil
        Task {
            do {
                let peer = try await client.finish()
                trusted = trustedStore.load()
                // The Mac was discovered before it was paired; its tags only match now.
                browser.refresh()
                selectedPeerId = peer.deviceId
                preferredPeerId = peer.deviceId
                pairingFinished(error: nil)
            } catch {
                pairingFinished(error: error.localizedDescription)
            }
        }
    }

    func cancelPairing() {
        pairingPrompt = nil
        pairingFinished(error: nil)
    }

    private func pairingFinished(error: String?) {
        pairingClient = nil
        pairingPeerId = nil
        lastError = error
    }

    func choose(fileAt url: URL) {
        guard !isSending else { return }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        selectedFile = (url, try? url.bookmarkData(), bytes)
        lastError = nil
        sendPhase = .ready(fileName: url.lastPathComponent)
    }

    func sendSelectedFile() {
        guard let file = selectedFile, sendPhase.isActionEnabled(targetSendable: selectedItem?.isSendable == true),
              let peer = nearby.first(where: { $0.deviceId != nil && $0.deviceId == selectedPeerId }),
              let endpoint = peer.secureEndpoint, let id = peer.deviceId,
              let secret = trustedStore.find(deviceId: id)?.authToken else {
            lastError = "Pair with a nearby device first"
            return
        }
        let url = file.url
        let fileName = url.lastPathComponent
        lastError = nil
        sendPhase = .sending(fileName: fileName, progress: 0)

        Task.detached { [weak self] in
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            var failure: String?
            do {
                try FileSender.send(filePath: url.path, endpoint: endpoint, secret: secret) { sent, total in
                    let fraction = total > 0 ? Double(sent) / Double(total) : 0
                    Task { @MainActor in
                        guard let self, case .sending = self.sendPhase else { return }
                        self.sendPhase = .sending(fileName: fileName, progress: fraction)
                    }
                }
            } catch {
                failure = String(describing: error)
            }
            await self?.finishTransfer(fileName: fileName, peerName: peer.deviceName, bytes: file.bytes,
                                       bookmark: file.bookmark, failure: failure)
        }
    }

    /// Re-sends the file behind a failed history row to the selected device.
    func retry(_ record: TransferRecord) {
        guard !isSending else { return }
        var stale = false
        guard let bookmark = record.bookmark,
              let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale),
              Self.fileExists(at: url) else {
            lastError = "That file is no longer available"
            return
        }
        choose(fileAt: url)
        sendSelectedFile()
    }

    private static func fileExists(at url: URL) -> Bool {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func finishTransfer(fileName: String, peerName: String, bytes: Int64, bookmark: Data?, failure: String?) {
        if let failure {
            sendPhase = .failed(fileName: fileName, message: failure)
            lastError = "Couldn't send \(fileName)"
        } else {
            sendPhase = .sent(fileName: fileName)
            selectedFile = nil
            Task {
                try? await Task.sleep(for: .seconds(2.5))
                if sendPhase == .sent(fileName: fileName) { sendPhase = .idle }
            }
        }
        history.insert(TransferRecord(fileName: fileName, peerName: peerName, bytes: bytes, date: Date(),
                                      succeeded: failure == nil, detail: failure, bookmark: bookmark), at: 0)
        history = Array(history.prefix(50))
        if let data = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(data, forKey: Self.historyKey)
        }
    }
}
