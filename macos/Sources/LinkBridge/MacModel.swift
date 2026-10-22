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
    private(set) var identity: DeviceIdentity

    /// Called when a request starts waiting, so a notification can be posted.
    @ObservationIgnored var onApprovalAdded: (PendingApproval) -> Void = { _ in }
    /// Opens the SwiftUI window; set by a view that has `openWindow`.
    @ObservationIgnored var openMainWindow: () -> Void = {}
    /// Set by menu commands; the main window switches its sidebar to it and clears it.
    var sidebarRequest: SidebarItem?

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
    /// Files picked or dropped together, sent one after another. Failed ones stay for Retry.
    @ObservationIgnored private var stagedFiles: [StagedFile] = []
    @ObservationIgnored private var preferredPeerId: UUID?
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var trustedObserver: NSObjectProtocol?
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
        let identity = IdentityStore().loadOrCreate()
        self.identity = identity
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

    /// Changes the name other devices see. Takes effect at once.
    func rename(to name: String) {
        identity = IdentityStore().rename(identity, to: name)
        server?.updateIdentity(identity)
    }

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

        // A device discovered before it was paired is only recognised once its secret is stored.
        trustedObserver = NotificationCenter.default.addObserver(forName: TrustedPeerStore.didChange, object: nil,
                                                                 queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.trusted = self.trustedStore.load()
                self.browser.refresh()
            }
        }

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

    private struct StagedFile {
        let url: URL
        let bookmark: Data?
        let bytes: Int64
    }

    /// Picks files for the window's Send control, up to `SendBatch.maxFiles`.
    func stage(_ urls: [URL]) {
        guard !isSending else { return }
        let files = Array(urls.prefix(SendBatch.maxFiles)).map { url in
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            return StagedFile(url: url, bookmark: try? url.bookmarkData(options: .withSecurityScope), bytes: bytes)
        }
        guard !files.isEmpty else { return }
        stagedFiles = files
        lastError = nil
        sendPhase = .ready(fileName: SendBatch.summary(files.map(\.url.lastPathComponent)))
    }

    /// The popover's quick path: stage and send at once.
    func sendNow(_ urls: [URL]) {
        guard !isSending else { return }
        stage(urls)
        send()
    }

    func send() {
        let files = stagedFiles
        guard !files.isEmpty, sendPhase.isActionEnabled(targetSendable: selectedItem?.isSendable == true),
              let peer = nearby.first(where: { $0.deviceId != nil && $0.deviceId == selectedPeerId }),
              let endpoint = peer.secureEndpoint, let id = peer.deviceId,
              let secret = trustedStore.find(deviceId: id)?.authToken else {
            lastError = "Pair with a nearby device first"
            return
        }
        let peerName = peer.deviceName
        lastError = nil
        sendingTarget = peerName
        sendPhase = .sending(fileName: SendBatch.progressName(files[0].url.lastPathComponent, index: 0, count: files.count),
                             progress: 0)

        Task.detached { [weak self] in
            var failedFiles: [StagedFile] = []
            for (index, file) in files.enumerated() {
                let url = file.url
                let fileName = url.lastPathComponent
                let shownName = SendBatch.progressName(fileName, index: index, count: files.count)
                await self?.showProgress(shownName, 0)
                let accessing = url.startAccessingSecurityScopedResource()
                var failed = false
                do {
                    try FileSender.send(filePath: url.path, endpoint: endpoint, secret: secret) { sent, total in
                        let fraction = total > 0 ? Double(sent) / Double(total) : 0
                        Task { @MainActor in self?.showProgress(shownName, fraction) }
                    }
                } catch {
                    failed = true
                }
                if accessing { url.stopAccessingSecurityScopedResource() }
                if failed { failedFiles.append(file) }
                await self?.recordSend(fileName: fileName, peerName: peerName, bytes: file.bytes,
                                       bookmark: file.bookmark, failed: failed)
            }
            await self?.finishSend(names: files.map(\.url.lastPathComponent), failed: failedFiles, peerName: peerName)
        }
    }

    private func showProgress(_ shownName: String, _ fraction: Double) {
        guard case .sending = sendPhase else { return }
        sendPhase = .sending(fileName: shownName, progress: fraction)
    }

    private func recordSend(fileName: String, peerName: String, bytes: Int64, bookmark: Data?, failed: Bool) {
        let failure = failed ? "Couldn't reach \(peerName) — check it's on the same Wi-Fi, then retry." : nil
        history = historyStore.add(TransferRecord(fileName: fileName, peerName: peerName, bytes: bytes, date: Date(),
                                                  succeeded: !failed, detail: failure, bookmark: bookmark))
    }

    private func finishSend(names: [String], failed: [StagedFile], peerName: String) {
        sendingTarget = nil
        if failed.isEmpty {
            let summary = SendBatch.summary(names)
            sendPhase = .sent(fileName: summary)
            stagedFiles = []
            Task {
                try? await Task.sleep(for: .seconds(2.5))
                if sendPhase == .sent(fileName: summary) { sendPhase = .idle }
            }
        } else {
            // Keep only the failures, so Retry resends just those.
            stagedFiles = failed
            let message = names.count == 1
                ? "Couldn't reach \(peerName) — check it's on the same Wi-Fi, then retry."
                : "\(failed.count) of \(names.count) files didn't reach \(peerName) — check it's on the same Wi-Fi, then retry."
            sendPhase = .failed(fileName: SendBatch.summary(failed.map(\.url.lastPathComponent)), message: message)
        }
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
        sendNow([url])
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
