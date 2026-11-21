# Offline Direct Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** File transfer with no Wi-Fi between every pair (iPhone↔Mac over AWDL; Android↔iPhone and Android↔Mac over an Android local-only hotspot), plus Android↔iPhone pairing/transfer and Mac→iPhone, which don't exist today.

**Architecture:** Apple devices enable Network.framework peer-to-peer and reach each other by Bonjour service. Android hosts a LocalOnlyHotspot, shows a QR link, and runs a small plain "offline hello" endpoint so both sides learn each other's discovery record. The iPhone gains the shared `ControlServer` (pairing + TLS receive). Pairing and TLS-PSK are unchanged.

**Tech Stack:** Swift 6 / Network.framework / NetworkExtension (`NEHotspotConfiguration`) / SwiftUI; Kotlin / Compose / `WifiManager.startLocalOnlyHotspot`; ZXing core for QR encoding on Android; AVFoundation QR scanning on iOS.

**Spec:** `docs/superpowers/specs/2026-11-15-offline-direct-link-design.md`

## Global Constraints

- Offline mode on Android requires API 33+ (runtime gate); app `minSdk` stays 31, `targetSdk` stays 36.
- Android permissions: `CHANGE_WIFI_STATE`; `NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"`. Never request any location permission.
- Hotspot idle stop: 10 minutes; idle = no active transfer, no pending approval, no pairing in progress.
- Offline hello: plain TCP, Android-only listener, bound to the hotspot IPv4 address, port **49154**, `POST /offline/hello` only, body ≤ 4 KB, ≤ 20 requests/minute, private-source IPv4 only (10/8, 172.16/12, 192.168/16), never pairs or accepts files.
- The QR link carries no pairing secrets; it's scanned in-app only. Never register its scheme as a system URL handler.
- No `joinOnce` on iOS; remove the hotspot configuration when the session ends.
- No new Mac entitlements. iOS adds the Hotspot Configuration entitlement, `NSCameraUsageDescription`, `UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace`.
- Protocol messages and version are unchanged; TXT record is unchanged.
- iPhone server runs only in the foreground.
- Timeouts: AWDL connect 10 s; Bonjour wait before hello/direct fallback 3 s; "no peers" card after 5 s.
- Build/test commands (from project notes):
  - Mac: `cd macos && swift run --build-system native LinkBridgeCoreChecks`
  - Android: `cd android && ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest`
  - iOS: `xcodebuild test -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=`
- Never trigger or type into Keychain password prompts; Mac server checks stub credentials in memory and set `CFFIXED_USER_HOME` to a temp dir (existing harness pattern).

## Review Focus

1. **Hotspot stops mid-transfer:** sender and receiver both report a failure and the partial file is removed; nothing hangs. → test in Task 9.
2. **QR link with a valid shape but hostile values** (public IP, port 0, 300-char SSID, extra fields): rejected with "This isn't a LinkBridge code", with no join attempt. → shared vectors, Tasks 3 and 7.
3. **iPhone backgrounded while a file is arriving:** listeners stop, the partial file is deleted, and the sender sees a failure (not a hang). → test in Task 4.
4. **Hello flood or garbage from another hotspot client:** the 21st request in a minute is refused, oversized/invalid bodies are refused, and the listener stays up for valid joiners. → tests in Task 10.
5. **Same device seen via Bonjour *and* hello:** one device row, not two (dedupe on matched deviceId, else rid). → tests in Tasks 10 and 12.

---

### Task 0: Device spike (throwaway, no product code)

Answers the spec's three open risks before building on them. Use a scratch branch `spike/offline-link`; don't merge it.

