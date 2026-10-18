import AppKit
import Foundation
import LinkBridgeCore
import LinkBridgePresentation
import Observation

/// Single source of truth for the window and the menu bar popover.
@MainActor @Observable
final class MacModel {
    /// A transfer that hasn't finished: waiting for the user, receiving, or sending.
    struct LiveTransfer: Identifiable {
        enum Kind {
            case waiting(PendingApproval)
            case receiving(fileName: String, from: String, progress: Double)
            case sending(fileName: String, to: String, progress: Double)
        }
        let id: UUID
        let kind: Kind
    }

    enum TransferRow: Identifiable {
        case live(LiveTransfer)
        case finished(TransferRecord)

        var id: UUID {
            switch self {
            case .live(let live): live.id
            case .finished(let record): record.id
            }
        }
    }

    private(set) var nearby: [ResolvedPeer] = []
    private(set) var trusted: [TrustedPeer] = []
    private(set) var lastSeen: [UUID: Date] = [:]
    private(set) var selectedPeerId: UUID?
    private(set) var sendPhase: SendPhase = .idle
    private(set) var history: [TransferRecord] = []
    private(set) var serverError: String?
    /// Last send failure; cleared by the next user action.
    private(set) var lastError: String?
    let approvals = ApprovalCenter()
    let identity: DeviceIdentity

    /// Called when a request starts waiting, so a notification can be posted.
    @ObservationIgnored var onApprovalAdded: (PendingApproval) -> Void = { _ in }
    /// Opens the SwiftUI window; set by a view that has `openWindow`.
    @ObservationIgnored var openMainWindow: () -> Void = {}

    func showMainWindow() {
        openMainWindow()
        NSApp.activate()
    }

    private var receiving: (id: UUID, fileName: String, sender: String, progress: Double, updatedAt: Date)?
    private var sendingTarget: String?

    @ObservationIgnored private var server: ControlServer?
    @ObservationIgnored private let browser: PeerBrowser
    @ObservationIgnored private let trustedStore = TrustedPeerStore()
    @ObservationIgnored private let historyStore = HistoryStore(key: "linkbridge.history.v2",
                                                               legacyMacKey: "linkbridge.transfer-history")
    @ObservationIgnored private var stagedFile: (url: URL, bookmark: Data?, bytes: Int64)?
    @ObservationIgnored private var preferredPeerId: UUID?
    @ObservationIgnored private var timer: Timer?
    /// File approvals by request ID, so an accepted file can show as "receiving".
    @ObservationIgnored private var fileRequests: [UUID: IncomingTransferRequest] = [:]

    /// Set when the user has turned notifications off for LinkBridge in System Settings.
    var notificationsDenied = false

    private static let selectedKey = "selectedDeviceId"
    static let notifyKey = "notifyIncoming"
    static let localNetworkMessage = "LinkBridge can't receive — allow it in Privacy & Security › Local Network."

