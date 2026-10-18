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

    /// Label colour on a fill of this accent: white only where it reaches 4.5:1, otherwise ink.
    var onLightHex: UInt32 { self == .action ? 0xFFFFFF : Self.ink }
    var onDarkHex: UInt32 { Self.ink }

    var color: Color { Self.dynamic(light: lightHex, dark: darkHex) }
    var onColor: Color { Self.dynamic(light: onLightHex, dark: onDarkHex) }

    private static let ink: UInt32 = 0x10151F

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        let light = UIColor(hex: light), dark = UIColor(hex: dark)
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

/// WCAG 2 contrast ratio between two sRGB colours given as 0xRRGGBB.
func contrastRatio(_ a: UInt32, _ b: UInt32) -> Double {
    func luminance(_ hex: UInt32) -> Double {
        func channel(_ value: UInt32) -> Double {
            let c = Double(value & 0xFF) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(hex >> 16) + 0.7152 * channel(hex >> 8) + 0.0722 * channel(hex)
    }
    let (la, lb) = (luminance(a), luminance(b))
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
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