- [ ] **Step 1:** Android 13+ test app button: `startLocalOnlyHotspot`, log `SoftApConfiguration` SSID/passphrase and every `NetworkInterface` IPv4 (name + address). Record which interface carries the hotspot address on the user's OnePlus.
- [ ] **Step 2:** Join from iPhone and Mac. From each, run `dns-sd -B _linkbridge._tcp` (Mac) / an `NWBrowser` (iPhone) while Android registers NSD. Record: does mDNS work across the hotspot (yes/no per direction)?
- [ ] **Step 3:** Record whether Android's own Wi-Fi client connection dropped when the hotspot started.
- [ ] **Step 4:** iPhone↔Mac with Wi-Fi off on both: a listener and browser with `includePeerToPeer = true`; connect to `.service`; read `connection.currentPath?.remoteEndpoint` and the interface; open a second TCP connection to that host on another port with `requiredInterface` set. Record success and the interface name (expect `awdl0`).
- [ ] **Step 5:** Mac sandbox: read `SCDynamicStoreCopyValue(nil, "State:/Network/Global/IPv4")` key `Router` from a sandboxed build while joined to the hotspot. Record whether it returns the gateway.
- [ ] **Step 6:** Write the results into the spec under "Open risks" (Results lines) and commit to the main branch. **Decision rules:**
  - If Step 4 fails, Task 2 uses the fallback: advertise the TLS listener as its own Bonjour service `_linkbridge-tls._tcp`, add it to `NSBonjourServices`, and connect by service.
  - If Step 5 fails, Task 13 uses manual address entry directly after Bonjour.

---

### Task 1: `ControlServer` receive directory and Mac-only assumptions

**Files:**
- Modify: `macos/Sources/LinkBridgeCore/ControlServer.swift` (init at ~69–108; the Downloads uses at ~681 and ~1015)
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift`

**Interfaces:**
- Produces: `ControlServer.init(..., receiveDirectory: URL = ControlServer.defaultReceiveDirectory, ...)`; `public static var defaultReceiveDirectory: URL` (today's `~/Downloads/LinkBridge` value, unchanged on Mac). Both inits (designated + convenience) take it.

- [ ] **Step 1: Failing check** `receivesIntoInjectedDirectory`: start a server with `receiveDirectory` = a temp dir, send a file with the existing harness client, then `check(FileManager.default.fileExists(atPath: tmp/<name>))` and `check(!exists(~/Downloads/LinkBridge/<name>))` (under `CFFIXED_USER_HOME`).
- [ ] **Step 2:** Run the Mac checks command. Expected: the new check fails (it doesn't compile or the file lands in Downloads).
- [ ] **Step 3:** Thread `receiveDirectory` through to both Downloads sites. While in the file, list any other macOS-only API use (e.g. `NSWorkspace`, AppKit imports) and move it behind `#if os(macOS)`, so the target compiles for iOS.
- [ ] **Step 4:** Run the Mac checks. Expected: all pass. Build the iOS scheme: `xcodebuild build ...` (same flags). Expected: succeeds.
- [ ] **Step 5:** Commit `feat(core): injectable receive directory for ControlServer`.

---

### Task 2: Apple peer-to-peer (AWDL)

**Files:**
- Modify: `macos/Sources/LinkBridgeCore/SecureTransport.swift`, `ControlServer.swift` (plain listener parameters), `PeerBrowser.swift`, `PeerResolver.swift` (`ResolvedPeer`), `FileSender.swift`, `PairingClient.swift`
- Create: `macos/Sources/LinkBridgeCore/DirectRoute.swift`
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift`

**Interfaces:**
- Produces:
  - `ResolvedPeer.service: NWEndpoint?` (the `.service` endpoint; nil for peers from hello/QR).
  - `ResolvedPeer.isDirect: Bool` (true when the resolved path's interface is AWDL).
  - `enum DirectRoute { static func resolve(_ service: NWEndpoint, timeout: TimeInterval = 10) throws -> (host: NWEndpoint.Host, interface: NWInterface?) }`.
  - `FileSender.send(filePath:peer:secret:onProgress:)`: if `peer.service != nil`, resolve with `DirectRoute`, then connect TLS to `(host, tlsPort)` with `requiredInterface = interface`; otherwise use the existing host:port path. The existing `send(filePath:endpoint:...)` stays.
  - `PairingClient(peer: ResolvedPeer)`: same rule for the plain port. URLSession can't pin an interface, so peer-to-peer pairing uses an `NWConnection` HTTP/1.1 request path; Wi-Fi peers keep URLSession.
  - `static let peerToPeer = true` applied to every `NWParameters` built in `SecureTransport`, `ControlServer` (plain listener), `PeerBrowser`.
- Consumes: Task 0 Step 4 result (fallback variant if it failed).

- [ ] **Step 1: Failing checks:**
  - `parametersEnablePeerToPeer`: `check(SecureTransport.serverParameters(keys: k).includePeerToPeer)`, `check(SecureTransport.clientParameters(identity: i, psk: p).includePeerToPeer)`, plus the plain-listener and browser parameters (expose them as `static func` so they're testable).
  - `sendsByServiceEndpoint`: start a local `ControlServer` with a test identity; browse until it's found; build a `ResolvedPeer` with `service` set; `FileSender.send(filePath:peer:secret:)` succeeds and the file arrives in the Task 1 temp dir.
- [ ] **Step 2:** Run the Mac checks. Expected: both new checks fail.
- [ ] **Step 3:** Implement per Interfaces. `PeerBrowser` stores `result.endpoint` on the record and sets `isDirect` from `NWConnection.currentPath?.usesInterfaceType(.other)` with interface name prefix `awdl` (after the first connect).
- [ ] **Step 4:** Run the Mac checks. Expected: all pass, including the existing TLS/pairing checks.
- [ ] **Step 5:** Wire the iOS model and the Mac model to call the `peer:` overloads. The UI shows a "Direct" label when `isDirect` is true (iOS `DevicesView`, Mac `SendSurface` device row). Build both apps.
- [ ] **Step 6:** Commit `feat(core): peer-to-peer Bonjour and service-endpoint transfers`.

---

### Task 3: Shared offline link types (Swift)

**Files:**
- Create: `macos/Sources/LinkBridgeCore/OfflineLink.swift`
- Create: `docs/test-vectors/offline-link.json` (shared with Task 7)
- Modify: `docs/protocol.md` (new "Offline link" section: QR format and hello)
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift`

