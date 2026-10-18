import LinkBridgeCore
import LinkBridgePresentation
import SwiftUI

/// What a pairing request or incoming file shows, in the popover row and the window sheet alike.
struct ApprovalContent: View {
    let item: PendingApproval
    let now: Date
    /// Return accepts and Escape declines; only in the sheet, where one request is on screen.
    var keyboardShortcuts = false
    let onDecide: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch item.kind {
            case let .pair(name, code, replacesExisting, expiresAt):
                Text("Pair with \(name)?")
                    .font(.headline)
                Text(PairingCode.display(code))
                    .font(.system(.largeTitle, design: .monospaced).weight(.semibold))
                    .accessibilityLabel("Code \(code.map(String.init).joined(separator: " "))")
                Text("Only accept if your phone shows the same code.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                if replacesExisting {
                    Text("\(name) is already paired. Accepting replaces its existing pairing. If you didn't just start pairing on that phone, decline.")
                        .font(.callout)
                }
                Text("Expires in \(Self.countdown(until: expiresAt, now: now))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            case let .file(sender, fileName, bytes):
                Text(fileName)
                    .font(.headline)
                    .lineLimit(2)
                    .truncationMode(.middle)
                Text("\(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)) · from \(sender)")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button("Decline") { onDecide(false) }
                    .keyboardShortcut(keyboardShortcuts ? .cancelAction : nil)
                Button("Accept") { onDecide(true) }
                    .keyboardShortcut(keyboardShortcuts ? .defaultAction : nil)
                    .buttonStyle(.glassProminent)
                    .tint(Accent.action.color)
            }
            .controlSize(.large)
        }
    }

    static func countdown(until date: Date, now: Date) -> String {
        let seconds = max(0, Int(date.timeIntervalSince(now).rounded(.up)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// Inline approval in the popover: coral-tinted glass, because it needs attention.
struct ApprovalRow: View {
    let item: PendingApproval
    let now: Date
    let onDecide: (Bool) -> Void

    var body: some View {
        ApprovalContent(item: item, now: now, onDecide: onDecide)
            .padding(14)
            .glassEffect(.regular.tint(Accent.attention.color.opacity(0.3)), in: .rect(cornerRadius: 16))
    }
}

/// The window's sheet for the oldest waiting request.
struct ApprovalSheet: View {
    let item: PendingApproval
    let onDecide: (Bool) -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            ApprovalContent(item: item, now: context.date, keyboardShortcuts: true, onDecide: onDecide)
                .padding(24)
                .frame(width: 400)
        }
    }
}