    static func openLocalNetworkSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
            NSWorkspace.shared.open(url)
        }
    }

    init() {
        identity = IdentityStore().loadOrCreate()
        browser = PeerBrowser(localDeviceId: identity.deviceId)
        trusted = trustedStore.load()
        history = historyStore.load()
        preferredPeerId = UserDefaults.standard.string(forKey: Self.selectedKey).flatMap(UUID.init(uuidString:))
        selectedPeerId = preferredPeerId
    }

    // MARK: - Derived

    var deviceItems: [DeviceListItem] {
        DeviceListItem.merge(nearby: nearby, trusted: trusted, lastSeen: lastSeen, now: Date())
    }

    var selectedItem: DeviceListItem? {
        deviceItems.first { $0.deviceId != nil && $0.deviceId == selectedPeerId }
    }

    var connection: ConnectionState {
        ConnectionState.derive(nearbyCount: nearby.count, selected: selectedItem, sendPhase: sendPhase,
                               errorMessage: serverError ?? lastError)
    }

    var isSending: Bool {
        if case .sending = sendPhase { return true }
        return false
    }

    /// Live rows first (waiting files, the current receive, the current send), then finished ones.
    var rows: [TransferRow] {
        var live: [LiveTransfer] = approvals.queue.items.compactMap { item in
            guard case .file = item.kind else { return nil }
            return LiveTransfer(id: item.id, kind: .waiting(item))
        }
        if let receiving {
            live.append(LiveTransfer(id: receiving.id, kind: .receiving(fileName: receiving.fileName, from: receiving.sender,
                                                                        progress: receiving.progress)))
        }
        if case .sending(let fileName, let progress) = sendPhase {
            live.append(LiveTransfer(id: Self.sendRowId, kind: .sending(fileName: fileName, to: sendingTarget ?? "",
                                                                        progress: progress)))
        }
        return live.map(TransferRow.live) + history.map(TransferRow.finished)
    }

    private static let sendRowId = UUID()

    // MARK: - Lifecycle

    func start() {
        guard server == nil else { return }
        let server = ControlServer(
            identity: identity,
            pairDecision: { [weak self] request, done in
                MainActor.assumeIsolated { self?.enqueue(id: request.id, kind: ApprovalKind(request), done: done) }
            },
            transferDecision: { [weak self] request, done in
                MainActor.assumeIsolated {
                    self?.fileRequests[request.id] = request
                    self?.enqueue(id: request.id, kind: ApprovalKind(request), done: done)
                }
            },
            transferProgress: { [weak self] received, total in
                MainActor.assumeIsolated { self?.updateReceive(received: received, total: total) }
            },
            transferReceived: { [weak self] request, url in
                MainActor.assumeIsolated { self?.finishReceive(request, at: url) }
            },
            transferWithdrawn: { [weak self] id in
                MainActor.assumeIsolated { self?.approvals.withdraw(id: id) }
            }
        )
        self.server = server
        server.start { [weak self] port in
            MainActor.assumeIsolated { self?.serverError = port == nil ? Self.localNetworkMessage : nil }
        }

        browser.onChange = { [weak self] peers in
            MainActor.assumeIsolated { self?.discovered(peers) }
        }
        browser.start()

        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    func shutdown() {
        approvals.declineAll()
        timer?.invalidate()
        browser.stop()
        server?.stop()
    }

    private func discovered(_ peers: [ResolvedPeer]) {
        nearby = peers
        let now = Date()
        for id in peers.compactMap(\.deviceId) { lastSeen[id] = now }
        trusted = trustedStore.load()
        selectedPeerId = DeviceSelection.resolve(current: selectedPeerId, preferred: preferredPeerId,
                                                 items: deviceItems, isSending: isSending)
    }

    private func tick() {
        approvals.tick()
        // A receive that stops reporting progress has failed on the sender's side.
        if let receiving, Date().timeIntervalSince(receiving.updatedAt) > 15 { self.receiving = nil }
    }

    // MARK: - Approvals

    private func enqueue(id: UUID, kind: ApprovalKind, done: @escaping (Bool) -> Void) {
        approvals.enqueue(id: id, kind: kind) { [weak self] approved in
            done(approved)
            guard let self, let request = self.fileRequests.removeValue(forKey: id), approved else { return }
            self.receiving = (id, request.fileName, request.senderName, 0, Date())
        }
        if let item = approvals.queue.items.last, item.id == id { onApprovalAdded(item) }
    }

    func resolve(_ id: UUID, approved: Bool) {
        approvals.resolve(id: id, approved: approved)
    }

    private func updateReceive(received: Int64, total: Int64) {
        guard let current = receiving else { return }
        receiving = (current.id, current.fileName, current.sender, total > 0 ? Double(received) / Double(total) : 0, Date())
    }

    private func finishReceive(_ request: IncomingTransferRequest, at url: URL) {
        receiving = nil
        history = historyStore.add(TransferRecord(fileName: url.lastPathComponent, peerName: request.senderName,
                                                  bytes: Int64(request.bytes), date: Date(), succeeded: true,
                                                  detail: nil, direction: .received, fileURL: url))
    }

    // MARK: - Devices

    func select(_ id: UUID?) {
        preferredPeerId = id
        UserDefaults.standard.set(id?.uuidString, forKey: Self.selectedKey)
        guard !isSending else { return }
        selectedPeerId = id
    }

    func unpair(_ deviceId: UUID) {
        trustedStore.remove(deviceId: deviceId)
        trusted = trustedStore.load()
        server?.reloadTrustedPeers()
    }

    // MARK: - Sending

    /// Picks a file for the window's Send control.
    func stage(_ url: URL) {
        guard !isSending else { return }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        stagedFile = (url, try? url.bookmarkData(options: .withSecurityScope), bytes)
        lastError = nil
        sendPhase = .ready(fileName: url.lastPathComponent)
    }

    /// The popover's quick path: stage and send at once.
    func sendNow(_ url: URL) {
        guard !isSending else { return }
        stage(url)
        send()
    }

    func send() {
        guard let file = stagedFile, sendPhase.isActionEnabled(targetSendable: selectedItem?.isSendable == true),
              let peer = nearby.first(where: { $0.deviceId != nil && $0.deviceId == selectedPeerId }),
              let endpoint = peer.secureEndpoint, let id = peer.deviceId,
              let secret = trustedStore.find(deviceId: id)?.authToken else {
            lastError = "Pair with a nearby device first"
            return
        }
        let url = file.url
        let fileName = url.lastPathComponent
        let peerName = peer.deviceName
        lastError = nil
        sendingTarget = peerName
        sendPhase = .sending(fileName: fileName, progress: 0)

        Task.detached { [weak self] in
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            var failed = false
            do {
                try FileSender.send(filePath: url.path, endpoint: endpoint, secret: secret) { sent, total in
                    let fraction = total > 0 ? Double(sent) / Double(total) : 0
                    Task { @MainActor in
                        guard let self, case .sending = self.sendPhase else { return }
                        self.sendPhase = .sending(fileName: fileName, progress: fraction)
                    }
                }
            } catch {
                failed = true
            }
            await self?.finishSend(fileName: fileName, peerName: peerName, bytes: file.bytes,
                                   bookmark: file.bookmark, failed: failed)
        }
    }

    private func finishSend(fileName: String, peerName: String, bytes: Int64, bookmark: Data?, failed: Bool) {
        let failure = failed ? "Couldn't reach \(peerName) — check it's on the same Wi-Fi, then retry." : nil
        sendingTarget = nil
        if let failure {
            sendPhase = .failed(fileName: fileName, message: failure)
        } else {
            sendPhase = .sent(fileName: fileName)
            stagedFile = nil
            Task {
                try? await Task.sleep(for: .seconds(2.5))
                if sendPhase == .sent(fileName: fileName) { sendPhase = .idle }
            }
        }
        history = historyStore.add(TransferRecord(fileName: fileName, peerName: peerName, bytes: bytes, date: Date(),
                                                  succeeded: !failed, detail: failure, bookmark: bookmark))
    }

    /// Re-sends the file behind a failed history row to the selected device.
    func retry(_ record: TransferRecord) {
        guard !isSending else { return }
        var stale = false
        guard let bookmark = record.bookmark,
              let url = try? URL(resolvingBookmarkData: bookmark, options: .withSecurityScope,
                                 bookmarkDataIsStale: &stale) else {
            lastError = "That file is no longer available"
            return
        }
        sendNow(url)
    }

    // MARK: - History and Finder

    func showInFinder(_ record: TransferRecord) {
        guard let url = record.fileURL else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func removeHistory(_ id: UUID) {
        history = historyStore.remove(id: id)
    }

    var receiveFolder: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LinkBridge", isDirectory: true)
    }

    func openReceiveFolder() {
        try? FileManager.default.createDirectory(at: receiveFolder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(receiveFolder)
    }
}
