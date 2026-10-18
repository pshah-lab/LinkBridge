# macOS Liquid Glass Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the AppKit Mac UI with a SwiftUI menu bar popover + `NavigationSplitView` window using Liquid Glass on macOS 26, sharing presentation logic with iOS.

**Architecture:** A new Foundation-only `LinkBridgePresentation` target holds the logic both Apple apps share (moved from iOS) plus the new approval queue and history store; `LinkBridgeCoreChecks` tests it. Core gains an additive completion-based approval initializer. The Mac app becomes a SwiftUI `App` with one `@Observable MacModel` driving the `Window`, the `MenuBarExtra` and a notification controller.

**Tech Stack:** Swift 6.2 toolchain (Swift 5 language mode), SwiftUI + Liquid Glass (macOS 26 / iOS 26), Observation, UserNotifications, Network.framework via LinkBridgeCore.

**Spec:** `docs/superpowers/specs/2026-10-18-macos-liquid-glass-redesign-design.md`

## Global Constraints

- Platforms `.macOS(.v26)`, `.iOS(.v26)`; no availability fallbacks or `#available` branches for glass.
- `swift-tools-version: 6.2` (required for `.v26`) with `swiftLanguageModes: [.v5]` so Core does not hit Swift 6 strict-concurrency errors.
- `LinkBridgePresentation` imports only Foundation, Observation and LinkBridgeCore — never SwiftUI, AppKit or UIKit.
- Build: `swift build --build-system native` from `macos/`. Checks: `swift run --build-system native LinkBridgeCoreChecks` from `macos/`; expected last line `N passed, 0 failed` (whatever the runner prints with zero failures).
- iOS build/test: `xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGNING_ALLOWED=NO build` (and `test`).
- Never run a freshly built Mac app binary except in the manual task (each triggers a login-keychain prompt). Checks keep using their `CFFIXED_USER_HOME` isolation.
- `UNUserNotificationCenter` is only touched when `Bundle.main.bundleIdentifier != nil` (it traps in an unbundled `swift run`).
- Copy is fixed by the spec's Screens / Empty states tables; use it verbatim.
- Glass only on: toolbar, connection rail, Send control, approval rows/sheet. Lists, rows, drop zones stay solid.
- `linkbridge-cli` must compile unchanged.
- Commit after each task; message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Incoming file nobody answers / phone gives up** — the waiting receive thread must not block forever: after 300 s it declines, and the pending row disappears (Task 3 timeout check, Task 4 `withdraw`).
2. **Same request answered twice** (notification Accept + popover Decline racing) — only the first answer reaches the server (Task 3 "first completion wins", Task 4 resolve-once check).
3. **Pairing answered after expiry** — late Accept must not save a trusted peer (Task 3 check).
4. **Corrupt or old history data** — legacy Mac entries migrate; unreadable data yields empty history, never a crash, and legacy key is removed only after a successful write (Task 5 checks).
5. **Unbundled run / notifications denied** — app still launches and approvals still work through popover and window (Task 9 guard; manual check in Task 10).

---

### Task 1: Raise platforms and toolchain

**Files:**
- Modify: `macos/Package.swift`

**Interfaces:**
- Produces: package on tools 6.2, `.macOS(.v26)`, `.iOS(.v26)`, Swift 5 language mode.

- [ ] **Step 1:** Change the header to `// swift-tools-version: 6.2`, platforms to `.macOS(.v26), .iOS(.v26)`, add `swiftLanguageModes: [.v5]` to `Package(...)`.
- [ ] **Step 2:** Run `swift build --build-system native` from `macos/`. Expected: builds (deprecation warnings OK). Fix only compile errors caused by the bump.
- [ ] **Step 3:** Run the checks. Expected: 0 failed.
- [ ] **Step 4:** Run the iOS build and test commands. Expected: BUILD SUCCEEDED / TEST SUCCEEDED.
- [ ] **Step 5:** Commit `build: raise package to macOS 26 / iOS 26 on Swift 6.2 tools`.

### Task 2: Shared presentation module