**Interfaces:**
- Produces:
  - `struct DiscoveryRecord: Codable, Equatable { rid, tags, deviceName, platform: String; protocolVersion: Int (JSON key "protocol"); port: UInt16; tlsPort: UInt16?; features: String }`
  - `struct OfflineLinkPayload: Equatable { ssid, passphrase: String; address: IPv4Address; helloPort: UInt16; record: DiscoveryRecord }`
  - `static func OfflineLinkPayload.parse(_ text: String) -> OfflineLinkPayload?` and `func encoded() -> String`
  - `enum OfflineHelloClient { static func exchange(host: NWEndpoint.Host, port: UInt16 = 49154, record: DiscoveryRecord, timeout: TimeInterval = 3) throws -> DiscoveryRecord }`
  - `static func ControlServer.discoveryRecord(...) -> DiscoveryRecord` (the same values `advertisedService()` puts in TXT; refactor so both use one source)
- QR format (decided here): `linkbridge-offline:1:` + base64url (no padding) of UTF-8 JSON `{"ssid","pass","addr","hello","record":{DiscoveryRecord}}`.
  - Limits: total text ≤ 1024 chars; ssid 1–32 bytes; pass 8–63 chars; `addr` private IPv4 only; ports 1–65535; `tags` ≤ 14 entries; unknown keys rejected.

- [ ] **Step 1: Write the vectors file.** Include ≥ 4 valid cases and these invalid ones: public addr `8.8.8.8`, `addr` `192.168.1.300`, port 0, ssid of 33 bytes, pass of 7 chars, wrong prefix `linkbridge-offline:2:`, unknown key, text of 1025 chars, bad base64.
- [ ] **Step 2: Failing check** `offlineLinkVectors`: every valid vector parses to its `expected` fields and round-trips through `encoded()`; every invalid one returns nil.
- [ ] **Step 3: Failing check** `helloExchangeRoundTrip`: a tiny in-check `NWListener` answers `POST /offline/hello` with a fixed record. `exchange` returns it, and the server saw the client's record. A 200 response whose body is > 4 KB is rejected with an error.
- [ ] **Step 4:** Run the Mac checks. Expected: the new checks fail.
- [ ] **Step 5:** Implement. The hello is HTTP/1.1 with `Content-Type: application/json`, `Content-Length` required, response `200` + JSON or `429`/`400`.
- [ ] **Step 6:** Run the Mac checks. Expected: pass. Update `docs/protocol.md`.
- [ ] **Step 7:** Commit `feat(core): offline link payload and hello client`.

---

### Task 4: iPhone runs `ControlServer`

**Files:**
- Create: `ios/LinkBridgeIOS/ReceiveHost.swift`
- Create: `ios/LinkBridgeIOS/Views/IncomingApprovalSheet.swift`
- Modify: `ios/LinkBridgeIOS/LinkBridgeIOSApp.swift` (scenePhase handler ~49), `LinkBridgeModel.swift`, `ios/Info.plist`
- Test: `ios/LinkBridgeIOSTests/ReceiveHostTests.swift`

