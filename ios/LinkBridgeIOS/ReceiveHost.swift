import Combine
import Foundation
import LinkBridgeCore

/// The part of `ControlServer` that ReceiveHost drives, so tests can use a fake.
protocol ServerControlling: AnyObject {
    func start(onReady: @escaping (UInt16?) -> Void)
    func stop()
}

extension ControlServer: ServerControlling {}

/// What a server built by ReceiveHost reports back. The server calls these on the main queue.
struct ReceiveHostCallbacks {
    let pairDecision: ControlServer.PairDecision
    let transferDecision: ControlServer.TransferDecision
    let transferReceived: (IncomingTransferRequest, URL) -> Void
    let transferWithdrawn: (UUID) -> Void
}

/// Runs the shared receive server while the app is in the foreground and holds the requests
/// waiting for the user. iOS suspends a backgrounded app, so the server stops when the app
/// leaves the foreground and anything still waiting is declined.
@MainActor
final class ReceiveHost: ObservableObject {
    /// Pairing request shown to the user; one at a time, as on the Mac.
    @Published private(set) var pendingPair: IncomingPairRequest?
    /// Oldest file waiting for the user; the others queue behind it.
    @Published private(set) var pendingTransfer: IncomingTransferRequest?
    @Published private(set) var isRunning = false

    /// Called after the user accepts a pairing, once the new device is saved.
    var onPaired: () -> Void = {}
    /// Called when a file has been saved to the receive folder.
    var onReceived: (IncomingTransferRequest, URL) -> Void = { _, _ in }

    private let makeServer: (ReceiveHostCallbacks) -> ServerControlling
    private var server: ServerControlling?
    private var pairCompletion: ((Bool) -> Void)?
    private var transfers: [(request: IncomingTransferRequest, completion: (Bool) -> Void)] = []

    init(makeServer: @escaping (ReceiveHostCallbacks) -> ServerControlling) {
        self.makeServer = makeServer
    }

    /// Where received files go: the app's Documents folder, visible in the Files app.
    static var receiveDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    func sceneBecameActive() {
        guard server == nil else { return }
        let server = makeServer(callbacks())
        self.server = server
        isRunning = true
        server.start { [weak self, weak server] port in
            MainActor.assumeIsolated {
                guard port == nil, let self, let server, self.server === server else { return }
                print("Receive server did not start")
                server.stop()
                self.server = nil
                self.isRunning = false
            }
        }
    }

    func sceneLeftForeground() {
        declineAll()
        server?.stop()
        server = nil
        isRunning = false
    }

    func answer(pair approved: Bool) {
        guard let completion = pairCompletion else { return }
        pairCompletion = nil
        pendingPair = nil
        completion(approved)
        // The server saves the device on the main queue right after the answer; run after it.
        if approved { DispatchQueue.main.async { [weak self] in self?.onPaired() } }
    }

    func answer(transfer approved: Bool) {
        guard !transfers.isEmpty else { return }
        let head = transfers.removeFirst()
        pendingTransfer = transfers.first?.request
        head.completion(approved)
    }

    private func declineAll() {
        answer(pair: false)
        let waiting = transfers
        transfers = []
        pendingTransfer = nil
        waiting.forEach { $0.completion(false) }
    }

    // MARK: - Server callbacks (main queue)

    private func callbacks() -> ReceiveHostCallbacks {
        ReceiveHostCallbacks(
            pairDecision: { [weak self] request, completion in
                MainActor.assumeIsolated { self?.ask(pair: request, completion: completion) ?? completion(false) }
            },
            transferDecision: { [weak self] request, completion in
                MainActor.assumeIsolated { self?.ask(transfer: request, completion: completion) ?? completion(false) }
            },
            transferReceived: { [weak self] request, url in
                MainActor.assumeIsolated { self?.onReceived(request, url) }
            },
            transferWithdrawn: { [weak self] id in
                MainActor.assumeIsolated { self?.withdraw(transfer: id) }
            }
        )
    }

    private func ask(pair request: IncomingPairRequest, completion: @escaping (Bool) -> Void) {
        // The server allows one pairing dialog at a time; refuse anything that slips past it.
        guard pairCompletion == nil else { return completion(false) }
        pairCompletion = completion
        pendingPair = request
        // The server declines the pairing at `expiresAt`; take the dialog down then too.
        let id = request.id
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, request.expiresAt.timeIntervalSinceNow)))
            guard let self, self.pendingPair?.id == id else { return }
            self.answer(pair: false)
        }
    }

    private func ask(transfer request: IncomingTransferRequest, completion: @escaping (Bool) -> Void) {
        transfers.append((request, completion))
        if pendingTransfer == nil { pendingTransfer = request }
    }

    /// The server gave up waiting (after `ControlServer.transferApprovalTimeout`).
    private func withdraw(transfer id: UUID) {
        transfers.removeAll { $0.request.id == id }
        pendingTransfer = transfers.first?.request
    }
}
