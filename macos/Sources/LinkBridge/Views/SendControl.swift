import LinkBridgePresentation
import SwiftUI

/// The one primary action in the device pane. A single glass shape that morphs between
/// "Choose a file", "Send", a progress pill, a check and "Retry".
struct SendControl: View {
    let phase: SendPhase
    let enabled: Bool
    let action: () -> Void

    @Namespace private var glass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GlassEffectContainer {
            content
                .glassEffectID("send", in: glass)
                .transition(reduceMotion ? .opacity : .identity)
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .bouncy, value: phase)
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .sending(_, let progress):
            HStack(spacing: 12) {
                ProgressView(value: progress)
                    .tint(Accent.action.color)
                    .frame(width: 160)
                Text(progress, format: .percent.precision(.fractionLength(0)))
                    .font(.subheadline.monospacedDigit().weight(.semibold))
            }
            .padding(.horizontal, 20)
            .frame(minHeight: 44)
            .glassEffect(.regular, in: .capsule)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(phase.actionTitle)
            .accessibilityValue("\(Int(progress * 100)) percent")
        case .sent:
            Label(phase.actionTitle, systemImage: phase.actionSymbol)
                .font(.headline)
                .foregroundStyle(Accent.signal.onColor)
                .padding(.horizontal, 24)
                .frame(minHeight: 44)
                .glassEffect(.regular.tint(Accent.signal.color), in: .capsule)
        default:
            Button(action: action) {
                Label(phase.actionTitle, systemImage: phase.actionSymbol)
                    .font(.headline)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 32)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.extraLarge)
            .tint(isFailed ? Accent.attention.color : Accent.action.color)
            .disabled(!enabled)
            .keyboardShortcut(.defaultAction)
        }
    }

    private var isFailed: Bool {
        if case .failed = phase { return true }
        return false
    }
}