**Files:**
- Create: `macos/Sources/LinkBridgePresentation/` — `ConnectionState.swift`, `SendPhase.swift`, `DeviceListItem.swift` (incl. `DevicePlatform`, `DeviceSelection`), `DroppedFile.swift`, `HistoryRowContent.swift`, `TransferRecord.swift`, `Accent.swift` (hex values, `onLightHex`/`onDarkHex`, `contrastRatio`).
- Create: `ios/LinkBridgeIOS/Theme/Accent+Color.swift` (the `UIColor` bridge + backdrop, from the old `Theme.swift`).
- Delete: `ios/LinkBridgeIOS/Presentation/*`, `ios/LinkBridgeIOS/Theme/Theme.swift`; remove `TransferRecord` from `LinkBridgeModel.swift`.
- Modify: `macos/Package.swift` (target + `.library(name: "LinkBridgePresentation", ...)`, add to `LinkBridgeCoreChecks` and `LinkBridge` deps), `ios/LinkBridgeIOS.xcodeproj/project.pbxproj` (add product dependency + framework build file, mirroring the existing `LinkBridgeCore` entries `A100…0A/0B`), iOS sources/tests: add `import LinkBridgePresentation`.
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift` (new `// MARK: - Presentation` sections; `import LinkBridgePresentation`).

**Interfaces:**
- Produces (all `public`, memberwise inits made `public` where the apps construct them):
  - The moved types with unchanged names and behaviour.
  - `public enum TransferDirection: String, Codable { case sent, received }`
  - `public struct TransferRecord: Codable, Identifiable, Equatable` with fields `id: UUID, fileName: String, peerName: String, bytes: Int64, date: Date, succeeded: Bool, detail: String?, direction: TransferDirection, bookmark: Data?, fileURL: URL?` and `init(id: UUID = UUID(), fileName:peerName:bytes:date:succeeded:detail:direction: = .sent, bookmark: = nil, fileURL: = nil)`. Custom `init(from:)` uses `decodeIfPresent` for `direction` (default `.sent`), `bookmark`, `fileURL`.
  - `HistoryRowContent` adds `public let canShowInFinder: Bool` (`direction == .received && succeeded && fileURL != nil`); detail prefix `"To \(peer)"` / `"From \(peer)"`; symbol `arrow.up.circle.fill` / `arrow.down.circle.fill` when succeeded, `exclamationmark.circle.fill` when failed. `canRetry` only for `.sent`.

- [ ] **Step 1: Port checks.** Translate every assertion in `ios/LinkBridgeIOSTests/{ConnectionState,DeviceListItem,HistoryRowContent,SelectionTests,SendPhase,Theme}Tests.swift` into `section(...) { check(...) }` blocks, plus new checks:

```swift
section("History rows: direction") {
    let received = TransferRecord(fileName: "a.pdf", peerName: "Pixel 8", bytes: 2048, date: .now,
                                  succeeded: true, detail: nil, direction: .received,
                                  fileURL: URL(fileURLWithPath: "/tmp/a.pdf"))
    let row = HistoryRowContent(received)
    check(row.detail.hasPrefix("From Pixel 8"), "received rows say From")
    check(row.symbol == "arrow.down.circle.fill", "received symbol")
    check(row.canShowInFinder && !row.canRetry, "received: Finder, no retry")
}
section("TransferRecord decodes pre-direction JSON") {
    let old = #"{"id":"\#(UUID())","fileName":"x","peerName":"p","bytes":1,"date":0,"succeeded":true}"#
    let r = try JSONDecoder().decode(TransferRecord.self, from: Data(old.utf8))
    check(r.direction == .sent && r.bookmark == nil && r.fileURL == nil, "defaults")
}
```

- [ ] **Step 2:** Run checks. Expected: compile failure (`no such module 'LinkBridgePresentation'`).
- [ ] **Step 3:** Create the target and move the files (`git mv` from `ios/LinkBridgeIOS/Presentation/`), add `public`, add `TransferDirection`/`fileURL`/direction-aware `HistoryRowContent`. `Accent.swift` keeps only hex logic; the iOS `Accent+Color.swift` adds `var color: Color` / `var onColor: Color` and `linkBridgeBackdrop()` as an extension.
- [ ] **Step 4:** Run checks. Expected: 0 failed.
- [ ] **Step 5:** Wire the iOS project (pbxproj product dependency; synchronized groups pick up the deletions), add imports in iOS sources and tests (tests: `import LinkBridgePresentation` beside `@testable import LinkBridgeIOS`). Run iOS build and test. Expected: SUCCEEDED.
- [ ] **Step 6:** Commit `refactor: move shared presentation logic into LinkBridgePresentation`.

