# Protocol Version Check and Update Notice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show "Update LinkBridge on <device>" when two devices are incompatible, and show a "new version available" banner in website builds of the Mac and Android apps.

**Architecture:** A pure compatibility function per platform (Swift `LinkBridgeCore`, shared with iOS, plus Kotlin), fed by new additive `protocol`/`minProtocol` fields in TXT, `/device`, `/pair/request` and upload headers, with a 426 `update_required` reply. A separate pure update-check model compares `latest.json` builds, gated by a build flag that only website builds set.

**Tech Stack:** Swift 6 / SwiftUI (SPM, `swift run --build-system native LinkBridgeCoreChecks`), Kotlin / Compose (`./gradlew :app:testDebugUnitTest`).

**Spec:** `docs/superpowers/specs/2026-11-22-version-check-and-update-notice-design.md`

## Global Constraints

- Current protocol `2`, minimum supported protocol `2`, on every platform.
- A missing `minProtocol` means 2. A missing `protocol` with a `tlsPort` means 2. No `tlsPort` and no device ID means protocol 1, min 1. JSON without the new fields: protocol 2, min 2 if `pairingVersion >= 3`, else protocol 1.
- The existing `protocolVersion` JSON field and `426 tls_required` stay unchanged.
- Copy: `Update LinkBridge on <peer name>` · `Update LinkBridge on this Mac` (macOS) · `Update LinkBridge on this device` (Android, iOS).
- 426 body: `{"error":"update_required","update":"sender"|"receiver","protocol":2,"minProtocol":2}`.
- Update source: `https://linkbridge.pratham.si/latest.json`. Open only `https` URLs on host `linkbridge.pratham.si`, otherwise `https://linkbridge.pratham.si/`.
- Check at launch and every 24 h, 10 s timeout, silent on failure, no IDs sent.
- Banner: `LinkBridge <version> is available.` with `Download` and `Not now`. "Not now" hides it until a higher build appears.
- The checker exists only when the Mac Info.plist key `LBUpdateCheck` is true (written by `scripts/package-mac-app.sh` when `WEBSITE_BUILD=1`) or `BuildConfig.UPDATE_CHECK` is true (Gradle property `linkbridge.websiteBuild=true`, passed only by `scripts/build-android-release.sh`). Both are off by default.
- Never download or install anything.

## Review Focus

1. **A sender writing a large body while the receiver rejects early with 426:** the sender may see a broken pipe instead of the reply. The pre-send check from TXT numbers must catch every case the 426 would, so the 426 is only a backstop. On a write error, still try to read a response if any bytes arrived.
2. **A 0.2.1 peer (no new fields anywhere):** stays fully compatible, sendable and pairable, with no "update" text.
3. **A malformed `latest.json`** (missing platform key, non-integer build, wrong types or an HTML error page): no banner, no crash.
4. **The update toggle turned off mid-session:** the banner hides at once and no further requests are made.
5. **A garbage TXT value** (`protocol=abc`, `minProtocol=` empty) is treated as missing, not as 0.

---

### Task 1: Swift compatibility model and discovery parsing

**Files:**
- Create: `macos/Sources/LinkBridgeCore/ProtocolCompat.swift`
- Modify: `macos/Sources/LinkBridgeCore/PeerResolver.swift` (`ResolvedPeer` + its TXT parse), `macos/Sources/LinkBridgeCore/PeerBrowser.swift:85-100`, `macos/Sources/LinkBridgeCore/ControlServer.swift` (TXT at ~254: add `minProtocol`; add constant)
- Test: `macos/Sources/LinkBridgeCoreChecks/ProtocolCompatChecks.swift` (called from `main.swift`)

**Interfaces — Produces:**
- `ControlServer.minProtocolVersion = 2`
- `public struct ProtocolLevel: Equatable { public let current: Int; public let minimum: Int; public static let local: ProtocolLevel }`
- `public static func ProtocolLevel.fromTXT(protocol: String?, minProtocol: String?, hasTLSPort: Bool, hasDeviceId: Bool) -> ProtocolLevel`
- `public static func ProtocolLevel.fromJSON(protocol: Int?, minProtocol: Int?, pairingVersion: Int?) -> ProtocolLevel`
- `public enum Compatibility: Equatable { case ok, peerTooOld, localTooOld }`
- `public static func ProtocolCompat.check(local: ProtocolLevel, peer: ProtocolLevel) -> Compatibility`
- `public static func ProtocolCompat.message(_ c: Compatibility, peerName: String, localName: String) -> String?` (`localName` is "this Mac" on macOS and "this device" on iOS, via `#if os(macOS)` at the call sites)
- `ResolvedPeer.level: ProtocolLevel` (new init param `level:` with default `.local`, so existing call sites compile)

