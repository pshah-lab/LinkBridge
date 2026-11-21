import AppKit
import Foundation
import LinkBridgePresentation

/// Website builds only: checks latest.json once a day and offers a link to the download page.
/// Never downloads or installs anything. Absent from builds without `LBUpdateCheck` in Info.plist,
/// so App Store builds have no checker and no setting.
@MainActor @Observable
final class UpdateChecker {
    static let enabledKey = "updateCheckEnabled"
    private static let lastCheckKey = "updateLastCheck"
    private static let dismissedKey = "updateDismissedBuild"
    /// The last good latest.json, so a relaunch within the day still shows the banner.
    private static let latestKey = "updateLatestFeed"

    /// Nil unless this is a website build.
    static func makeIfWebsiteBuild() -> UpdateChecker? {
        Bundle.main.object(forInfoDictionaryKey: "LBUpdateCheck") as? Bool == true ? UpdateChecker() : nil
    }

    /// The newer release to offer, or nil.
    private(set) var available: LatestRelease?

    var enabled: Bool {
        didSet {
            defaults.set(enabled, forKey: Self.enabledKey)
            if enabled { start() } else { stop() }
        }
    }

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var latest: LatestRelease?
    @ObservationIgnored private let currentBuild = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    @ObservationIgnored private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 10
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.httpAdditionalHeaders = ["User-Agent": "LinkBridge"]
        return URLSession(configuration: config)
    }()

    private init() {
        enabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true
        latest = (UserDefaults.standard.data(forKey: Self.latestKey)).flatMap { UpdateCheck.parse($0, platform: "mac") }
    }

    func start() {
        guard enabled, timer == nil else { return }
        checkIfDue()
        // Hourly tick; the 24 h gate decides whether a request is actually made.
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkIfDue() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        available = nil
    }

    /// "Not now": hide until a higher build appears.
    func dismiss() {
        guard let available else { return }
        defaults.set(available.build, forKey: Self.dismissedKey)
        self.available = nil
    }

    func openDownload() {
        NSWorkspace.shared.open(available?.url ?? UpdateCheck.siteURL)
    }

    private func checkIfDue() {
        guard enabled, UpdateCheck.isDue(lastCheck: defaults.object(forKey: Self.lastCheckKey) as? Date, now: Date()) else {
            refresh()
            return
        }
        Task { [weak self, session] in
            guard let (data, response) = try? await session.data(from: UpdateCheck.feedURL),
                  (response as? HTTPURLResponse)?.statusCode == 200 else { return }
            await MainActor.run {
                guard let self, self.enabled else { return }
                self.defaults.set(Date(), forKey: Self.lastCheckKey)
                if let release = UpdateCheck.parse(data, platform: "mac") {
                    self.latest = release
                    self.defaults.set(data.prefix(16 * 1024), forKey: Self.latestKey)
                }
                self.refresh()
            }
        }
    }

    private func refresh() {
        let dismissed = defaults.object(forKey: Self.dismissedKey) as? Int
        available = enabled && UpdateCheck.shouldShow(latest: latest, currentBuild: currentBuild, dismissedBuild: dismissed) ? latest : nil
    }
}