**Interfaces:**
- Consumes: Task 1 `receiveDirectory`.
- Produces:
  - `protocol ServerControlling: AnyObject { func start(onReady: @escaping (UInt16?) -> Void); func stop() }`, with `ControlServer` conforming via an extension.
  - `@MainActor final class ReceiveHost` with:
    - `init(makeServer: @escaping (ReceiveHostCallbacks) -> ServerControlling)`
    - `func sceneBecameActive()`, `func sceneLeftForeground()`
    - `@Published var pendingPair: IncomingPairRequest?`, `@Published var pendingTransfer: IncomingTransferRequest?`
    - `func answer(pair: Bool)`, `func answer(transfer: Bool)`
    - `var isRunning: Bool`
  - `receiveDirectory` = the app's Documents directory.
- Mac approval waits are 110 s (project note). Reuse the same timeout constants from `ControlServer`.

- [ ] **Step 1: Failing tests:**
  - `testStartsOnActiveStopsOnBackground`: fake server; after `sceneBecameActive()`, `start` is called once and `isRunning == true`; after `sceneLeftForeground()`, `stop` is called once and `isRunning == false`; a second `sceneBecameActive()` starts again.
  - `testBackgroundDeclinesPendingApprovals`: with `pendingTransfer` set, `sceneLeftForeground()` calls the decision completion with `false` and clears `pendingTransfer`.
  - `testPartialFileRemovedOnStop` (Review Focus 3): in a `LinkBridgeCoreChecks` check, start a real server with a temp `receiveDirectory`, begin a slow upload, call `stop()` mid-body, then check the directory has no partial file and the client's send throws.
- [ ] **Step 2:** Run the iOS tests and Mac checks. Expected: fail.
- [ ] **Step 3:** Implement `ReceiveHost`, wire it in the app's scenePhase handler, and show `IncomingApprovalSheet` (code + Accept/Decline for pairing, file name + size + Accept/Decline for transfers). Make `ControlServer.stop()` cancel in-flight receives and delete their partial files if it doesn't already.
- [ ] **Step 4:** Info.plist: `UIFileSharingEnabled` = true; `LSSupportsOpeningDocumentsInPlace` = true. Extend the `NSLocalNetworkUsageDescription` text to mention receiving files.
- [ ] **Step 5:** Run the iOS tests and Mac checks. Expected: pass.
- [ ] **Step 6:** Commit `feat(ios): receive files and accept pairing while open`.

---

### Task 5: Mac → iPhone send

**Files:**
- Modify: `macos/Sources/LinkBridgePresentation/DeviceListItem.swift` (~61, ~70), `macos/Sources/LinkBridge/Views/SendSurface.swift:77`, `macos/Sources/LinkBridge/MacModel.swift`
- Test: `ios/LinkBridgeIOSTests/DeviceListItemTests.swift` (it already tests the shared presentation module)

**Interfaces:**
- Consumes: Task 2 `FileSender.send(filePath:peer:...)`.
- Produces: a paired `.iphone` peer with `tlsPort != nil` is sendable on the Mac. An unpaired iPhone row says "Pair from the iPhone app".

- [ ] **Step 1: Failing tests:** `testPairedIPhoneIsSendable` (paired + `platform: "ios"` + tlsPort → sendable, subtitle not "coming soon"); `testUnpairedIPhoneSubtitle` == `"Pair from the iPhone app"`.
- [ ] **Step 2:** Run the iOS tests. Expected: fail.
- [ ] **Step 3:** Implement. The Mac send path routes iPhone peers through the same `FileSender` overload.
- [ ] **Step 4:** Run the iOS tests and build the Mac app. Expected: pass.
- [ ] **Step 5:** Commit `feat(mac): send files to a paired iPhone`.

---

### Task 6: Android pairs with and sends to iPhone

**Files:**
- Modify: `android/app/src/main/java/com/linkbridge/android/presentation/DeviceListItem.kt` (~11), `MainViewModel.kt`, `ShareViewModel.kt` (anywhere `"macos"` gates pairing or sending), `DiscoveryService.kt` (if it filters platforms)
- Test: `android/app/src/test/java/com/linkbridge/android/MainViewModelPairingTest.kt`, new `DeviceListItemTest.kt`