- [ ] **Step 1: Write the failing checks:** `check(ProtocolCompat.check(local: .init(2,2), peer: .init(1,1)) == .peerTooOld)`; `peer .init(3,3)` → `.localTooOld`; `peer .init(3,2)` → `.ok`; `fromTXT("2", nil, true, false) == .init(2,2)`; `fromTXT(nil, nil, false, false) == .init(1,1)`; `fromTXT("abc", "", true, false) == .init(2,2)`; `fromJSON(nil, nil, 3) == .init(2,2)`; `fromJSON(nil, nil, nil) == .init(1,1)`; `message(.peerTooOld, "Pixel", "this Mac") == "Update LinkBridge on Pixel"`; `message(.localTooOld, …) == "Update LinkBridge on this Mac"`; `message(.ok, …) == nil`.
- [ ] **Step 2:** Run `cd macos && swift run --build-system native LinkBridgeCoreChecks`. Expected: build fails (types missing).
- [ ] **Step 3:** Implement. Parse `protocol`/`minProtocol` in both TXT readers into `ResolvedPeer.level`. Advertise `minProtocol=2`.
- [ ] **Step 4:** Run the checks. Expected: all pass, ending in the existing success line.
- [ ] **Step 5:** Commit `Add protocol compatibility model (Swift)`.

### Task 2: Swift device list, pairing and transfer enforcement

**Files:**
- Modify: `macos/Sources/LinkBridgePresentation/DeviceListItem.swift:62-100`, `macos/Sources/LinkBridgeCore/ControlServer.swift` (`/device` reply, `handlePairRequest` ~474, `handleUpload` ~608), `macos/Sources/LinkBridgeCore/DeviceIdentity.swift` (`PublicDeviceIdentity`: optional `protocol`, `minProtocol`), `macos/Sources/LinkBridgeCore/PairingClient.swift`, `macos/Sources/LinkBridgeCore/FileSender.swift`, `macos/Sources/LinkBridge/Views/SendSurface.swift:87`, `macos/Sources/LinkBridge/NotificationController.swift` (incoming-rejected notice)
- Test: `macos/Sources/LinkBridgeCoreChecks/PresentationChecks.swift`, `main.swift` (server checks), `ios/LinkBridgeIOSTests/DeviceListItemTests.swift`

**Interfaces — Consumes:** Task 1. **Produces:**
- `DeviceListItem.merge(nearby:trusted:lastSeen:now:localName:)` (the new `localName` param defaults to "this Mac"). An incompatible nearby peer gets subtitle `ProtocolCompat.message(...)`, `isSendable = false`, `canPair = false`. This replaces the `deviceId == nil && tlsPort == nil` branch.
- `PairingClientError.updateRequired(String)` (the message text). `PairingClient.begin()` checks `/device`'s level first, and maps a 426 `update_required` on `/pair/request` to it.
- `SenderError.updateRequired(String)`. `FileSender` sends `X-LinkBridge-Protocol: 2` and `X-LinkBridge-Min-Protocol: 2`, and maps a 426 with `error == "update_required"` by `update` (`"sender"` → this side, `"receiver"` → the peer). A 426 without that body stays `.peerNeedsUpdate`.
- Server: `/device` and the `/pair/request` reply include `"protocol":2,"minProtocol":2`. An incompatible `/pair/request` or upload (missing headers mean 2/2) gets the 426 body. Uploads are rejected before the approval prompt. The upload handler calls a new `ControlServer.onIncompatibleSender: ((String /*deviceName*/) -> Void)?`, rate-limited in the server to once per sender per hour; the Mac app posts a notification with `Update LinkBridge on <name>`.

- [ ] **Step 1: Write the failing checks:**
  - Presentation: a nearby peer with level (1,1) and a TLS port (paired or not) → subtitle "Update LinkBridge on <name>", not sendable, not pairable. Level (3,3) → "Update LinkBridge on this Mac". Level (2,2), paired with TLS → "Paired · Nearby", sendable (0.2.1 regression).
  - Server (reuse the `pairAsPhone`-style HTTP helpers in `main.swift`): `/pair/request` with `"protocol":1,"minProtocol":1` → 426 with `update == "sender"`; with `"minProtocol":3,"protocol":3` → 426 `update == "receiver"`; without the fields but with `pairingVersion: 3` → 200 pending. `GET /device` JSON has `protocol == 2` and `minProtocol == 2`. An upload with `X-LinkBridge-Protocol: 1` → 426 `update_required` and no approval prompt queued.
