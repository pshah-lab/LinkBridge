import Foundation

/// State of the Send control: it morphs between these as a file is chosen and sent.
public enum SendPhase: Equatable {
    case idle
    case ready(fileName: String)
    case sending(fileName: String, progress: Double)
    case sent(fileName: String)
    case failed(fileName: String, message: String)

    public func isActionEnabled(targetSendable: Bool) -> Bool {
        switch self {
        case .ready, .failed: targetSendable
        case .idle, .sending, .sent: false
        }
    }

    public var actionTitle: String {
        switch self {
        case .idle: "Choose a file"
        case .ready: "Send"
        case .sending: "Sending…"
        case .sent: "Sent"
        case .failed: "Retry"
        }
    }

    public var actionSymbol: String {
        switch self {
        case .idle: "doc.badge.plus"
        case .ready: "paperplane.fill"
        case .sending: "arrow.up.circle"
        case .sent: "checkmark"
        case .failed: "arrow.clockwise"
        }
    }
}
