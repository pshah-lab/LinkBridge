@testable import LinkBridgeIOS
import Testing

@Test func idleIsNeverEnabled() {
    #expect(!SendPhase.idle.isActionEnabled(targetSendable: true))
    #expect(SendPhase.idle.actionTitle == "Choose a file")
}

@Test func readyNeedsSendableTarget() {
    let phase = SendPhase.ready(fileName: "a.txt")
    #expect(phase.isActionEnabled(targetSendable: true))
    #expect(!phase.isActionEnabled(targetSendable: false))
    #expect(phase.actionTitle == "Send")
    #expect(phase.actionSymbol == "paperplane.fill")
}

@Test func sendingAndSentAreDisabled() {
    #expect(!SendPhase.sending(fileName: "a", progress: 0.5).isActionEnabled(targetSendable: true))
    #expect(!SendPhase.sent(fileName: "a").isActionEnabled(targetSendable: true))
    #expect(SendPhase.sending(fileName: "a", progress: 0.5).actionTitle == "Sending…")
    #expect(SendPhase.sent(fileName: "a").actionTitle == "Sent")
}

@Test func failedOffersRetry() {
    let phase = SendPhase.failed(fileName: "a", message: "x")
    #expect(phase.actionTitle == "Retry")
    #expect(phase.actionSymbol == "arrow.clockwise")
    #expect(phase.isActionEnabled(targetSendable: true))
}