### Task 3: Completion-based approvals in Core

**Files:**
- Modify: `macos/Sources/LinkBridgeCore/ControlServer.swift`, `macos/Sources/LinkBridgeCore/TrustedPeerStore.swift`
- Test: `macos/Sources/LinkBridgeCoreChecks/main.swift`

**Interfaces:**
- Produces:
  - `IncomingPairRequest` and `IncomingTransferRequest` gain `public let id: UUID` (new per request); `IncomingPairRequest` gains `public let expiresAt: Date` (the pending pairing's existing 3-minute expiry).
  - New designated init (labels differ from the old one so calls stay unambiguous):
    ```swift
    public init(identity: DeviceIdentity, preferredPort: UInt16 = 49152, preferredSecurePort: UInt16 = 49162,
                pairDecision: @escaping (IncomingPairRequest, @escaping (Bool) -> Void) -> Void,
                transferDecision: @escaping (IncomingTransferRequest, @escaping (Bool) -> Void) -> Void,
                transferProgress: @escaping (Int64, Int64) -> Void = { _, _ in },
                transferReceived: @escaping (IncomingTransferRequest, URL) -> Void = { _, _ in },
                transferWithdrawn: @escaping (UUID) -> Void = { _ in })
    ```
  - The existing `Bool` init becomes a convenience wrapper (`pairDecision: { r, done in done(pairApproval(r)) }`, `transferReceived: { r, _ in transferCompleted(r) }`).
  - `public static let transferApprovalTimeout: TimeInterval = 300`
  - `TrustedPeerStore.remove(deviceId: UUID)` (public; deletes the peer and its stored secret).
- Behaviour: `pairDecision` is called on the main queue; the decision is recorded when the completion fires (any thread, hop to main), only the first call counts, and a call after `expiresAt` is ignored (no trusted peer saved, pairing reported declined/expired as today). `transferDecision` is called off-main; the receive thread waits on a semaphore up to `transferApprovalTimeout`; timeout = decline + `transferWithdrawn(id)` on main. `transferReceived` gets the final file URL.

- [ ] **Step 1: Write failing checks** (reuse `startServer`/`pairAsPhone` helpers; add a `startServer(decision:)` overload that uses the new init and captures the completion):
  - `"async pair: accept after delay saves peer"` — store completion, call `done(true)` 0.5 s later from a background queue; `pairAsPhone(...).finalizeStatus == 200`; `TrustedPeerStore().find(deviceId:) != nil`.
  - `"async pair: decline"` — `done(false)`; finalize is rejected; peer not stored.
  - `"async pair: first completion wins"` — `done(false); done(true)`; peer not stored.
  - `"async pair: late completion ignored"` — build an `IncomingPairRequest`-driven pending pairing with expiry forced into the past via an internal (`@testable`) hook `ControlServer.pairingLifetime` (make the 3-minute literal an internal static var, default `180`); set it to `0.2`, answer `true` after 0.5 s; peer not stored.
  - `"async transfer: timeout declines and withdraws"` — set internal `transferApprovalTimeoutOverride` to `0.3`, never call the completion; the upload fails with the declined status and `transferWithdrawn` fires with the request's `id`.
  - `"Bool init still works"` — existing live-server checks unchanged and passing.
  - `"remove trusted peer"` — save, `remove(deviceId:)`, `find == nil`.
- [ ] **Step 2:** Run checks. Expected: compile failure on `pairDecision` / `remove`.
- [ ] **Step 3:** Implement per Interfaces. Remove the `approvalInProgress` main-thread assumption only as far as needed: it still serialises one pairing decision at a time.
- [ ] **Step 4:** Run checks. Expected: 0 failed. Run `swift build --build-system native --product linkbridge-cli`. Expected: builds with no source change to the CLI.
- [ ] **Step 5:** Commit `feat(core): completion-based pair and transfer approvals`.

### Task 4: ApprovalQueue and ApprovalCenter

**Files:**
- Create: `macos/Sources/LinkBridgePresentation/ApprovalQueue.swift`, `macos/Sources/LinkBridgePresentation/ApprovalCenter.swift`
- Test: checks `main.swift`

**Interfaces:**
- Produces:
  ```swift
  public enum ApprovalKind: Equatable {
      case pair(deviceName: String, code: String, replacesExisting: Bool, expiresAt: Date)
      case file(senderName: String, fileName: String, bytes: Int)
  }
  public struct PendingApproval: Identifiable, Equatable { public let id: UUID; public let kind: ApprovalKind; public let receivedAt: Date }
  public struct ExpiredNotice: Identifiable, Equatable { public let id: UUID; public let title: String; public let until: Date }
  public struct ApprovalQueue: Equatable {
      public private(set) var items: [PendingApproval]      // arrival order
      public private(set) var notices: [ExpiredNotice]
      public var oldest: PendingApproval? { items.first }
      public mutating func add(_ item: PendingApproval)
      @discardableResult public mutating func remove(id: UUID) -> PendingApproval?
      /// Moves pairs whose expiresAt <= now into notices ("Pairing request expired", until now+5s),
      /// drops notices whose until <= now, returns the ids that expired.
      public mutating func expire(now: Date) -> [UUID]
  }
  @MainActor @Observable public final class ApprovalCenter {
      public init(now: @escaping () -> Date = Date.init)
      public private(set) var queue: ApprovalQueue
      public var pendingCount: Int { get }
      public var onRemoved: (UUID) -> Void          // notification withdrawal hook
      public func enqueue(id: UUID, kind: ApprovalKind, completion: @escaping (Bool) -> Void)
      public func resolve(id: UUID, approved: Bool) // calls completion once, removes, onRemoved
      public func withdraw(id: UUID)                // server gave up: remove + onRemoved, no completion
      public func tick()                            // expire(now:); completion(false) + onRemoved per expired id
      public func declineAll()                      // app quit
  }
  ```
  Add `ApprovalKind.init(_ request: IncomingPairRequest)` and `init(_ request: IncomingTransferRequest)`.

- [ ] **Step 1: Failing checks** (wrap in `MainActor.assumeIsolated { }`, since top-level checks run on the main thread):
  - order: add A then B → `oldest?.id == A`; remove A → `oldest?.id == B`.
  - expire: pair with `expiresAt = t0+1`; `expire(now: t0+2) == [id]`; `items.isEmpty`; `notices.first?.title == "Pairing request expired"`; `expire(now: t0+8)` → `notices.isEmpty`. File items never expire.
  - resolve once: count completion calls; `resolve(id, true)` twice → 1 call with `true`; `onRemoved` called once.
  - withdraw: no completion call; item gone; `onRemoved` called.
  - tick: injected clock past expiry → completion called with `false`.
  - declineAll: two items → both completions `false`, `pendingCount == 0`.
- [ ] **Step 2:** Run checks. Expected: compile failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run checks. Expected: 0 failed.
- [ ] **Step 5:** Commit `feat(presentation): approval queue and center`.

### Task 5: HistoryStore with Mac migration

**Files:**
- Create: `macos/Sources/LinkBridgePresentation/HistoryStore.swift`
- Test: checks `main.swift`

**Interfaces:**
- Produces:
  ```swift
  public final class HistoryStore {
      public init(defaults: UserDefaults = .standard, key: String, legacyMacKey: String? = nil, limit: Int = 200)
      public func load() -> [TransferRecord]          // newest first; migrates legacy once
      @discardableResult public func add(_ record: TransferRecord) -> [TransferRecord]
      @discardableResult public func remove(id: UUID) -> [TransferRecord]
  }
  ```
  Mac uses `key: "linkbridge.history.v2", legacyMacKey: "linkbridge.transfer-history"`. Legacy element shape: `{fileName: String, bytes: Int64, peerName: String, isOutgoing: Bool, completedAt: Date}` encoded with default `JSONEncoder` (dates as seconds since reference date). Maps to `succeeded: true, detail: nil, direction: isOutgoing ? .sent : .received, date: completedAt`.

- [ ] **Step 1: Failing checks** using `UserDefaults(suiteName: "checks-\(UUID())")!`:
  - legacy two entries (one outgoing, one incoming) → `load()` returns 2 records with matching directions and dates; legacy key now `nil`; second `load()` returns the same 2 (no duplication).
  - legacy key holding garbage bytes → `load() == []`, no crash; legacy key left untouched.
  - `add` 201 records with `limit: 200` → count 200, newest first.
  - `remove(id:)` removes exactly that record.
- [ ] **Step 2:** Run checks. Expected: compile failure.
- [ ] **Step 3:** Implement. Write the new key first, then delete the legacy key only if that write's re-read decodes.
- [ ] **Step 4:** Run checks. Expected: 0 failed.
- [ ] **Step 5:** Commit `feat(presentation): history store with Mac history migration`.

### Task 6: SwiftUI app shell and MacModel

**Files:**
- Delete: `macos/Sources/LinkBridge/MacGuiApp.swift`, `macos/Sources/LinkBridge/main.swift`
- Create: `macos/Sources/LinkBridge/LinkBridgeApp.swift`, `MacModel.swift`, `Theme/Accent+Color.swift`, `Theme/Backdrop.swift`, `Views/MainWindow.swift` (sidebar skeleton only)

**Interfaces:**
- Consumes: Task 2 types, Task 3 init, Task 4 `ApprovalCenter`, Task 5 `HistoryStore`.
- Produces (`MacModel`, `@MainActor @Observable final class`):
  - state: `nearby: [ResolvedPeer]`, `trusted: [TrustedPeer]`, `lastSeen: [UUID: Date]`, `selectedPeerId: UUID?`, `sendPhase: SendPhase`, `receiving: (request: IncomingTransferRequest, progress: Double)?`, `history: [TransferRecord]`, `approvals: ApprovalCenter`, `serverError: String?`, `identity: DeviceIdentity`.
  - derived: `deviceItems: [DeviceListItem]` (`DeviceListItem.merge`), `connection: ConnectionState` (`derive`, `errorMessage: serverError ?? lastError`), `recent: [TransferRow]` where `enum TransferRow: Identifiable { case live(LiveTransfer), finished(TransferRecord) }` and live rows come first (pending file approvals, current receive, current send).
  - actions: `start()`, `select(_ id: UUID?)` (persists `"selectedDeviceId"`), `stage(_ url: URL)`, `send()`, `sendNow(_ url: URL)` (stage + send; popover path), `retry(_ record: TransferRecord)`, `resolve(_ id: UUID, approved: Bool)`, `showInFinder(_ record: TransferRecord)`, `removeHistory(_ id: UUID)`, `unpair(_ deviceId: UUID)` (store remove + `server.reloadTrustedPeers()`), `openReceiveFolder()`, `shutdown()` (`approvals.declineAll()`, stop server/browser).
  - Send pipeline ported from `MacGuiApp.sendFileURL` (secret from `TrustedPeerStore`, `secureEndpoint`, `FileSender.send` off-main, progress to `sendPhase`); failure message `"Couldn't reach \(name) — check it's on the same Wi-Fi, then retry."`; failed records keep `bookmark`. Busy rule: `stage`/`sendNow` are ignored while `sendPhase` is `.sending`.
  - Receive: `transferReceived` appends a `.received` record with `fileURL`; server start failure sets `serverError` to the spec's Local Network sentence.
- `LinkBridgeApp`: `@main struct LinkBridgeApp: App` with `@NSApplicationDelegateAdaptor` (`applicationShouldTerminateAfterLastWindowClosed` → `false`; `applicationWillTerminate` → `model.shutdown()`), `Window("LinkBridge", id: "main") { MainWindow() }`, `MenuBarExtra { Text("LinkBridge") } label: { … }` placeholder (Task 8 fills it). Activation policy stays `.regular`.

- [ ] **Step 1:** Implement the files above; the window shows the sidebar (Devices · History · Settings) with plain placeholder detail views.
- [ ] **Step 2:** Run `swift build --build-system native`. Expected: builds. Run checks. Expected: 0 failed.
- [ ] **Step 3:** Run `scripts/package-mac-app.sh` (builds the bundle; does not launch it). Expected: `dist/LinkBridge.app` produced, `plutil` passes.
- [ ] **Step 4:** Commit `feat(mac): SwiftUI app shell and MacModel replace AppKit UI`.

### Task 7: Window — devices, send surface, history, settings

**Files:**
- Create: `macos/Sources/LinkBridge/Views/ConnectionRail.swift`, `DevicesView.swift`, `SendSurface.swift`, `SendControl.swift`, `TransferRow.swift`, `HistoryView.swift`, `SettingsView.swift`
- Modify: `Views/MainWindow.swift`

**Interfaces:**
- Consumes: `MacModel` (Task 6).
- Produces: `ConnectionRail(state: ConnectionState, devices: [DeviceListItem], selected: UUID?, onSelect: (UUID) -> Void)` and `TransferRow(row: MacModel.TransferRow, model: MacModel)`, both reused by Task 8.

- [ ] **Step 1:** `ConnectionRail`: glass capsule (`.glassEffect(.regular, in: .capsule)`), 8 pt state dot coloured by `dotAccent` (grey when nil), title truncating, chevron `Menu` only when ≥ 2 sendable devices; accessibility label `"\(dotLabel), \(title)"`. Placed in the window toolbar.
- [ ] **Step 2:** `DevicesView` sidebar section rows: platform symbol, name, subtitle, "Paired" badge. Detail = `SendSurface` for the selected item.
- [ ] **Step 3:** `SendSurface`: solid rounded drop zone filling the detail (`.dropDestination(for: URL.self)` → `DroppedFile.stage` → `model.stage`; click → `fileImporter`), copy "Drop a file to send to \(name)". `SendControl` floats at bottom inside a `GlassEffectContainer`, morphs between `SendPhase` states with `glassEffectID("send", in: ns)`, tinted cobalt when actionable; `withAnimation` respects Reduce Motion (cross-fade). Not-sendable devices show the item's subtitle sentence and no control. No devices → "No devices yet — open LinkBridge on your phone on the same Wi-Fi."
- [ ] **Step 4:** `TransferRow`: solid row with `HistoryRowContent` (finished) or progress / "Waiting for you" with Accept/Decline (live). Actions: Show in Finder (`canShowInFinder`), Retry (`canRetry`), context-menu Remove. `HistoryView`: live rows first, then finished grouped by day (`Calendar.startOfDay`); empty → "Files you send and receive will appear here."
- [ ] **Step 5:** `SettingsView`: Mac name + device ID with Copy; "Receive folder: Downloads" + Open in Finder; paired devices list with Unpair behind `confirmationDialog`; `Toggle("Notify me about incoming files and pairing requests", isOn: $notifyIncoming)` backed by `@AppStorage("notifyIncoming")` default `true`; denied-permission hint line (value supplied by Task 9; until then hidden). Server error banner in the detail area with the spec's Local Network sentence and "Open Settings" (`x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork`).
- [ ] **Step 6:** Build. Expected: builds; checks 0 failed.
- [ ] **Step 7:** Commit `feat(mac): window with devices, send surface, history and settings`.

### Task 8: Menu bar popover and approval UI

**Files:**
- Create: `macos/Sources/LinkBridge/Views/MenuBarContent.swift`, `CompactDropTarget.swift`, `ApprovalContent.swift`, `ApprovalRow.swift`, `ApprovalSheet.swift`
- Modify: `LinkBridgeApp.swift` (real `MenuBarExtra` content + `.menuBarExtraStyle(.window)`), `Views/MainWindow.swift` (sheet)

**Interfaces:**
- Consumes: `ConnectionRail`, `TransferRow` (Task 7), `MacModel.approvals` (Task 4/6).
- Produces: `ApprovalContent(item: PendingApproval, now: Date, onDecide: (Bool) -> Void)` shared by row and sheet.

- [ ] **Step 1:** `ApprovalContent`: pair → "Pair with \(name)?", code via `PairingCode.display` in `.system(.largeTitle, design: .monospaced)` with `.accessibilityLabel` reading digits separated by spaces, "Only accept if your phone shows the same code.", replace-existing warning when set, countdown `mm:ss` from `expiresAt - now`; file → name, size (`ByteCountFormatter` `.file`), "from \(sender)". Buttons Accept (`.keyboardShortcut(.defaultAction)`) / Decline (`.keyboardShortcut(.cancelAction)`).
- [ ] **Step 2:** `ApprovalRow`: `ApprovalContent` in coral-tinted glass (`.glassEffect(.regular.tint(Accent.attention.color))`). Expired notices render as a single line with the notice title.
- [ ] **Step 3:** `MenuBarContent` (width 340): rail in a `GlassEffectContainer`; approvals (all items + notices); `CompactDropTarget` (64 pt, solid; drop/click → `model.sendNow`; disabled line "Pair a device to send files" when nothing sendable, "Sending…" while busy); up to 3 `model.recent` rows; footer "Open LinkBridge" (`openWindow(id: "main")` + `NSApp.activate()`) and Quit. A `TimelineView(.periodic(from: .now, by: 1))` drives countdowns and calls `approvals.tick()`.
- [ ] **Step 4:** Menu bar label: `Image(systemName: pending ? "arrow.left.arrow.right.circle.fill" : "arrow.left.arrow.right.circle")`, coral `foregroundStyle` when pending, accessibility label "LinkBridge — \(n) request(s) waiting" when pending, else "LinkBridge".
- [ ] **Step 5:** Window: `.sheet(item:)` bound to `approvals.queue.oldest` only while `controlActiveState == .key`; content `ApprovalSheet` wrapping `ApprovalContent`.
- [ ] **Step 6:** Wire `MacModel` so the server's `pairDecision`/`transferDecision` call `approvals.enqueue(id: request.id, kind: ApprovalKind(request), completion:)` on main, and `transferWithdrawn` calls `approvals.withdraw`.
- [ ] **Step 7:** Build; checks 0 failed.
- [ ] **Step 8:** Commit `feat(mac): menu bar popover and inline/sheet approvals`.

### Task 9: Notifications

**Files:**
- Create: `macos/Sources/LinkBridge/NotificationController.swift`
- Modify: `MacModel.swift`, `LinkBridgeApp.swift`, `Views/SettingsView.swift`

**Interfaces:**
- Consumes: `ApprovalCenter.onRemoved`, `MacModel.resolve`.
- Produces: `@MainActor final class NotificationController: NSObject, UNUserNotificationCenterDelegate` with `init?(onAction: @escaping (UUID, Bool?) -> Void)` (returns `nil` when `Bundle.main.bundleIdentifier == nil`; `Bool?` nil = Review), `requestAuthorization()`, `post(_ item: PendingApproval)`, `withdraw(id: UUID)`, `var isDenied: Bool`.
- Constants: categories `"LINKBRIDGE_FILE"` (actions `"ACCEPT"` "Accept", `"DECLINE"` "Decline") and `"LINKBRIDGE_PAIR"` (action `"REVIEW"` "Review", foreground). Notification identifier = approval id string; `userInfo["approvalId"]`. Pair body: "\(name) wants to pair. Compare the code in LinkBridge." File body: "\(sender) wants to send \(file) (\(size))."

- [ ] **Step 1:** Implement. `MacModel` posts on enqueue only when `notifyIncoming` is on; `approvals.onRemoved` → `withdraw`. Accept/Decline → `model.resolve(id, approved:)`; Review or tapping the banner → `openWindow(id: "main")` + activate. Delegate's `willPresent` returns `[.banner, .sound]`.
- [ ] **Step 2:** Settings shows "Notifications are off for LinkBridge — turn them on in System Settings › Notifications." when `isDenied`.
- [ ] **Step 3:** Build; checks 0 failed; `scripts/package-mac-app.sh` succeeds.
- [ ] **Step 4:** Commit `feat(mac): actionable notifications for approvals`.

### Task 10: Verification, screenshots and docs

**Files:**
- Create: `docs/screenshots/macos/*.png`
- Modify: `DESIGN.md` (link `docs/screenshots/macos/`), `README.md` only if it describes the old Mac UI.

- [ ] **Step 1:** Install the bundle (`scripts/install-mac-app.sh`), accept the one keychain prompt. Capture popover and window in each state — empty, searching, connected, sending, failed, approval pending (pair + file), expired notice — in light, dark, Reduce Transparency and the largest text size. Save as `docs/screenshots/macos/<surface>-<state>-<mode>.png`.
- [ ] **Step 2:** Real-device run with the Android phone: pair (code matches, accept from popover), send Mac → phone, phone → Mac accepted from the notification, both in History; Show in Finder opens the file. Repeat once with notifications denied (approve from the window sheet with Return).
- [ ] **Step 3:** VoiceOver completes pair and send; keyboard-only Tab order matches the spec.
- [ ] **Step 4:** Final checks run (0 failed), iOS build + test SUCCEEDED, `linkbridge-cli` builds.
- [ ] **Step 5:** Commit `docs: macOS screenshots and design notes`; open the PR with screenshots attached.
