import Foundation
import Observation

/// One place every surface (popover, window sheet, notification) answers a request through,
/// so the server hears exactly one answer per request.
@MainActor @Observable
public final class ApprovalCenter {
    public private(set) var queue = ApprovalQueue()
    /// Called once whenever a request leaves the queue for any reason (e.g. to withdraw its notification).
    @ObservationIgnored public var onRemoved: (UUID) -> Void = { _ in }

    @ObservationIgnored private var completions: [UUID: (Bool) -> Void] = [:]
    @ObservationIgnored private let now: () -> Date

    public init(now: @escaping () -> Date = Date.init) {
        self.now = now
    }

    public var pendingCount: Int { queue.items.count }

    public func enqueue(id: UUID, kind: ApprovalKind, completion: @escaping (Bool) -> Void) {
        completions[id] = completion
        queue.add(PendingApproval(id: id, kind: kind, receivedAt: now()))
    }

    public func resolve(id: UUID, approved: Bool) {
        guard let completion = completions.removeValue(forKey: id) else { return }
        queue.remove(id: id)
        completion(approved)
        onRemoved(id)
    }

    /// The server gave up on the request; nothing to answer.
    public func withdraw(id: UUID) {
        guard completions.removeValue(forKey: id) != nil else { return }
        queue.remove(id: id)
        onRemoved(id)
    }

    /// Declines expired pairings and clears old notices. Call about once a second while anything is pending.
    public func tick() {
        for id in queue.expire(now: now()) {
            completions.removeValue(forKey: id)?(false)
            onRemoved(id)
        }
    }

    /// Declines everything, e.g. when the app quits.
    public func declineAll() {
        for item in queue.items { resolve(id: item.id, approved: false) }
    }
}