- [ ] **Step 2:** Run the checks. Expected: the new checks fail.
- [ ] **Step 3:** Implement as above. Update the iOS `DeviceListItemTests` expectations for the replaced branch, using "this device".
- [ ] **Step 4:** Run the checks. Expected: all pass. Then run `xcodebuild test` for the iOS scheme if it's available locally, and note it if it's skipped.
- [ ] **Step 5:** Commit `Enforce protocol compatibility on Mac and iOS`.

### Task 3: Android compatibility model, discovery, device list, pairing and transfers

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/ProtocolCompat.kt`
- Modify: `DiscoveredPeer.kt` (add `level: ProtocolLevel = ProtocolLevel.LOCAL`), `DiscoveryService.kt:93,209` (advertise `minProtocol`, parse both), `presentation/DeviceListItem.kt:47-70`, `ControlClient.kt` (pair request adds `protocol`/`minProtocol`; checks the `fetchDevice` level; upload headers; 426 mapping at ~208, and on `IOException` try to read any response first), `ReceiveServer.kt:118-140` (reject incompatible senders with 426 before the prompt, plus a once-per-hour notice through the existing notification path)
- Test: `android/app/src/test/java/com/linkbridge/android/ProtocolCompatTest.kt`, `presentation/DeviceListItemTest.kt`

**Interfaces — Produces (mirror Task 1):**
- `data class ProtocolLevel(val current: Int, val minimum: Int)` with `companion { val LOCAL = ProtocolLevel(2, 2); fun fromTxt(protocol: String?, minProtocol: String?, hasTlsPort: Boolean, hasDeviceId: Boolean): ProtocolLevel; fun fromJson(protocol: Int?, minProtocol: Int?, pairingVersion: Int?): ProtocolLevel }`
- `enum class Compatibility { OK, PEER_TOO_OLD, LOCAL_TOO_OLD }`, `object ProtocolCompat { fun check(local: ProtocolLevel, peer: ProtocolLevel): Compatibility; fun message(c: Compatibility, peerName: String): String? }` (local text is "this device")
- `class UpdateRequiredException(message: String) : IllegalStateException(message)`, thrown by `ControlClient` pairing and upload, so its message reaches the existing error UI unchanged.

- [ ] **Step 1: Write failing tests** with the same cases as the Task 1 Step 1 checks, plus `DeviceListItemTest`: a peer with level (1,1) and a TLS port → "Update LinkBridge on <name>", not sendable/pairable; (3,3) → "Update LinkBridge on this device"; (2,2) paired → sendable. Keep the existing "Needs a LinkBridge update" test for no-TLS peers only if that branch survives; otherwise change it to the new copy.
- [ ] **Step 2:** Run `cd android && ./gradlew :app:testDebugUnitTest`. Expected: compile failure or failing tests.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `Enforce protocol compatibility on Android`.

### Task 4: Protocol doc

- [ ] Add a `## Versions` section to `docs/protocol.md` after Discovery, covering the numbers, the rule, inference, new fields and headers, the 426 body, and where each message shows. Add `minProtocol=2` to the TXT block and `X-LinkBridge-Protocol`/`X-LinkBridge-Min-Protocol` to the upload headers. Commit `Document protocol versions`.

### Task 5: Update check, Mac

**Files:**
- Create: `macos/Sources/LinkBridgePresentation/UpdateCheck.swift` (pure logic), `macos/Sources/LinkBridge/UpdateChecker.swift` (URLSession, timer, UserDefaults)
- Modify: `scripts/package-mac-app.sh:60-87` (plist key under `WEBSITE_BUILD=1`), `macos/Sources/LinkBridge/Views/MainWindow.swift` (banner), `Views/MenuBarContent.swift` ("Update available…"), `Views/SettingsView.swift` ("Check for updates" toggle), `MacModel.swift` (owns the checker)
- Test: `macos/Sources/LinkBridgeCoreChecks/UpdateCheckChecks.swift`

