import Foundation
import LinkBridgeCore

public enum ApprovalKind: Equatable {
    case pair(deviceName: String, code: String, replacesExisting: Bool, expiresAt: Date)
    case file(senderName: String, fileName: String, bytes: Int)

    public init(_ request: IncomingPairRequest) {
        self = .pair(deviceName: request.deviceName, code: request.code,
                     replacesExisting: request.replacesExisting, expiresAt: request.expiresAt)
    }

    public init(_ request: IncomingTransferRequest) {
        self = .file(senderName: request.senderName, fileName: request.fileName, bytes: request.bytes)
    }
}

/// A pairing or incoming file waiting for the user.
public struct PendingApproval: Identifiable, Equatable {
    public let id: UUID
    public let kind: ApprovalKind
    public let receivedAt: Date

    public init(id: UUID, kind: ApprovalKind, receivedAt: Date) {
        self.id = id
        self.kind = kind
        self.receivedAt = receivedAt
    }
}

/// A short-lived line shown where an expired request used to be.
public struct ExpiredNotice: Identifiable, Equatable {
    public let id: UUID
    public let title: String
    public let until: Date
}

/// Pending approvals in arrival order. Pure value type; `ApprovalCenter` drives it.
public struct ApprovalQueue: Equatable {
    public private(set) var items: [PendingApproval] = []
    public private(set) var notices: [ExpiredNotice] = []

    static let noticeLifetime: TimeInterval = 5

    public init() {}

    public var oldest: PendingApproval? { items.first }

    public mutating func add(_ item: PendingApproval) {
        items.append(item)
    }

    @discardableResult
    public mutating func remove(id: UUID) -> PendingApproval? {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return nil }
        return items.remove(at: index)
    }

    /// Moves pairings whose `expiresAt <= now` into notices, drops notices whose time is up,
    /// and returns the IDs that expired.
    public mutating func expire(now: Date) -> [UUID] {
        notices.removeAll { $0.until <= now }
        let expired = items.filter {
            if case .pair(_, _, _, let expiresAt) = $0.kind { return expiresAt <= now }
            return false
        }
        items.removeAll { item in expired.contains { $0.id == item.id } }
        notices += expired.map {
            ExpiredNotice(id: $0.id, title: "Pairing request expired", until: now.addingTimeInterval(Self.noticeLifetime))
        }
        return expired.map(\.id)
    }
}
