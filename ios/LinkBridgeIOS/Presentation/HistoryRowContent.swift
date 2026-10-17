import Foundation

/// Display values for one row of the History tab.
struct HistoryRowContent {
    let title: String
    let detail: String
    let symbol: String
    let accent: Accent
    let failureReason: String?
    let canRetry: Bool

    init(_ record: TransferRecord) {
        title = record.fileName
        let size = ByteCountFormatter.string(fromByteCount: record.bytes, countStyle: .file)
        detail = "To \(record.peerName) · \(size) · \(record.date.formatted(date: .abbreviated, time: .shortened))"
        symbol = record.succeeded ? "arrow.up.circle.fill" : "exclamationmark.circle.fill"
        accent = record.succeeded ? .signal : .attention
        failureReason = record.succeeded ? nil : record.detail
        canRetry = !record.succeeded && record.bookmark != nil
    }
}
