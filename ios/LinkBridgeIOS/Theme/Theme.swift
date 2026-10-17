import SwiftUI
import UIKit

/// Accent colours from the shared LinkBridge design language.
enum Accent: CaseIterable {
    case action, signal, attention

    var lightHex: UInt32 {
        switch self {
        case .action: 0x3E62E8
        case .signal: 0x35C98B
        case .attention: 0xF08A5D
        }
    }

    var darkHex: UInt32 {
        switch self {
        case .action: 0x7C95FF
        case .signal: 0x4ADBA0
        case .attention: 0xFF9F78
        }
    }

    var color: Color {
        let light = UIColor(hex: lightHex), dark = UIColor(hex: darkHex)
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: 1)
    }
}

private struct LinkBridgeBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Color(uiColor: .systemBackground)
            .overlay(Accent.action.color.opacity(colorScheme == .dark ? 0.10 : 0.06))
            .ignoresSafeArea()
    }
}

extension View {
    /// System background tinted with cobalt (6% light, 10% dark), behind content.
    func linkBridgeBackdrop() -> some View {
        background { LinkBridgeBackdrop() }
    }
}