**Interfaces:**
- Produces: `fun isServerPlatform(platform: String?): Boolean = platform == "macos" || platform == "ios"` in `DeviceListItem.kt`, used by every gate that checks for `"macos"` today. `"ios"` maps to a new `IPHONE` kind with its own icon and label "iPhone".

- [ ] **Step 1: Failing tests:** `unreachableIosPeerSaysOpenApp` (connection refused or timed out to a `platform = "ios"` peer → error text "Open LinkBridge on the iPhone"; the same rule goes in Task 5 for the Mac with a matching check `macUnreachableIphoneMessage`); `pairsWithIosPeer` (fake `ControlPairingClient`; starting pairing with a `DiscoveredPeer(platform = "ios")` reaches the code-confirm state the same way a Mac peer does); `iosMapsToIphone`; `iosPeerIsSendableWhenPaired`.
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement, replacing the literal `"macos"` checks with `isServerPlatform`. Pairing copy that says "Mac" becomes the peer's kind name.
- [ ] **Step 4:** Run the Android tests. Expected: pass.
- [ ] **Step 5:** Manual check on shared Wi-Fi: pair Android with iPhone (simulator or device), send both ways. Record the result for the Task 14 matrix.
- [ ] **Step 6:** Commit `feat(android): pair with and send to iPhone`.

---

### Task 7: Offline link types (Kotlin)

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/offline/OfflineLink.kt`
- Test: `android/app/src/test/java/com/linkbridge/android/offline/OfflineLinkTest.kt`

**Interfaces:**
- Produces: `data class DiscoveryRecord(rid, tags, deviceName, platform: String, protocol: Int, port: Int, tlsPort: Int?, features: String)` with `toJson()`/`fromJson(String): DiscoveryRecord?`; `data class OfflineLinkPayload(ssid, passphrase: String, address: Inet4Address, helloPort: Int, record: DiscoveryRecord)` with `encode(): String` and `companion fun parse(text: String): OfflineLinkPayload?`. Same format and limits as Task 3.

- [ ] **Step 1: Failing test** `vectorsMatchSwift`: read `File("../../docs/test-vectors/offline-link.json")`; same assertions as Task 3 Step 2.
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement with `org.json` (already used by `ControlClient`) and `java.util.Base64.getUrlEncoder().withoutPadding()`.
- [ ] **Step 4:** Run the Android tests. Expected: pass.
- [ ] **Step 5:** Commit `feat(android): offline link payload`.

---

### Task 8: `OfflineHotspot` state machine

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/offline/OfflineHotspot.kt`, `offline/WifiManagerHotspotPort.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/linkbridge/android/offline/OfflineHotspotTest.kt`

**Interfaces:**
- Produces:
  - `interface HotspotPort { fun start(callback: HotspotPortCallback); fun stop() }` with `HotspotPortCallback { onStarted(ssid: String, passphrase: String); onStopped(); onFailed(code: Int) }`.
  - `sealed interface HotspotState { Idle; Starting; Active(ssid, passphrase); Failed(reason: HotspotFailure); Stopped(bySystem: Boolean) }`.
  - `enum HotspotFailure { NO_CHANNEL, TETHERING_ON, INCOMPATIBLE_MODE, NOT_ALLOWED, PERMISSION_DENIED, UNSUPPORTED_OS, GENERIC }`, mapped from `LocalOnlyHotspotCallback.ERROR_NO_CHANNEL / ERROR_TETHERING_DISALLOWED / ERROR_INCOMPATIBLE_MODE / ERROR_GENERIC`, plus `SecurityException` → `PERMISSION_DENIED` and API < 33 → `UNSUPPORTED_OS`.
  - `class OfflineHotspot(port: HotspotPort, sdkInt: Int, clock: () -> Long, schedule: (delayMs: Long, block: () -> Unit) -> Cancelable)` with `val state: StateFlow<HotspotState>`, `fun start()`, `fun stop()`, `fun setBusy(busy: Boolean)`.
  - `IDLE_TIMEOUT_MS = 600_000`.
  - Manifest: `CHANGE_WIFI_STATE`; `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"`.

