import Foundation
@testable import LinkBridgeIOS
import LinkBridgeCore
import Testing

/// Stands in for `ControlServer` and keeps the callbacks ReceiveHost handed it.
private final class FakeServer: ServerControlling {
    let callbacks: ReceiveHostCallbacks
    var starts = 0
    var stops = 0

    init(callbacks: ReceiveHostCallbacks) { self.callbacks = callbacks }
    func start(onReady: @escaping (UInt16?) -> Void) { starts += 1; onReady(49152) }
    func stop() { stops += 1 }
}

@MainActor
private func makeHost() -> (ReceiveHost, () -> [FakeServer]) {
    var servers: [FakeServer] = []
    let host = ReceiveHost(makeServer: { callbacks in
        let server = FakeServer(callbacks: callbacks)
        servers.append(server)
        return server
    })
    return (host, { servers })
}

private func transferRequest(sender: UUID = UUID(), name: String = "photo.jpg") -> IncomingTransferRequest {
    IncomingTransferRequest(id: UUID(), senderName: "Pixel", senderId: sender, fileName: name, bytes: 2048)
}

private func pairRequest() -> IncomingPairRequest {
    IncomingPairRequest(id: UUID(), deviceName: "Pixel", deviceId: UUID(), platform: "android", code: "123456",
                        replacesExisting: false, expiresAt: Date().addingTimeInterval(110), sourceAddress: "192.168.1.5")
}

@MainActor
@Test func testStartsOnActiveStopsOnBackground() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    #expect(servers().count == 1)
    #expect(servers()[0].starts == 1)
    #expect(host.isRunning)

    host.sceneBecameActive()
    #expect(servers().count == 1, "already running: not started twice")
    #expect(servers()[0].starts == 1)

    host.sceneLeftForeground()
    #expect(servers()[0].stops == 1)
    #expect(!host.isRunning)

    host.sceneBecameActive()
    #expect(host.isRunning)
    #expect(servers().map(\.starts).reduce(0, +) == 2)
}

@MainActor
@Test func testBackgroundDeclinesPendingApprovals() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    let callbacks = servers()[0].callbacks
    var transferAnswers: [Bool] = []
    var pairAnswers: [Bool] = []
    callbacks.transferDecision(transferRequest()) { transferAnswers.append($0) }
    callbacks.transferDecision(transferRequest()) { transferAnswers.append($0) }
    callbacks.pairDecision(pairRequest()) { pairAnswers.append($0) }
    #expect(host.pendingTransfer != nil)
    #expect(host.pendingPair != nil)

    host.sceneLeftForeground()
    #expect(transferAnswers == [false, false], "every waiting file is declined")
    #expect(pairAnswers == [false])
    #expect(host.pendingTransfer == nil)
    #expect(host.pendingPair == nil)
}

@MainActor
@Test func answeringTransfersGoesInArrivalOrder() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    let callbacks = servers()[0].callbacks
    var answers: [String: Bool] = [:]
    callbacks.transferDecision(transferRequest(name: "a.txt")) { answers["a.txt"] = $0 }
    callbacks.transferDecision(transferRequest(name: "b.txt")) { answers["b.txt"] = $0 }
    #expect(host.pendingTransfer?.fileName == "a.txt")

    host.answer(transfer: true)
    #expect(answers == ["a.txt": true])
    #expect(host.pendingTransfer?.fileName == "b.txt")

    host.answer(transfer: false)
    #expect(answers == ["a.txt": true, "b.txt": false])
    #expect(host.pendingTransfer == nil)
}

@MainActor
@Test func withdrawnTransferLeavesThePrompt() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    let callbacks = servers()[0].callbacks
    let request = transferRequest()
    var answers: [Bool] = []
    callbacks.transferDecision(request) { answers.append($0) }
    callbacks.transferWithdrawn(request.id)
    #expect(host.pendingTransfer == nil)
    host.answer(transfer: true)
    #expect(answers.isEmpty, "the server already gave up; nothing to answer")
}

@MainActor
@Test func answeringPairCallsCompletionOnce() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    var answers: [Bool] = []
    servers()[0].callbacks.pairDecision(pairRequest()) { answers.append($0) }
    host.answer(pair: true)
    host.answer(pair: false)
    #expect(answers == [true])
    #expect(host.pendingPair == nil)
}

@MainActor
@Test func secondPairWhileOneWaitsIsDeclined() {
    let (host, servers) = makeHost()
    host.sceneBecameActive()
    let callbacks = servers()[0].callbacks
    var first: [Bool] = []
    var second: [Bool] = []
    let shown = pairRequest()
    callbacks.pairDecision(shown) { first.append($0) }
    callbacks.pairDecision(pairRequest()) { second.append($0) }
    #expect(second == [false])
    #expect(first.isEmpty)
    #expect(host.pendingPair?.id == shown.id)
}
