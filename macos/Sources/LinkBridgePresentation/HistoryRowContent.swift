import Foundation

/// Display values for one finished row in History.
public struct HistoryRowContent {
    public let title: String
    public let detail: String
    public let symbol: String
    public let accent: Accent
    public let failureReason: String?
    public let canRetry: Bool
    public let canShowInFinder: Bool

    public init(_ record: TransferRecord) {
        let sent = record.direction == .sent
        title = record.fileName
        let size = ByteCountFormatter.string(fromByteCount: record.bytes, countStyle: .file)
        let peer = sent ? "To \(record.peerName)" : "From \(record.peerName)"
        detail = "\(peer) · \(size) · \(record.date.formatted(date: .abbreviated, time: .shortened))"
        if record.succeeded {
            symbol = sent ? "arrow.up.circle.fill" : "arrow.down.circle.fill"
        } else {
            symbol = "exclamationmark.circle.fill"
        }
        accent = record.succeeded ? .signal : .attention
        failureReason = record.succeeded ? nil : record.detail
        canRetry = sent && !record.succeeded && record.bookmark != nil
        canShowInFinder = !sent && record.succeeded && record.fileURL != nil
    }
}
