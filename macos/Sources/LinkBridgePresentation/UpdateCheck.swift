import Foundation

/// The newest website build, from https://linkbridge.pratham.si/latest.json.
public struct LatestRelease: Equatable, Sendable {
    public let version: String
    public let build: Int
    public let url: URL
    public let notes: String

    public init(version: String, build: Int, url: URL, notes: String) {
        self.version = version
        self.build = build
        self.url = url
        self.notes = notes
    }
}

/// Pure logic for the "new version available" banner in website builds. It only ever links to the
/// site; it never downloads or installs anything.
public enum UpdateCheck {
    public static let feedURL = URL(string: "https://linkbridge.pratham.si/latest.json")!
    public static let siteURL = URL(string: "https://linkbridge.pratham.si/")!
    public static let siteHost = "linkbridge.pratham.si"
    public static let interval: TimeInterval = 24 * 3600
    static let maxNotes = 120

    /// `platform` is "mac" (build from `build`) or "android" (from `versionCode`). Nil for anything malformed.
    public static func parse(_ data: Data, platform: String) -> LatestRelease? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let entry = root[platform] as? [String: Any],
              let version = entry["version"] as? String, !version.isEmpty,
              let buildNumber = entry[platform == "android" ? "versionCode" : "build"] as? NSNumber,
              CFGetTypeID(buildNumber) == CFNumberGetTypeID() else { return nil }
        let firstLine = ((entry["notes"] as? String) ?? "").components(separatedBy: .newlines).first ?? ""
        return LatestRelease(version: String(version.prefix(20)), build: buildNumber.intValue,
                             url: safeURL(entry["url"] as? String), notes: String(firstLine.prefix(maxNotes)))
    }

    /// Only `https` links on the LinkBridge site are opened; anything else opens the site's home page.
    public static func safeURL(_ string: String?) -> URL {
        guard let string, let components = URLComponents(string: string),
              components.scheme == "https", components.host?.lowercased() == siteHost,
              components.user == nil, components.password == nil, components.port == nil,
              let url = components.url else { return siteURL }
        return url
    }

    public static func shouldShow(latest: LatestRelease?, currentBuild: Int, dismissedBuild: Int?) -> Bool {
        guard let latest, latest.build > currentBuild else { return false }
        return latest.build > (dismissedBuild ?? Int.min)
    }

    /// Due once a day. A last check in the future (clock moved back) counts as due.
    public static func isDue(lastCheck: Date?, now: Date) -> Bool {
        guard let lastCheck else { return true }
        let elapsed = now.timeIntervalSince(lastCheck)
        return elapsed < 0 || elapsed >= interval
    }
}
