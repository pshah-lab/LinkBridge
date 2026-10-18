import Foundation

/// Accent colours from the shared LinkBridge design language. Each app adds its own
/// `color` / `onColor` bridge (UIColor on iOS, NSColor on macOS).
public enum Accent: CaseIterable {
    case action, signal, attention

    public var lightHex: UInt32 {
        switch self {
        case .action: 0x3E62E8
        case .signal: 0x35C98B
        case .attention: 0xF08A5D
        }
    }

    public var darkHex: UInt32 {
        switch self {
        case .action: 0x7C95FF
        case .signal: 0x4ADBA0
        case .attention: 0xFF9F78
        }
    }

    /// Label colour on a fill of this accent: white only where it reaches 4.5:1, otherwise ink.
    public var onLightHex: UInt32 { self == .action ? 0xFFFFFF : Self.ink }
    public var onDarkHex: UInt32 { Self.ink }

    private static let ink: UInt32 = 0x10151F
}

/// WCAG 2 contrast ratio between two sRGB colours given as 0xRRGGBB.
public func contrastRatio(_ a: UInt32, _ b: UInt32) -> Double {
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
