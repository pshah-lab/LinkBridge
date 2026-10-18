import Foundation
import LinkBridgeCore
import LinkBridgePresentation

// Checks for LinkBridgePresentation, the display logic shared by the Mac and iOS apps.
// Ported from the iOS app's tests; called from main.swift before the live server checks.

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)

private func item(_ id: UUID = UUID(), name: String = "Mac", sendable: Bool, nearby: Bool? = nil) -> DeviceListItem {
    DeviceListItem(id: id.uuidString, deviceId: id, name: name, platform: .mac, isPaired: true,
                   isNearby: nearby ?? sendable, isSendable: sendable, canPair: false, subtitle: "")
}

private func peer(_ name: String, id: UUID? = UUID(), platform: String? = "macos",
                  host: String = "10.0.0.5", tlsPort: UInt16? = 49162) -> ResolvedPeer {
    ResolvedPeer(deviceName: name, deviceId: id, platform: platform, host: host, port: 49152, tlsPort: tlsPort)
}

private func trusted(_ name: String, id: UUID, platform: String = "macos") -> TrustedPeer {
    TrustedPeer(deviceId: id, deviceName: name, platform: platform, protocolVersion: 2,
                features: [], pairedAt: Date(timeIntervalSince1970: 1_760_000_000), authToken: "t")
}

private func record(succeeded: Bool, direction: TransferDirection = .sent, bookmark: Data? = nil,
                    fileURL: URL? = nil) -> TransferRecord {
    TransferRecord(fileName: "photo.jpg", peerName: "Mac", bytes: 1_200_000, date: Date(),
                   succeeded: succeeded, detail: succeeded ? nil : "Connection lost",
                   direction: direction, bookmark: bookmark, fileURL: fileURL)
}

