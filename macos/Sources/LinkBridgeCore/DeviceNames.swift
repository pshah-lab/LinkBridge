import Foundation

/// Device names come from unauthenticated requests and Bonjour, so they are cleaned before
/// they reach any dialog, notification or store: one line of at most 40 visible characters.
public enum DeviceNames {
    public static let maxLength = 40
    public static let fallback = "Unknown device"

    public static func sanitize(_ name: String) -> String {
        var cleaned = ""
        var pendingSpace = false
        for scalar in name.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator, .spaceSeparator:
                // Newlines, tabs, bidi overrides and zero-width characters: at most one space.
                pendingSpace = !cleaned.isEmpty
            default:
                if pendingSpace { cleaned.unicodeScalars.append(" ") }
                pendingSpace = false
                cleaned.unicodeScalars.append(scalar)
            }
        }
        let capped = String(cleaned.prefix(maxLength)).trimmingCharacters(in: .whitespaces)
        return capped.isEmpty ? fallback : capped
    }
}
