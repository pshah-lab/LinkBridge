import SwiftUI

/// History tab: every recorded send, newest first. Solid rows; failed rows can be retried.
struct HistoryView: View {
    @ObservedObject var model: LinkBridgeModel

    var body: some View {
        if model.history.isEmpty {
            ContentUnavailableView("No transfers yet", systemImage: "clock",
                                   description: Text("Files you send will appear here."))
        } else {
            List(model.history) { record in
                row(record)
            }
            .scrollContentBackground(.hidden)
        }
    }

    private func row(_ record: TransferRecord) -> some View {
        let content = HistoryRowContent(record)
        return HStack(spacing: 12) {
            Image(systemName: content.symbol)
                .font(.title3)
                .foregroundStyle(content.accent.color)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(content.title)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(content.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let reason = content.failureReason {
                    Text(reason)
                        .font(.caption)
                        .foregroundStyle(Accent.attention.color)
                }
            }
            Spacer(minLength: 8)
            if content.canRetry {
                Button("Retry") { model.retry(record) }
                    .buttonStyle(.borderless)
                    .tint(Accent.action.color)
                    .disabled(model.isSending)
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .swipeActions(edge: .leading) {
            if content.canRetry {
                Button("Retry") { model.retry(record) }
                    .tint(Accent.action.color)
            }
        }
    }
}