func runPresentationChecks() {
    section("Connection state") {
        let error = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true),
                                           sendPhase: .sending(fileName: "a", progress: 0.4), errorMessage: "Boom")
        check(error == .error(message: "Boom") && error.dotLabel == "Error" && error.dotAccent == .attention
              && error.title == "Boom", "error wins over everything")
        let sending = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true),
                                             sendPhase: .sending(fileName: "a", progress: 0.4), errorMessage: nil)
        check(sending == .sending(deviceName: "Mac", progress: 0.4) && sending.dotLabel == "Connected", "sending shows progress")
        let ready = ConnectionState.derive(nearbyCount: 1, selected: item(sendable: true), sendPhase: .idle, errorMessage: nil)
        check(ready == .ready(deviceName: "Mac") && ready.dotAccent == .signal && ready.title == "Mac", "sendable selection is ready")
        let searching = ConnectionState.derive(nearbyCount: 0, selected: nil, sendPhase: .idle, errorMessage: nil)
        check(searching == .searching && searching.title == "Looking for devices…" && searching.dotLabel == "Searching"
              && searching.dotAccent == nil, "nothing nearby is searching")
        let none = ConnectionState.derive(nearbyCount: 2, selected: item(sendable: false), sendPhase: .idle, errorMessage: nil)
        check(none == .noPairedDevice && none.title == "No paired device nearby", "nearby but not sendable")
        let blink = item(sendable: false, nearby: false)
        check(ConnectionState.derive(nearbyCount: 0, selected: blink, sendPhase: .sending(fileName: "a", progress: 0.5),
                                     errorMessage: nil) == .sending(deviceName: "Mac", progress: 0.5),
              "sending stays visible when the target blinks out")
    }

    section("Device list merge") {
        let a = UUID(), b = UUID(), c = UUID(), d = UUID()
        let items = DeviceListItem.merge(
            nearby: [peer("Zed Mac", id: a), peer("Unpaired Mac", id: c), peer("Alpha Mac", id: b)],
            trusted: [trusted("Zed Mac", id: a), trusted("Alpha Mac", id: b), trusted("Away Mac", id: d)],
            lastSeen: [:], now: fixedNow)
        check(items.map(\.name) == ["Alpha Mac", "Zed Mac", "Unpaired Mac", "Away Mac"], "order")

        let id = UUID()
        let paired = DeviceListItem.merge(nearby: [peer("Mac", id: id)], trusted: [trusted("Mac", id: id)],
                                          lastSeen: [:], now: fixedNow)[0]
        check(paired.subtitle == "Paired · Nearby" && paired.isSendable && paired.isPaired && paired.isNearby, "paired + TLS is sendable")
        let noTLS = DeviceListItem.merge(nearby: [peer("Mac", id: id, tlsPort: nil)], trusted: [trusted("Mac", id: id)],
                                         lastSeen: [:], now: fixedNow)[0]
        check(noTLS.subtitle == "Paired · needs a LinkBridge update" && !noTLS.isSendable, "paired without TLS needs update")
        let old = DeviceListItem.merge(nearby: [peer("Old Mac", id: nil)], trusted: [], lastSeen: [:], now: fixedNow)[0]
        check(old.id == "10.0.0.5:49152" && !old.canPair && !old.isSendable, "no device id uses endpoint id")
        let unpaired = DeviceListItem.merge(nearby: [peer("Mac")], trusted: [], lastSeen: [:], now: fixedNow)[0]
        check(unpaired.subtitle == "Not paired" && unpaired.canPair && unpaired.platform == .mac, "unpaired Mac can pair")
        let android = DeviceListItem.merge(nearby: [peer("Phone", platform: "android")], trusted: [],
                                           lastSeen: [:], now: fixedNow)[0]
        check(android.subtitle == "Pair from the Android app (coming soon)" && !android.canPair
              && android.platform == .android, "unpaired Android explains direction")
        let seen = UUID(), never = UUID()
        let away = DeviceListItem.merge(nearby: [], trusted: [trusted("A Mac", id: seen), trusted("B Mac", id: never)],
                                        lastSeen: [seen: fixedNow.addingTimeInterval(-300)], now: fixedNow)
        check(away[0].subtitle.hasPrefix("Last seen") && away[1].subtitle.hasPrefix("Paired ")
              && away.allSatisfy { !$0.isNearby && !$0.isSendable && $0.isPaired }, "away devices")
    }

    section("Device selection") {
        let a = UUID(), b = UUID()
        check(DeviceSelection.resolve(current: a, preferred: b, items: [item(a, sendable: true), item(b, sendable: true)],
                                      isSending: false) == b, "preferred wins when sendable")
        check(DeviceSelection.resolve(current: b, preferred: b, items: [], isSending: false) == b, "empty list keeps selection")
        check(DeviceSelection.resolve(current: b, preferred: b, items: [item(a, sendable: true), item(b, sendable: false)],
                                      isSending: true) == b, "sending never changes selection")
        let fallback = DeviceSelection.resolve(current: b, preferred: b,
                                               items: [item(a, sendable: true), item(b, sendable: false)], isSending: false)
        let back = DeviceSelection.resolve(current: fallback, preferred: b,
                                           items: [item(a, sendable: true), item(b, sendable: true)], isSending: false)
        check(fallback == a && back == b, "falls back while preferred is away, then returns")
    }

    section("Send phase") {
        check(!SendPhase.idle.isActionEnabled(targetSendable: true) && SendPhase.idle.actionTitle == "Choose a file", "idle")
        let ready = SendPhase.ready(fileName: "a.txt")
        check(ready.isActionEnabled(targetSendable: true) && !ready.isActionEnabled(targetSendable: false)
              && ready.actionTitle == "Send" && ready.actionSymbol == "paperplane.fill", "ready")
        check(!SendPhase.sending(fileName: "a", progress: 0.5).isActionEnabled(targetSendable: true)
              && !SendPhase.sent(fileName: "a").isActionEnabled(targetSendable: true)
              && SendPhase.sending(fileName: "a", progress: 0.5).actionTitle == "Sending…"
              && SendPhase.sent(fileName: "a").actionTitle == "Sent", "sending and sent disabled")
        let failed = SendPhase.failed(fileName: "a", message: "x")
        check(failed.actionTitle == "Retry" && failed.actionSymbol == "arrow.clockwise"
              && failed.isActionEnabled(targetSendable: true), "failed offers retry")
    }

    section("Dropped files") {
        check(try DroppedFile.stage(URL(string: "https://example.com")!) == nil, "web link rejected")
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("drop-\(UUID()).txt")
        try Data("hello".utf8).write(to: source)
        let staged = try DroppedFile.stage(source)
        try FileManager.default.removeItem(at: source)
        check(staged?.lastPathComponent == source.lastPathComponent, "staged keeps its name")
        check(staged.flatMap { try? Data(contentsOf: $0) } == Data("hello".utf8), "staged copy outlives the drop")

        // macOS: a drop already grants access, so the original file is sent, never copied.
        let original = FileManager.default.temporaryDirectory.appendingPathComponent("drop-\(UUID()).txt")
        try Data("hi".utf8).write(to: original)
        defer { try? FileManager.default.removeItem(at: original) }
        check(DroppedFile.sendable(original) == original, "a dropped file is used in place")
        check(DroppedFile.sendable(FileManager.default.temporaryDirectory) == nil, "a dropped folder is rejected")
        check(DroppedFile.sendable(URL(string: "https://example.com")!) == nil, "a dropped web link is rejected")
    }

    section("History rows") {
        let ok = HistoryRowContent(record(succeeded: true))
        check(ok.title == "photo.jpg" && ok.detail.hasPrefix("To Mac · 1.2 MB · ") && ok.symbol == "arrow.up.circle.fill"
              && ok.accent == .signal && ok.failureReason == nil && !ok.canRetry && !ok.canShowInFinder, "successful send")
        let failed = HistoryRowContent(record(succeeded: false, bookmark: Data([1])))
        check(failed.canRetry && failed.accent == .attention && failed.symbol == "exclamationmark.circle.fill"
              && failed.failureReason == "Connection lost", "failed send with bookmark can retry")
        check(!HistoryRowContent(record(succeeded: false)).canRetry, "failed send without bookmark cannot retry")

        let received = HistoryRowContent(TransferRecord(fileName: "a.pdf", peerName: "Pixel 8", bytes: 2048, date: .now,
                                                        succeeded: true, detail: nil, direction: .received,
                                                        fileURL: URL(fileURLWithPath: "/tmp/a.pdf")))
        check(received.detail.hasPrefix("From Pixel 8"), "received rows say From")
        check(received.symbol == "arrow.down.circle.fill", "received symbol")
        check(received.canShowInFinder && !received.canRetry, "received: Finder, no retry")
        check(!HistoryRowContent(record(succeeded: false, direction: .received, bookmark: Data([1]))).canRetry,
              "failed receive never offers retry")
    }

    section("TransferRecord decoding") {
        let legacy = #"[{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","fileName":"a.txt","peerName":"Mac","bytes":3,"date":0,"succeeded":true,"detail":null}]"#
        let records = try JSONDecoder().decode([TransferRecord].self, from: Data(legacy.utf8))
        check(records.count == 1 && records[0].fileName == "a.txt", "legacy iOS history decodes")
        check(records[0].direction == .sent && records[0].bookmark == nil && records[0].fileURL == nil, "defaults")
        let round = try JSONDecoder().decode(TransferRecord.self, from: JSONEncoder().encode(record(succeeded: true, direction: .received)))
        check(round.direction == .received, "direction round-trips")
    }

    section("Accent") {
        check(Accent.action.lightHex == 0x3E62E8 && Accent.action.darkHex == 0x7C95FF, "action hex")
        check(Accent.signal.lightHex == 0x35C98B && Accent.signal.darkHex == 0x4ADBA0, "signal hex")
        check(Accent.attention.lightHex == 0xF08A5D && Accent.attention.darkHex == 0xFF9F78, "attention hex")
        for accent in Accent.allCases {
            check(contrastRatio(accent.onLightHex, accent.lightHex) >= 4.5, "\(accent) light label contrast")
            check(contrastRatio(accent.onDarkHex, accent.darkHex) >= 4.5, "\(accent) dark label contrast")
        }
        check(abs(contrastRatio(0xFFFFFF, 0x000000) - 21) < 0.01 && abs(contrastRatio(0xFFFFFF, 0xFFFFFF) - 1) < 0.01,
              "known contrast values")
    }
}
