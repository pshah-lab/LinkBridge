import Foundation
import LinkBridgePresentation

func runUpdateCheckChecks() {
    section("Update check: latest.json parsing") {
        let sample = Data(#"""
        {"mac": {"version": "0.3.0", "build": 4, "url": "https://linkbridge.pratham.si/download/mac", "notes": "Faster pairing"},
         "android": {"version": "0.3.0", "versionCode": 4, "url": "https://linkbridge.pratham.si/download/android", "notes": ""}}
        """#.utf8)
        let mac = UpdateCheck.parse(sample, platform: "mac")
        check(mac == LatestRelease(version: "0.3.0", build: 4, url: URL(string: "https://linkbridge.pratham.si/download/mac")!, notes: "Faster pairing"), "mac entry")
        check(UpdateCheck.parse(sample, platform: "android")?.build == 4, "android entry uses versionCode")
        check(UpdateCheck.parse(Data(#"{"android": {"version": "0.3.0", "versionCode": 4}}"#.utf8), platform: "mac") == nil, "missing platform")
        check(UpdateCheck.parse(Data(#"{"mac": {"version": "0.3.0", "build": "4"}}"#.utf8), platform: "mac") == nil, "build must be a number")
        check(UpdateCheck.parse(Data("<html>Not found</html>".utf8), platform: "mac") == nil, "HTML error page")
        let long = UpdateCheck.parse(Data(#"{"mac": {"version": "1", "build": 9, "notes": "\#(String(repeating: "a", count: 300))\nsecond line"}}"#.utf8), platform: "mac")
        check(long?.notes.count == 120 && long?.notes.contains("\n") == false, "notes cut to one short line")
        check(long?.url == URL(string: "https://linkbridge.pratham.si/")!, "missing url falls back to the site")
    }

    section("Update check: only the LinkBridge site is opened") {
        check(UpdateCheck.safeURL("https://linkbridge.pratham.si/download/mac") == URL(string: "https://linkbridge.pratham.si/download/mac")!, "site URL kept")
        let home = URL(string: "https://linkbridge.pratham.si/")!
        check(UpdateCheck.safeURL("http://linkbridge.pratham.si/x") == home, "http refused")
        check(UpdateCheck.safeURL("https://evil.example/x") == home, "other host refused")
        check(UpdateCheck.safeURL("https://linkbridge.pratham.si.evil.example/x") == home, "lookalike host refused")
        check(UpdateCheck.safeURL(nil) == home, "missing URL")
    }

    section("Update check: when to show and when to check") {
        let r4 = LatestRelease(version: "0.3.0", build: 4, url: URL(string: "https://linkbridge.pratham.si/")!, notes: "")
        let r5 = LatestRelease(version: "0.3.1", build: 5, url: r4.url, notes: "")
        check(UpdateCheck.shouldShow(latest: r4, currentBuild: 3, dismissedBuild: nil), "newer build shows")
        check(!UpdateCheck.shouldShow(latest: r4, currentBuild: 3, dismissedBuild: 4), "dismissed build hides")
        check(UpdateCheck.shouldShow(latest: r5, currentBuild: 3, dismissedBuild: 4), "an even newer build shows again")
        check(!UpdateCheck.shouldShow(latest: r4, currentBuild: 4, dismissedBuild: nil), "same build hides")
        check(!UpdateCheck.shouldShow(latest: nil, currentBuild: 3, dismissedBuild: nil), "nothing known hides")
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        check(UpdateCheck.isDue(lastCheck: nil, now: now), "never checked")
        check(!UpdateCheck.isDue(lastCheck: now.addingTimeInterval(-23 * 3600), now: now), "checked 23 h ago")
        check(UpdateCheck.isDue(lastCheck: now.addingTimeInterval(-25 * 3600), now: now), "checked 25 h ago")
        check(UpdateCheck.isDue(lastCheck: now.addingTimeInterval(3600), now: now), "clock moved back")
    }
}