- [ ] **Step 1: Failing tests:**
  - `startsAndBecomesActive`
  - `idleStopsAfterTenMinutes` (advance the fake clock to 599_999 → still Active; to 600_000 → `Stopped(false)` and `port.stop()` called)
  - `busyPausesIdleTimer` (`setBusy(true)` at minute 9, advance 20 min → still Active; `setBusy(false)` then 10 min → stopped)
  - `mapsEveryFailureCode`
  - `systemStopReportsBySystem`
  - `belowApi33IsUnsupported` (sdkInt 32 → `Failed(UNSUPPORTED_OS)`, port never started)
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement. `WifiManagerHotspotPort` calls `startLocalOnlyHotspot` and reads `reservation.softApConfiguration.ssid/passphrase` (API 30+); `stop()` = `reservation.close()`.
- [ ] **Step 4:** Run the Android tests. Expected: pass.
- [ ] **Step 5:** Commit `feat(android): offline hotspot state machine`.

---

### Task 9: Android hotspot networking

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/offline/HotspotAddress.kt`
- Modify: `ReceiveServer.kt` (constructor at ~21; accept loop), `LinkBridgeService.kt`
- Test: `android/app/src/test/java/com/linkbridge/android/offline/HotspotAddressTest.kt`, `ReceiveServerAcceptFilterTest.kt`

**Interfaces:**
- Consumes: Task 8 `HotspotState.Active`; Task 0 Step 1 interface finding.
- Produces:
  - `fun findHotspotAddress(interfaces: List<NetInterfaceInfo>, stationAddress: Inet4Address?): Inet4Address?` (picks the up, non-loopback IPv4 that isn't the station Wi-Fi address and whose interface name matches the spike's findings, e.g. `swlan`/`ap`/`wlan1`). `NetInterfaceInfo(name, isUp, isLoopback, ipv4s)` is a plain data class so the function is testable.
  - `ReceiveServer(..., acceptFilter: (localAddress: InetAddress) -> Boolean = { true })`. While Active, the service sets the filter to `{ it == hotspotAddress }`.
  - `LinkBridgeService` aborts active receives and sends when the hotspot goes `Stopped`.

- [ ] **Step 1: Failing tests:**
  - `picksSoftApNotStation`
  - `nullWhenNoCandidate`
  - `acceptFilterRejectsOtherLocalAddress` (bind the server on loopback, filter `{ false }` → client connection is closed with no response)
  - `hotspotStopAbortsReceive` (Review Focus 1: a fake hotspot goes `Stopped(true)` mid-receive; `onTransferFailed` fires and no partial file remains in the test dir)
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the Android tests. Expected: pass.
- [ ] **Step 5:** Commit `feat(android): bind receive to the hotspot while it's on`.

---

