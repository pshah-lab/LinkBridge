import AppKit
import LinkBridgePresentation
import SwiftUI

extension Accent {
    var color: Color { Self.dynamic(light: lightHex, dark: darkHex) }
    var onColor: Color { Self.dynamic(light: onLightHex, dark: onDarkHex) }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        let light = NSColor(hex: light), dark = NSColor(hex: dark)
        return Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        })
    }
}

private extension NSColor {
    convenience init(hex: UInt32) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: 1)
    }
}

private struct LinkBridgeBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Color(nsColor: .windowBackgroundColor)
            .overlay(Accent.action.color.opacity(colorScheme == .dark ? 0.10 : 0.06))
            .ignoresSafeArea()
    }
}

extension View {
    /// Window background tinted with cobalt (6% light, 10% dark), behind content.
    func linkBridgeBackdrop() -> some View {
        background { LinkBridgeBackdrop() }
    }
}
