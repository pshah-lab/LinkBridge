import LinkBridgePresentation
import SwiftUI

/// A solid row for one transfer: live (waiting, receiving, sending) or finished.
struct TransferRow: View {
    let row: MacModel.TransferRow
    @Environment(MacModel.self) private var model

    var body: some View {
        switch row {
        case .finished(let record):
            finished(record, content: HistoryRowContent(record))
        case .live(let live):
            switch live.kind {
            case .waiting(let item):
                waiting(item)
            case let .receiving(fileName, from, progress):
                progressRow(symbol: "arrow.down.circle", title: fileName, detail: "Receiving from \(from)", progress: progress)
            case let .sending(fileName, to, progress):
                progressRow(symbol: "arrow.up.circle", title: fileName, detail: "Sending to \(to)", progress: progress)
            }
        }
    }

    private func finished(_ record: TransferRecord, content: HistoryRowContent) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: content.symbol)
                .foregroundStyle(content.accent.color)
                .font(.title3)
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
            if content.canShowInFinder {
                Button("Show in Finder", systemImage: "folder") { model.showInFinder(record) }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .help("Show in Finder")
            }
            if content.canRetry {
                Button("Retry") { model.retry(record) }
                    .disabled(model.isSending)
            }
        }
        .padding(.vertical, 4)
        .contextMenu {
            if content.canShowInFinder { Button("Show in Finder") { model.showInFinder(record) } }
            if content.canRetry { Button("Retry") { model.retry(record) } }
            Button("Remove", role: .destructive) { model.removeHistory(record.id) }
        }
    }

    private func waiting(_ item: PendingApproval) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Accent.attention.color)
                .font(.title3)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                if case let .file(sender, fileName, _) = item.kind {
                    Text(fileName).lineLimit(1).truncationMode(.middle)
                    Text("Waiting for you · from \(sender)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            Button("Decline") { model.resolve(item.id, approved: false) }
            Button("Accept") { model.resolve(item.id, approved: true) }
                .buttonStyle(.borderedProminent)
                .tint(Accent.action.color)
        }
        .padding(.vertical, 4)
    }

    private func progressRow(symbol: String, title: String, detail: String, progress: Double) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .foregroundStyle(Accent.action.color)
                .font(.title3)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).lineLimit(1).truncationMode(.middle)
                ProgressView(value: progress)
                    .tint(Accent.action.color)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityValue("\(Int(progress * 100)) percent")
    }
}