### Task 10: Android offline hello server

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/offline/OfflineHelloServer.kt`
- Modify: `LinkBridgeService.kt` (merge hello peers into the discovery list), `DiscoveryService.kt` or the merge point (dedupe)
- Test: `android/app/src/test/java/com/linkbridge/android/offline/OfflineHelloServerTest.kt`, `PeerMergeTest.kt`

**Interfaces:**
- Consumes: Task 7 `DiscoveryRecord`; Task 9 hotspot address.
- Produces:
  - `class OfflineHelloServer(bindAddress: InetAddress, port: Int = 49154, localRecord: () -> DiscoveryRecord, isAllowedSource: (InetAddress) -> Boolean = ::isPrivateIpv4, clock: () -> Long, onPeer: (DiscoveryRecord, Inet4Address) -> Unit)` with `start(): Boolean` (false if the port is busy) and `stop()`.
  - `fun mergePeers(bonjour: List<DiscoveredPeer>, hello: List<DiscoveredPeer>, matchDeviceId: (rid: String, tags: String) -> String?): List<DiscoveredPeer>` dedupes on matched deviceId, else rid (Review Focus 5).

- [ ] **Step 1: Failing tests** (bind to 127.0.0.1, with `isAllowedSource` injected as `{ true }` except where noted):
  - `exchangeReturnsLocalRecordAndReportsPeer`
  - `rejectsBodyOver4KB` (400, no `onPeer`)
  - `rejectsInvalidJson`
  - `rejectsDisallowedSource` (`isAllowedSource = { false }` → connection closed, no `onPeer`)
  - `rateLimits21stRequestPerMinute` (429; then after the clock advances 60 s, 200 again)
  - `rejectsOtherPaths` (`GET /offline/hello`, `POST /pair/request` → 404)
  - `stopClosesPort`
  - `busyPortReturnsFalse`
  - `mergeDedupesBonjourAndHello`
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement. The service starts the hello server when the hotspot is Active and stops it on any other state. A failed `start()` logs and continues (spec errors section).
- [ ] **Step 4:** Run the Android tests. Expected: pass.
- [ ] **Step 5:** Commit `feat(android): offline hello endpoint`.

---

### Task 11: Android offline UI

**Files:**
- Create: `android/app/src/main/java/com/linkbridge/android/ui/offline/OfflineLinkScreen.kt`, `ui/offline/OfflineCard.kt`, `ui/offline/QrBitmap.kt`
- Modify: `ui/devices/*` (place the card), `MainViewModel.kt` (offline state and actions), `android/app/build.gradle*` (add `com.google.zxing:core:3.5.3`)
- Test: `android/app/src/test/java/com/linkbridge/android/MainViewModelOfflineTest.kt`

**Interfaces:**
- Consumes: Tasks 7–10.
- Produces: `MainViewModel.offline: StateFlow<OfflineUi>`, `startOffline()`, `stopOffline()`, where `OfflineUi` has `{ cardVisible: Boolean, cardText: String, screen: OfflineScreenUi? }` and `OfflineScreenUi` has `{ qrText, ssid, passphrase, address, minutesLeftLabel }`.
- Copy (from the spec):
  - card: "No Wi-Fi? Connect directly" / "Needs Android 13 or later"
  - screen: "Offline link is on", "Stops after 10 min idle", "Stop"
  - warnings: "This network has no internet." and "Your phone may leave its current Wi-Fi while this is on."
  - failure messages: one per `HotspotFailure`, e.g. `TETHERING_ON` → "Couldn't start offline link: turn off Mobile Hotspot and try again"
  - "LinkBridge needs Nearby devices to start the link" with Open Settings; "Offline link stopped"

- [ ] **Step 1: Failing tests:**
  - `cardShowsAfterFiveSecondsWithNoPeers`
  - `cardSaysNeedsAndroid13OnApi32`
  - `activeStateBuildsQrFromPayload` (`qrText` parses back with Task 7 `parse`)
  - `permissionDeniedShowsSettingsAction`
  - `eachFailureHasMessage`
- [ ] **Step 2:** Run the Android tests. Expected: fail.
- [ ] **Step 3:** Implement. The runtime permission request happens on "Start offline link". The QR is rendered via ZXing `QRCodeWriter` to a bitmap. The screen keeps the display on while it's visible.
- [ ] **Step 4:** Run the Android tests and `./scripts/build-android.sh`. Expected: pass and build.
- [ ] **Step 5:** Commit `feat(android): offline link screen and card`.

---

### Task 12: iPhone scans and joins

**Files:**
- Create: `ios/LinkBridgeIOS/Offline/HotspotJoiner.swift`, `Offline/QRScannerView.swift`, `Offline/OfflineJoinFlow.swift`
- Modify: `ios/LinkBridgeIOS/Views/DevicesView.swift` (card + entry point), `LinkBridgeModel.swift` (peer merge), `ios/LinkBridgeIOS.entitlements`, `ios/Info.plist`
- Test: `ios/LinkBridgeIOSTests/OfflineJoinFlowTests.swift`

**Interfaces:**
- Consumes: Task 3 `OfflineLinkPayload`, `OfflineHelloClient`, `DiscoveryRecord`; Task 4 server ports for the hello record.
- Produces:
  - `protocol HotspotJoining { func join(ssid: String, passphrase: String) async throws; func leave(ssid: String) }`. The real implementation uses `NEHotspotConfiguration(ssid:passphrase:isWEP: false)`, `joinOnce = false`, `NEHotspotConfigurationManager.shared.apply`, and `removeConfiguration(forSSID:)`. Treat `alreadyAssociated` as success and `userDenied` as cancelled.
  - `@MainActor final class OfflineJoinFlow` with `init(joiner:hello:browserPeers:clock:)`, `func handleScanned(_ text: String) async`, `func end()`, and `@Published var phase: JoinPhase` where `JoinPhase` is `.scanning`, `.joining`, `.connected(ResolvedPeer)`, `.failed(String)` or `.notLinkBridge`.
- Copy: "Scan Android's code", "This isn't a LinkBridge code", "Couldn't join: move closer to the Android and scan again", "Disconnected from the Android network" + "Rejoin", "This network has no internet."

- [ ] **Step 1: Failing tests:**
  - `invalidCodeShowsNotLinkBridgeAndNeverJoins` (Review Focus 2)
  - `cancelledJoinReturnsToScanning`
  - `joinFailureRemovesConfig`
  - `bonjourWithin3sWins` (fake browser yields a peer at 1 s → connected, hello not required)
  - `helloAfter3sFallback` (no Bonjour peer → hello is called with the QR address and helloPort, and the peer comes from the hello response at the QR address)
  - `bonjourAndHelloSameDeviceOneRow` (Review Focus 5)
  - `endRemovesConfig`
- [ ] **Step 2:** Run the iOS tests. Expected: fail.
- [ ] **Step 3:** Implement. The scanner uses `AVCaptureMetadataOutput` with `.qr`. The card appears after 5 s with no peers. Add the entitlement `com.apple.developer.networking.HotspotConfiguration` = true and `NSCameraUsageDescription` = "Scan the code on your Android device to connect without Wi-Fi."
- [ ] **Step 4:** Run the iOS tests and the build. Expected: pass.
- [ ] **Step 5:** Commit `feat(ios): join an Android offline link by QR`.

---

### Task 13: Mac guided join

**Files:**
- Create: `macos/Sources/LinkBridgeCore/OfflineLocator.swift`, `macos/Sources/LinkBridge/Views/OfflineJoinGuide.swift`
- Modify: `macos/Sources/LinkBridge/MacModel.swift`, `Views/MainWindow.swift` and `Views/MenuBarContent.swift` (card entry point)
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift`

**Interfaces:**
- Consumes: Task 3 `OfflineHelloClient`, `DiscoveryRecord`; Task 0 Step 5 result.
- Produces:
  - `final class OfflineLocator` with `init(gateway: () -> IPv4Address?, hello: (NWEndpoint.Host, DiscoveryRecord) throws -> DiscoveryRecord, bonjourPeers: () -> [ResolvedPeer], clock:)` and `func locate(localRecord: DiscoveryRecord, timeout: TimeInterval = 3) -> LocateResult`, where `LocateResult` is `.found(ResolvedPeer)` or `.needsManualAddress`.
  - `func connectManually(address: String) -> LocateResult`, which validates with the same private-IPv4 rule as Task 3.
  - The real gateway comes from `SCDynamicStoreCopyValue(..., "State:/Network/Global/IPv4")["Router"]`, or returns nil if Task 0 Step 5 failed.
- Copy: the 3-step guide text from spec section 3; "No LinkBridge device on this network. Check the name on Android"; "Enter the address shown on Android".

- [ ] **Step 1: Failing checks:**
  - `locatorPrefersBonjour`
  - `locatorFallsBackToHelloAtGateway`
  - `locatorNeedsManualWhenNoGateway`
  - `locatorNeedsManualWhenHelloFails`
  - `manualRejectsPublicAddress`
  - `manualAcceptsPrivateAddress` (hello is stubbed)
- [ ] **Step 2:** Run the Mac checks. Expected: fail.
- [ ] **Step 3:** Implement the locator and the guide view. The card appears after 5 s with no peers. While the guide is open, it polls the locator every 2 s.
- [ ] **Step 4:** Run the Mac checks and build the app. Expected: pass.
- [ ] **Step 5:** Commit `feat(mac): guided join to an Android offline link`.

---

### Task 14: Device matrix and docs

**Files:**
- Modify: `docs/roadmap.md` (tick "Local hotspot fallback"; add "Android 17 `ACCESS_LOCAL_NETWORK`" and "Resume interrupted transfers"), spec "Open risks" results if they changed, the PR description

- [ ] **Step 1:** Run every manual row from spec section 6 on real devices with no router (Android 13+ only). Add the rows from section 0: Android↔iPhone on Wi-Fi and offline, Mac→iPhone, iPhone backgrounded mid-transfer.
- [ ] **Step 2:** Record pass/fail per row in the PR description. Any failure goes back to the owning task.
- [ ] **Step 3:** Run all three automated suites. Expected: all green.
- [ ] **Step 4:** Commit `docs: offline link roadmap and results`.