**Interfaces — Produces:**
- `public struct LatestRelease: Equatable { public let version: String; public let build: Int; public let url: URL; public let notes: String }`
- `public enum UpdateCheck { static func parse(_ data: Data, platform: String /*"mac"|"android"*/) -> LatestRelease?; static func safeURL(_ s: String?) -> URL; static func shouldShow(latest: LatestRelease?, currentBuild: Int, dismissedBuild: Int?) -> Bool; static func isDue(lastCheck: Date?, now: Date) -> Bool }` (Android uses the `versionCode` key for `build`). Notes are cut to 120 characters on one line.
- `UpdateChecker` (MainActor, `ObservableObject`): `@Published var available: LatestRelease?`, `func start()`, `func dismiss()`, `var enabled: Bool` (UserDefaults `updateCheckEnabled`, default true). It only exists when `Bundle.main.object(forInfoDictionaryKey: "LBUpdateCheck") as? Bool == true`. The current build comes from `CFBundleVersion`.

- [ ] **Step 1: Write failing checks:** a valid sample JSON → `LatestRelease(version "0.3.0", build 4, …)`. Missing `mac` key, `build: "4"` and HTML input → nil. `safeURL("https://linkbridge.pratham.si/download/mac")` keeps the URL; `"http://linkbridge.pratham.si/x"`, `"https://evil.example/x"` and `nil` → `https://linkbridge.pratham.si/`. `shouldShow(build 4, current 3, dismissed nil)` true; `dismissed 4` false; `latest build 5, dismissed 4` true; `current 4` false. `isDue(nil)` true; 23 h → false; 25 h → true.
- [ ] **Step 2:** Run the checks. Expected: fail.
- [ ] **Step 3:** Implement the logic, then the checker (10 s timeout, generic User-Agent `LinkBridge`, ephemeral session, no cookies), the banner (`LinkBridge <version> is available.` with Download/Not now, opening the URL with `NSWorkspace`), the menu item and the toggle (turning it off clears `available` and stops the timer). Add the `LBUpdateCheck` plist key under `WEBSITE_BUILD=1`.
- [ ] **Step 4:** Run the checks and `swift build --build-system native`. Run `WEBSITE_BUILD=1 ./scripts/package-mac-app.sh` and confirm `plutil -p dist/LinkBridge.app/Contents/Info.plist | grep LBUpdateCheck` prints `=> true`. Run it without the flag and confirm grep prints nothing.
- [ ] **Step 5:** Commit `Add website update notice (Mac)`.

### Task 6: Update check, Android

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/UpdateCheck.kt` (pure), `UpdateChecker.kt` (HttpURLConnection on IO, SharedPreferences)
- Modify: `android/app/build.gradle.kts` (`buildConfigField("boolean", "UPDATE_CHECK", (project.findProperty("linkbridge.websiteBuild") == "true").toString())`), `scripts/build-android-release.sh:13` (pass `-Plinkbridge.websiteBuild=true`), `MainViewModel.kt`/`UiState.kt` (expose `availableUpdate`), `ui/devices/DevicesScreen.kt` (banner), `ui/settings/SettingsScreen.kt` (toggle, shown only when `BuildConfig.UPDATE_CHECK`)
- Test: `android/app/src/test/java/com/linkbridge/android/UpdateCheckTest.kt`

**Interfaces — Produces:** `data class LatestRelease(val version: String, val build: Int, val url: String, val notes: String)`, `object UpdateCheck { fun parse(json: String, platform: String): LatestRelease?; fun safeUrl(s: String?): String; fun shouldShow(latest: LatestRelease?, currentBuild: Int, dismissedBuild: Int?): Boolean; fun isDue(lastCheckMillis: Long?, nowMillis: Long): Boolean }`. `currentBuild` is `BuildConfig.VERSION_CODE`. "Download" opens an `ACTION_VIEW` intent for the URL.

- [ ] **Step 1: Write failing tests** with the same cases as Task 5 Step 1, using the `android` key and `versionCode`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: fail.
- [ ] **Step 3:** Implement. `UpdateChecker` is never constructed when `!BuildConfig.UPDATE_CHECK`.
- [ ] **Step 4:** Run the tests. Expected: BUILD SUCCESSFUL. Run `./gradlew :app:assembleDebug -Plinkbridge.websiteBuild=true` and confirm it compiles. Don't build a release (it needs the user's keystore password).
- [ ] **Step 5:** Commit `Add website update notice (Android)`.

### Task 7: Hand-off and PR

- [ ] Write `website-latest-json.md` in the shared notes folder with the exact `latest.json` sample from the spec, the privacy-page line, and the note that the Android release must now be built via `build-android-release.sh` and the Mac one with `WEBSITE_BUILD=1`.
- [ ] Run all the checks and tests once more, push the branch, and open a PR against `main`. Don't merge.
