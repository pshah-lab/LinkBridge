import LinkBridgePresentation
import Foundation
import LinkBridgeCore

struct PairingPrompt: Identifiable {
    enum Stage {
        /// Show the code to type on the peer, with any warning about an existing pairing.
        case code(PairingClient.ExistingPairing)
        /// The peer approved; ask whether it now lists this device as paired.
        case confirmOnPeer
    }

    let id = UUID()
    let peerName: String
    let code: String
    let stage: Stage
}

@MainActor
final class LinkBridgeModel: ObservableObject {
    @Published private(set) var nearby: [ResolvedPeer] = []
    @Published private(set) var trusted: [TrustedPeer] = []
    @Published private(set) var selectedPeerId: UUID?
    @Published var pairingPrompt: PairingPrompt?
    /// `DeviceListItem.id` of the device being paired.
    @Published private(set) var pairingItemId: String?
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
    /// Files chosen or dropped together, sent one after another. Failed ones stay for Retry.
    private var selectedFiles: [SelectedFile] = []
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
        guard pairingItemId == nil else { return }
        let client = PairingClient(endpoint: peer.endpoint)
        pairingClient = client
        pairingItemId = DeviceListItem.itemId(for: peer)
        lastError = nil
        Task {
            do {
                let started = try await client.begin()
                guard pairingClient === client else { return }
                pairingPrompt = PairingPrompt(peerName: started.peerName, code: PairingCode.display(started.code),
                                              stage: .code(started.existing))
            } catch {
                pairingFinished(error: error.localizedDescription)
            }
        }
    }

    /// The user typed the code on the other device; wait for its approval, then ask the user
    /// whether that device lists this one as paired. Nothing is saved until they say yes.
    func confirmPairing() {
        guard let client = pairingClient, let prompt = pairingPrompt else { return }
        pairingPrompt = nil
        Task {
            do {
                try await client.awaitApproval()
                // Cancelled or restarted while waiting: ignore the late approval.
                guard pairingClient === client else { return }
                pairingPrompt = PairingPrompt(peerName: prompt.peerName, code: prompt.code, stage: .confirmOnPeer)
            } catch {
                guard pairingClient === client else { return }
                pairingFinished(error: error.localizedDescription)
            }
        }
    }

    /// The user said the other device lists this one as paired: trust it.
    func acceptPairing() {
        guard let client = pairingClient else { return }
        pairingPrompt = nil
        do {
            let peer = try client.save()
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

    /// Cancel, or "No" to "does it list this device as paired?": nothing is saved.
    func cancelPairing() {
        pairingClient?.cancel()
        pairingPrompt = nil
        pairingFinished(error: nil)
    }

    /// The user said the other device doesn't list this one as paired.
    func rejectPairing() {
        pairingClient?.cancel()
        pairingPrompt = nil
        pairingFinished(error: "Not paired. If the other device didn't accept the code, someone may be intercepting this connection. Try again on a network you trust.")
    }

    private func pairingFinished(error: String?) {
        pairingClient = nil
        pairingItemId = nil
        lastError = error
    }

    private struct SelectedFile {
        let url: URL
        let bookmark: Data?
        let bytes: Int64
    }

    /// Picks files to send, up to `SendBatch.maxFiles`.
    func choose(filesAt urls: [URL]) {
        guard !isSending else { return }
        let files = Array(urls.prefix(SendBatch.maxFiles)).map { url in
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            return SelectedFile(url: url, bookmark: try? url.bookmarkData(), bytes: bytes)
        }
        guard !files.isEmpty else { return }
        selectedFiles = files
        lastError = nil
        sendPhase = .ready(fileName: SendBatch.summary(files.map(\.url.lastPathComponent)))
    }

    func sendSelectedFile() {
        let files = selectedFiles
        guard !files.isEmpty, sendPhase.isActionEnabled(targetSendable: selectedItem?.isSendable == true),
              let peer = nearby.first(where: { $0.deviceId != nil && $0.deviceId == selectedPeerId }),
              let endpoint = peer.secureEndpoint, let id = peer.deviceId,
              let secret = trustedStore.find(deviceId: id)?.authToken else {
            lastError = "Pair with a nearby device first"
            return
        }
        lastError = nil
        sendPhase = .sending(fileName: SendBatch.progressName(files[0].url.lastPathComponent, index: 0, count: files.count),
                             progress: 0)

        Task.detached { [weak self] in
            var failedFiles: [SelectedFile] = []
            var lastFailure: String?
            for (index, file) in files.enumerated() {
                let url = file.url
                let fileName = url.lastPathComponent
                let shownName = SendBatch.progressName(fileName, index: index, count: files.count)
                await self?.showProgress(shownName, 0)
                let accessing = url.startAccessingSecurityScopedResource()
                var failure: String?
                do {
                    try FileSender.send(filePath: url.path, endpoint: endpoint, secret: secret, peerName: peer.deviceName) { sent, total in
                        let fraction = total > 0 ? Double(sent) / Double(total) : 0
                        Task { @MainActor in self?.showProgress(shownName, fraction) }
                    }
                } catch {
                    failure = String(describing: error)
                }
                if accessing { url.stopAccessingSecurityScopedResource() }
                if let failure {
                    failedFiles.append(file)
                    lastFailure = failure
                }
                await self?.recordTransfer(fileName: fileName, peerName: peer.deviceName, bytes: file.bytes,
                                           bookmark: file.bookmark, failure: failure)
            }
            await self?.finishTransfer(names: files.map(\.url.lastPathComponent), failed: failedFiles,
                                       failure: lastFailure)
        }
    }

    private func showProgress(_ shownName: String, _ fraction: Double) {
        guard case .sending = sendPhase else { return }
        sendPhase = .sending(fileName: shownName, progress: fraction)
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
        choose(filesAt: [url])
        sendSelectedFile()
    }

    private static func fileExists(at url: URL) -> Bool {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func finishTransfer(names: [String], failed: [SelectedFile], failure: String?) {
        if let failure, !failed.isEmpty {
            // Keep only the failures, so Retry resends just those.
            selectedFiles = failed
            let failedNames = failed.map(\.url.lastPathComponent)
            let message = names.count == 1 ? failure : "\(failed.count) of \(names.count) files didn't send"
            sendPhase = .failed(fileName: SendBatch.summary(failedNames), message: message)
            lastError = "Couldn't send \(SendBatch.summary(failedNames))"
        } else {
            let summary = SendBatch.summary(names)
            sendPhase = .sent(fileName: summary)
            selectedFiles = []
            Task {
                try? await Task.sleep(for: .seconds(2.5))
                if sendPhase == .sent(fileName: summary) { sendPhase = .idle }
            }
        }
    }

    private func recordTransfer(fileName: String, peerName: String, bytes: Int64, bookmark: Data?, failure: String?) {
        history.insert(TransferRecord(fileName: fileName, peerName: peerName, bytes: bytes, date: Date(),
                                      succeeded: failure == nil, detail: failure, bookmark: bookmark), at: 0)
        history = Array(history.prefix(50))
        if let data = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(data, forKey: Self.historyKey)
        }
    }
}
