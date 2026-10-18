import LinkBridgeCore
import LinkBridgePresentation
import SwiftUI

/// What a pairing request or incoming file shows, in the popover row and the window sheet alike.
struct ApprovalContent: View {
    let item: PendingApproval
    let now: Date
    /// Escape declines, and for files Return accepts; only in the sheet, where one request is
    /// on screen. Pairing has no default button, so Return can't pair a device by accident.
    var keyboardShortcuts = false
    let onDecide: (Bool) -> Void
    @State private var enteredCode = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch item.kind {
            case let .pair(name, _, replacesExisting, expiresAt, sourceAddress):
                // A different icon and layout from file prompts, so a pairing request can't pass
                // for an incoming file at a glance.
                Label("Pair with \(name)?", systemImage: "link.badge.plus")
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(replacesExisting ? "Already paired · \(sourceAddress)" : "New device · \(sourceAddress)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                // The Mac doesn't show its own code: typing the one on the phone is the check.
                TextField("Code shown on the device", text: $enteredCode)
                    .font(.system(.title2, design: .monospaced).weight(.semibold))
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: enteredCode) { _, text in
                        let digits = PairingCodeEntry.digits(text)
                        if digits != text { enteredCode = digits }
                    }
                    .accessibilityHint("Type the six-digit code shown on the device that is pairing")
                Text("If the device isn't showing a code, or you didn't start pairing, decline.")
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
                Label(fileName, systemImage: "doc")
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
                Button(isPairing ? "Pair" : "Accept") { onDecide(true) }
                    .keyboardShortcut(keyboardShortcuts && !isPairing ? .defaultAction : nil)
                    .disabled(!codeMatches)
                    .buttonStyle(.glassProminent)
                    .tint(Accent.action.color)
            }
            .controlSize(.large)
        }
    }

    /// Files need no code; pairing needs the phone's code typed in.
    private var codeMatches: Bool {
        guard case let .pair(_, code, _, _, _) = item.kind else { return true }
        return PairingCodeEntry.matches(enteredCode, code: code)
    }

    private var isPairing: Bool {
        if case .pair = item.kind { return true }
        return false
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
