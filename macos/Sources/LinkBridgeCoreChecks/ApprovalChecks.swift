import Foundation
import LinkBridgePresentation

// Checks for the pending-approval queue the Mac shows in its popover, window and notifications.

private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

private func pairItem(_ id: UUID = UUID(), expiresAt: Date = t0.addingTimeInterval(180)) -> PendingApproval {
    PendingApproval(id: id, kind: .pair(deviceName: "Pixel 8", code: "123456", replacesExisting: false, expiresAt: expiresAt),
                    receivedAt: t0)
}

private func fileItem(_ id: UUID = UUID()) -> PendingApproval {
    PendingApproval(id: id, kind: .file(senderName: "Pixel 8", fileName: "a.pdf", bytes: 10), receivedAt: t0)
}

func runApprovalChecks() {
    section("Approval queue: order and removal") {
        var queue = ApprovalQueue()
        let a = fileItem(), b = pairItem()
        queue.add(a)
        queue.add(b)
        check(queue.oldest?.id == a.id, "oldest is the first to arrive")
        check(queue.remove(id: a.id)?.id == a.id, "remove returns the item")
        check(queue.oldest?.id == b.id && queue.items.count == 1, "next oldest takes its place")
        check(queue.remove(id: UUID()) == nil, "removing an unknown id is a no-op")
    }

    section("Approval queue: expiry") {
        var queue = ApprovalQueue()
        let pair = pairItem(expiresAt: t0.addingTimeInterval(1)), file = fileItem()
        queue.add(pair)
        queue.add(file)
        check(queue.expire(now: t0).isEmpty, "nothing expires early")
        check(queue.expire(now: t0.addingTimeInterval(2)) == [pair.id], "expired pairing reported")
        check(queue.items.map(\.id) == [file.id], "files never expire")
        check(queue.notices.first?.title == "Pairing request expired", "expiry leaves a notice")
        _ = queue.expire(now: t0.addingTimeInterval(8))
        check(queue.notices.isEmpty, "notice gone after 5 seconds")
    }

    section("Approval sheet: presentation rule") {
        let a = fileItem(), b = pairItem()
        check(ApprovalQueue.sheetItem(showing: nil, oldest: a, windowIsKey: true) == a, "opens for the oldest when the window is key")
        check(ApprovalQueue.sheetItem(showing: nil, oldest: a, windowIsKey: false) == nil, "doesn't open in a background window")
        // Showing a sheet takes key status from its window; that must not close the sheet.
        check(ApprovalQueue.sheetItem(showing: a, oldest: a, windowIsKey: false) == a, "stays open when the window loses key")
        check(ApprovalQueue.sheetItem(showing: a, oldest: b, windowIsKey: false) == b, "moves on to the next request")
        check(ApprovalQueue.sheetItem(showing: a, oldest: nil, windowIsKey: true) == nil, "closes when nothing is waiting")
    }

    MainActor.assumeIsolated {
        section("Approval center: resolve once") {
            var clock = t0
            let center = ApprovalCenter(now: { clock })
            var answers: [Bool] = []
            var removed: [UUID] = []
            center.onRemoved = { removed.append($0) }
            let id = UUID()
            center.enqueue(id: id, kind: pairItem().kind) { answers.append($0) }
            check(center.pendingCount == 1, "enqueued")
            center.resolve(id: id, approved: true)
            center.resolve(id: id, approved: false)
            check(answers == [true], "completion called once with the first answer")
            check(removed == [id], "removal reported once")
            check(center.pendingCount == 0, "resolved request leaves the queue")
            clock = t0
        }

        section("Approval center: withdraw, tick, decline all") {
            var clock = t0
            let center = ApprovalCenter(now: { clock })
            var answers: [UUID: Bool] = [:]
            var removed: [UUID] = []
            center.onRemoved = { removed.append($0) }

            let withdrawn = UUID()
            center.enqueue(id: withdrawn, kind: fileItem().kind) { answers[withdrawn] = $0 }
            center.withdraw(id: withdrawn)
            check(answers[withdrawn] == nil && center.pendingCount == 0 && removed == [withdrawn],
                  "withdraw removes without answering")

            let expiring = UUID()
            center.enqueue(id: expiring, kind: pairItem(expiresAt: t0.addingTimeInterval(1)).kind) { answers[expiring] = $0 }
            clock = t0.addingTimeInterval(2)
            center.tick()
            check(answers[expiring] == false && center.pendingCount == 0, "tick declines expired pairings")
            check(center.queue.notices.count == 1, "tick leaves the expiry notice")

            let a = UUID(), b = UUID()
            center.enqueue(id: a, kind: fileItem().kind) { answers[a] = $0 }
            center.enqueue(id: b, kind: pairItem(expiresAt: clock.addingTimeInterval(60)).kind) { answers[b] = $0 }
            center.declineAll()
            check(answers[a] == false && answers[b] == false && center.pendingCount == 0, "declineAll answers no to everything")
        }
    }
}
