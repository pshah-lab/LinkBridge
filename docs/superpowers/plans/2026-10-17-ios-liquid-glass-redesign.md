# iOS Liquid Glass Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Also load the `swiftui-liquid-glass` and `swiftui-expert-skill` skills before writing view code.

**Goal:** Rebuild the iOS companion's interface on the shared LinkBridge design language: a glass tab bar (Devices · Send · History), a glass connection rail, a morphing glass Send control, a glass pairing sheet, solid content lists.

**Architecture:** Pure presentation types (`ConnectionState`, `DeviceListItem`, `SendPhase`, `HistoryRowContent`) are derived from `LinkBridgeModel` and unit-tested. Views are thin, one file per screen or component, and read only those types plus model actions. Networking (`PeerBrowser`, `PairingClient`, `FileSender`) is untouched.

**Tech Stack:** Swift 6, SwiftUI on iOS 26 (Liquid Glass: `glassEffect`, `GlassEffectContainer`, `glassEffectID`, `.buttonStyle(.glass/.glassProminent)`), Swift Testing, LinkBridgeCore (local package `../macos`).

**Spec:** `docs/superpowers/specs/2026-10-17-liquid-glass-design-language-design.md`

## Global Constraints

- iOS deployment target 26.0 (raised from 16.0). No pre-26 fallback code, no `#available` checks for glass.
- Glass only on: tab bar, connection rail, Send control, pairing sheet. Never on lists, history rows, the drop zone or body text.
- Neighbouring glass elements share one `GlassEffectContainer`. Tint glass only for state: cobalt = action, coral = needs attention.
- Accents: Action `#3E62E8` / dark `#7C95FF`; Signal `#35C98B` / dark `#4ADBA0`; Attention `#F08A5D` / dark `#FF9F78`. Backdrop: cobalt at 6% (light) / 10% (dark) over the system background. All other text and surfaces use system colours.
- System font only; pairing code uses `.monospacedDigit()` at large size.
- One dominant action per screen.
- Reduce Motion: morphs become cross-fades, no scale or bounce.
- Text on glass reaches 4.5:1; prefer icons and short labels on glass.
- Hit targets ≥ 44 pt. Every control has an accessibility label; the state dot also exposes "Connected", "Searching" or "Error".
- Behaviour unchanged: discovery, pairing (iPhone initiates, Mac approves), TLS-PSK send, 50-entry history.
- Simulator: iOS 27.1 runtime, device "iPhone Duo" (newer device types are incompatible with this runtime).
- Build: `xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGNING_ALLOWED=NO build`
- Test: `xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGNING_ALLOWED=NO test`
- Core checks after touching LinkBridgeCore: `cd macos && swift run --build-system native LinkBridgeCoreChecks`

## Review Focus

1. **Old history JSON** saved by the current app (no `bookmark` key) must still load after the update — nobody loses their history. Test in Task 3.
2. **A paired device that is not nearby** must still appear in Devices (greyed, "Last seen …" or "Paired <date>") and must not be selectable as a send target. Test in Task 2.
3. **Nearby peer with no device id or no TLS port** (older app) shows a clear "needs a LinkBridge update" row and never enables Send. Test in Task 2.
4. **Retry on a history row whose file is gone** (bookmark stale or file deleted) shows an error state instead of crashing or silently doing nothing. Test in Task 3 (`HistoryRowContent.canRetry` false without bookmark) and manual check in Task 8.
5. **Very long device and file names at the largest accessibility text size** truncate in the rail and wrap in rows without clipping controls. Manual check in Task 8.

---

## File Structure

```
ios/LinkBridgeIOS/
  LinkBridgeIOSApp.swift          App + RootView (TabView, toolbar, sheets) — rewritten
  LinkBridgeModel.swift           State + actions — modified (see Task 3 interfaces)
  Presentation/
    ConnectionState.swift         Rail state enum + derivation
    DeviceListItem.swift          Merged nearby + paired device rows
    SendPhase.swift               Send control state machine
    HistoryRowContent.swift       History row formatting
  Theme/
    Theme.swift                   Accent colours, backdrop
  Views/
    ConnectionRail.swift          Glass capsule with state dot
    DevicesView.swift             Devices tab
    PairingSheet.swift            Glass pairing sheet
    SendView.swift                Send tab: drop zone + SendControl
    SendControl.swift             Morphing glass Send button / progress pill
    HistoryView.swift             History tab
    SettingsView.swift            Settings sheet
ios/LinkBridgeIOSTests/           Swift Testing unit tests (new target)
ios/LinkBridgeIOS.xcodeproj/xcshareddata/xcschemes/LinkBridgeIOS.xcscheme   shared scheme incl. tests (new)
macos/Sources/LinkBridgeCore/PeerResolver.swift      add public init to ResolvedPeer
macos/Sources/LinkBridgeCore/TrustedPeerStore.swift  add public init to TrustedPeer
```

Both app and test folders are `PBXFileSystemSynchronizedRootGroup`s, so new files inside them need no project edits.

---

### Task 1: iOS 26 target, test target and theme tokens

**Files:**
- Modify: `ios/LinkBridgeIOS.xcodeproj/project.pbxproj` (both `IPHONEOS_DEPLOYMENT_TARGET = 16.0;` → `26.0`; add test target)
- Create: `ios/LinkBridgeIOS.xcodeproj/xcshareddata/xcschemes/LinkBridgeIOS.xcscheme`
- Create: `ios/LinkBridgeIOS/Theme/Theme.swift`
- Create: `ios/LinkBridgeIOSTests/ThemeTests.swift`
- Modify: `ios/README.md` (Requirements: "iOS 26 or later"; layout section mentions the test target and the test command from Global Constraints)

**Interfaces:**
- Produces:
  - `enum Accent: CaseIterable { case action, signal, attention }` with `var lightHex: UInt32`, `var darkHex: UInt32`, `var color: Color` (dynamic light/dark via `UIColor { traits in … }`).
  - `extension View { func linkBridgeBackdrop() -> some View }` — fills behind content with system background plus cobalt at 6% light / 10% dark, ignoring safe areas.

- [ ] **Step 1: Add the test target.** Add a `com.apple.product-type.bundle.unit-test` target `LinkBridgeIOSTests` with a synchronized root group `LinkBridgeIOSTests`, `TEST_HOST = $(BUILT_PRODUCTS_DIR)/LinkBridgeIOS.app/LinkBridgeIOS`, `BUNDLE_LOADER = $(TEST_HOST)`, deployment target 26.0, a target dependency on `LinkBridgeIOS`, and `LinkBridgeCore` linked. Add the shared scheme with Build (app) and Test (`LinkBridgeIOSTests`) actions.
- [ ] **Step 2: Write the failing test** `ThemeTests.accentHexValuesMatchSpec`:
  ```swift
  @testable import LinkBridgeIOS
  import Testing
  @Test func accentHexValuesMatchSpec() {
      #expect(Accent.action.lightHex == 0x3E62E8 && Accent.action.darkHex == 0x7C95FF)
      #expect(Accent.signal.lightHex == 0x35C98B && Accent.signal.darkHex == 0x4ADBA0)
      #expect(Accent.attention.lightHex == 0xF08A5D && Accent.attention.darkHex == 0xFF9F78)
  }
  ```
- [ ] **Step 3: Run the test command.** Expected: FAIL — `Accent` not found (proves the test target builds and runs).
- [ ] **Step 4: Implement `Theme.swift`** per the Interfaces block; raise both deployment targets to 26.0.
- [ ] **Step 5: Run the test command.** Expected: PASS. Run the build command. Expected: `BUILD SUCCEEDED`.
- [ ] **Step 6: Commit** — `git add ios && git commit -m "iOS: target iOS 26, add test target and theme tokens"`

---

### Task 2: Device list and connection state

**Files:**
- Modify: `macos/Sources/LinkBridgeCore/PeerResolver.swift` (public memberwise `init` on `ResolvedPeer`)
- Modify: `macos/Sources/LinkBridgeCore/TrustedPeerStore.swift` (public memberwise `init` on `TrustedPeer`)
- Create: `ios/LinkBridgeIOS/Presentation/DeviceListItem.swift`, `ios/LinkBridgeIOS/Presentation/ConnectionState.swift`
- Test: `ios/LinkBridgeIOSTests/DeviceListItemTests.swift`, `ios/LinkBridgeIOSTests/ConnectionStateTests.swift`

**Interfaces:**
- Produces:
  ```swift
  enum DevicePlatform { case mac, android, iphone, unknown
      init(_ raw: String?)          // "macos"→.mac, "android"→.android, "ios"→.iphone
      var symbol: String }          // "laptopcomputer", "smartphone", "iphone", "questionmark.circle"
  struct DeviceListItem: Identifiable, Equatable {
      let id: String                // deviceId.uuidString, else peer.endpoint
      let deviceId: UUID?
      let name: String
      let platform: DevicePlatform
      let isPaired: Bool
      let isNearby: Bool
      let isSendable: Bool          // paired && nearby && secureEndpoint != nil
      let canPair: Bool             // nearby && !paired && platform == .mac && deviceId != nil
      let subtitle: String
      static func merge(nearby: [ResolvedPeer], trusted: [TrustedPeer],
                        lastSeen: [UUID: Date], now: Date) -> [DeviceListItem]
  }
  enum ConnectionState: Equatable {
      case searching, noPairedDevice
      case ready(deviceName: String)
      case sending(deviceName: String, progress: Double)
      case error(message: String)
      static func derive(nearbyCount: Int, selected: DeviceListItem?, sendPhase: SendPhase,
                         errorMessage: String?) -> ConnectionState
      var title: String             // device name, "Looking for devices…", "No paired device nearby", or the message
      var dotLabel: String          // "Connected" | "Searching" | "Error"
      var dotAccent: Accent?        // .signal for ready/sending, .attention for error, nil (grey) otherwise
  }
  ```
  `ConnectionState.derive` uses `SendPhase` from Task 3 — create `SendPhase.swift` with just the enum cases from Task 3's Interfaces here so this task compiles; Task 3 adds its behaviour.

- [ ] **Step 1: Add the two public inits in LinkBridgeCore.** Run core checks. Expected: all checks pass.
- [ ] **Step 2: Write failing tests** in `DeviceListItemTests`:
  - `orderIsNearbyPairedThenNearbyUnpairedThenOfflinePaired` — names sorted A→Z within each group.
  - `pairedNearbyWithTLSIsSendable` — subtitle `"Paired · Nearby"`, `isSendable == true`.
  - `pairedNearbyWithoutTLSNeedsUpdate` — `tlsPort: nil` → subtitle `"Paired · needs a LinkBridge update"`, `isSendable == false`. *(Review Focus 3)*
  - `peerWithoutDeviceIdUsesEndpointIdAndCannotPair` — `id == "10.0.0.5:49152"`, `canPair == false`. *(Review Focus 3)*
  - `unpairedMacCanPair` — subtitle `"Not paired"`, `canPair == true`.
  - `unpairedAndroidExplainsPairingDirection` — subtitle `"Pair from the Android app (coming soon)"`, `canPair == false`.
  - `offlinePairedShowsLastSeenOrPairedDate` — with `lastSeen` 300 s before `now`: subtitle starts with `"Last seen"`; without: subtitle starts with `"Paired "`; both `isNearby == false`, `isSendable == false`. *(Review Focus 2)*
- [ ] **Step 3: Write failing tests** in `ConnectionStateTests`, priority error > sending > ready > searching/noPaired:
  - `errorWinsOverEverything` → `.error(message:)`, `dotLabel == "Error"`, `dotAccent == .attention`.
  - `sendingShowsProgress` → `.sending(deviceName: "Mac", progress: 0.4)`, `dotLabel == "Connected"`.
  - `sendableSelectionIsReady` → `.ready(deviceName: "Mac")`, `dotAccent == .signal`.
  - `nothingNearbyIsSearching` → `.searching`, `title == "Looking for devices…"`, `dotLabel == "Searching"`, `dotAccent == nil`.
  - `nearbyButNoSendableSelectionIsNoPairedDevice` → `.noPairedDevice`, `title == "No paired device nearby"`.
- [ ] **Step 4: Run the test command.** Expected: FAIL (types missing).
- [ ] **Step 5: Implement** `DeviceListItem.swift` and `ConnectionState.swift`. "Last seen" uses `RelativeDateTimeFormatter` with `.abbreviated` units; "Paired" uses `pairedAt.formatted(date: .abbreviated, time: .omitted)`.
- [ ] **Step 6: Run the test command and core checks.** Expected: PASS.
- [ ] **Step 7: Commit** — `"iOS: device list and connection state presentation"`

---

### Task 3: Send phase, history rows and model wiring

**Files:**
- Modify: `ios/LinkBridgeIOS/Presentation/SendPhase.swift`
- Create: `ios/LinkBridgeIOS/Presentation/HistoryRowContent.swift`
- Modify: `ios/LinkBridgeIOS/LinkBridgeModel.swift`
- Test: `ios/LinkBridgeIOSTests/SendPhaseTests.swift`, `ios/LinkBridgeIOSTests/HistoryRowContentTests.swift`

**Interfaces:**
- Consumes: `DeviceListItem`, `ConnectionState` (Task 2).
- Produces:
  ```swift
  enum SendPhase: Equatable {
      case idle
      case ready(fileName: String)
      case sending(fileName: String, progress: Double)
      case sent(fileName: String)
      case failed(fileName: String, message: String)
      func isActionEnabled(targetSendable: Bool) -> Bool  // ready/failed && targetSendable
      var actionTitle: String       // idle "Choose a file", ready "Send", sending "Sending…", sent "Sent", failed "Retry"
      var actionSymbol: String      // "doc.badge.plus", "paperplane.fill", "arrow.up.circle", "checkmark", "arrow.clockwise"
  }
  struct TransferRecord           // existing; add `var bookmark: Data? = nil` (optional so old JSON decodes)
  struct HistoryRowContent {
      init(_ record: TransferRecord)
      let title: String             // file name
      let detail: String            // "To Mac · 1.2 MB · <abbreviated date, short time>"
      let symbol: String            // "arrow.up.circle.fill" | "exclamationmark.circle.fill"
      let accent: Accent            // .signal | .attention
      let failureReason: String?    // record.detail when failed
      let canRetry: Bool            // !succeeded && bookmark != nil
  }
  // LinkBridgeModel — replaces `progress` and `status`:
  @Published private(set) var sendPhase: SendPhase = .idle
  @Published private(set) var lastError: String?       // set on pairing/send failure; cleared by any new user action
  @Published private(set) var lastSeen: [UUID: Date]   // in-memory; updated on every browser change
  var deviceItems: [DeviceListItem] { get }            // DeviceListItem.merge(..., now: Date())
  var selectedItem: DeviceListItem? { get }
  var connectionState: ConnectionState { get }
  func select(_ item: DeviceListItem)                  // only if item.isSendable
  func choose(fileAt url: URL)                         // → .ready(fileName:)
  func sendSelectedFile()                              // .ready/.failed → .sending → .sent/.failed
  func retry(_ record: TransferRecord)                 // resolve bookmark, choose, send; stale/missing → lastError "That file is no longer available"
  // pair(with:), confirmPairing(), cancelPairing(), pairingPrompt, pairingPeerId — unchanged signatures
  ```

- [ ] **Step 1: Write failing tests** in `SendPhaseTests`:
  - `idleIsNeverEnabled`, `readyNeedsSendableTarget` (`false` when `targetSendable == false`), `sendingAndSentAreDisabled`, `failedOffersRetry` (`actionTitle == "Retry"`, enabled with a sendable target).
- [ ] **Step 2: Write failing tests** in `HistoryRowContentTests`:
  - `successfulRow` — `bytes: 1_200_000`, peer `"Mac"` → `detail` starts with `"To Mac · 1.2 MB · "`, `accent == .signal`, `canRetry == false`.
  - `failedRowWithBookmarkCanRetry` and `failedRowWithoutBookmarkCannotRetry` *(Review Focus 4)*.
  - `legacyHistoryJSONDecodes` — decode `[{"id":"…","fileName":"a.txt","peerName":"Mac","bytes":3,"date":0,"succeeded":true,"detail":null}]` (no `bookmark` key) into `[TransferRecord]`; `bookmark == nil`. *(Review Focus 1)*
- [ ] **Step 3: Run the test command.** Expected: FAIL.
- [ ] **Step 4: Implement** `SendPhase` behaviour and `HistoryRowContent`; add `bookmark` to `TransferRecord`.
- [ ] **Step 5: Rewire `LinkBridgeModel`** to the Interfaces block. `choose(fileAt:)` stores the URL and `url.bookmarkData()` (taken while security-scoped access is open). After `.sent`, return to `.idle` after 2.5 s unless a new file was chosen. Keep the existing `Task.detached` send path and 50-entry cap. The current `LinkBridgeIOSApp.swift` will stop compiling; replace its body with a placeholder `Text("LinkBridge")` root so the build passes until Task 4.
- [ ] **Step 6: Run the test command and build command.** Expected: PASS / `BUILD SUCCEEDED`.
- [ ] **Step 7: Commit** — `"iOS: send phase, history rows and model wiring"`

---

### Task 4: App frame and connection rail

**Files:**
- Modify: `ios/LinkBridgeIOS/LinkBridgeIOSApp.swift`
- Create: `ios/LinkBridgeIOS/Views/ConnectionRail.swift`, `ios/LinkBridgeIOS/Views/SettingsView.swift` (stub: `NavigationStack` with title "Settings" and a Done button)

**Interfaces:**
- Consumes: `LinkBridgeModel` (Task 3), `ConnectionState`, `Accent`, `linkBridgeBackdrop()`.
- Produces: `enum AppTab: Hashable { case devices, send, history }`; `struct ConnectionRail: View { let state: ConnectionState }`; `struct SettingsView: View { @ObservedObject var model: LinkBridgeModel }`.

- [ ] **Step 1: Build `RootView`:** `TabView` with `Tab("Devices", systemImage: "dot.radiowaves.left.and.right")`, `Tab("Send", systemImage: "paperplane")`, `Tab("History", systemImage: "clock.arrow.circlepath")` — system glass tab bar, no custom background. Each tab is a `NavigationStack` with `.linkBridgeBackdrop()`, the rail via `.safeAreaInset(edge: .top)`, and a toolbar gear button (`accessibilityLabel("Settings")`) presenting `SettingsView` as a sheet. Keep the scene-phase discovery start/stop and `.onAppear { model.startDiscovery() }`. Tabs show placeholder `Text` until Tasks 5–7.
- [ ] **Step 2: Build `ConnectionRail`:** capsule with `glassEffect(.regular, in: .capsule)`; 8 pt dot filled with `state.dotAccent?.color ?? .secondary`; `state.title` single line, `.truncationMode(.middle)`; `.sending` shows a thin progress bar under the title. The rail is one accessibility element: label `"\(state.dotLabel), \(state.title)"`.
- [ ] **Step 3: Build, launch in the simulator (`swiftui-liquid-glass` checks), screenshot each tab.** Expected: glass tab bar, rail floating over tinted backdrop, "Looking for devices…" with a grey dot when the Mac app isn't running.
- [ ] **Step 4: Commit** — `"iOS: tab frame and glass connection rail"`

---

### Task 5: Devices tab and pairing sheet

**Files:**
- Create: `ios/LinkBridgeIOS/Views/DevicesView.swift`, `ios/LinkBridgeIOS/Views/PairingSheet.swift`
- Modify: `ios/LinkBridgeIOS/LinkBridgeIOSApp.swift` (Devices tab content; pairing sheet presentation)

**Interfaces:**
- Consumes: `model.deviceItems`, `model.select(_:)`, `model.pair(with:)` (needs the `ResolvedPeer`: look it up in `model.nearby` by `item.deviceId`), `model.pairingPeerId`, `model.pairingPrompt`, `confirmPairing()`, `cancelPairing()`.
- Produces: `struct DevicesView: View`, `struct PairingSheet: View { let prompt: PairingPrompt; let onConfirm: () -> Void; let onCancel: () -> Void }`.

- [ ] **Step 1: `DevicesView`** — solid inset-grouped `List` (no glass). Row: platform symbol, name, subtitle; trailing: checkmark when selected, `Button("Pair")` (`.buttonStyle(.glass)`) when `canPair`, `ProgressView` while `pairingPeerId == item.deviceId`. Tapping a sendable row selects it. Offline rows use `.foregroundStyle(.secondary)` and are not tappable. Empty: `ContentUnavailableView("No devices yet", systemImage: "wifi", description: Text("Make sure both devices are on the same Wi-Fi and LinkBridge is open."))`.
- [ ] **Step 2: `PairingSheet`** — presented with `.sheet(item: $model.pairingPrompt)`, `.presentationDetents([.medium])` (system glass sheet). Content: "Pair with \(peerName)?", the code in `.system(size: 44, weight: .semibold, design: .monospaced).monospacedDigit()`, "Check that \(peerName) shows the same code.", primary `Button("Codes match")` `.buttonStyle(.glassProminent)` tinted `Accent.action.color`, secondary `Button("Cancel", role: .cancel)`. Swiping the sheet down calls `onCancel`.
- [ ] **Step 3: Build, run, verify against the real Mac app:** pair from the Devices tab; the sheet shows the same code as the Mac; after Mac approval the row becomes "Paired · Nearby" and selected, rail turns mint. Screenshot the empty, list and sheet states.
- [ ] **Step 4: Commit** — `"iOS: devices tab and glass pairing sheet"`

---

### Task 6: Send tab and morphing Send control

**Files:**
- Create: `ios/LinkBridgeIOS/Views/SendView.swift`, `ios/LinkBridgeIOS/Views/SendControl.swift`
- Modify: `ios/LinkBridgeIOS/LinkBridgeIOSApp.swift` (Send tab content)

**Interfaces:**
- Consumes: `model.sendPhase`, `model.selectedItem`, `choose(fileAt:)`, `sendSelectedFile()`, `SendPhase.actionTitle/actionSymbol/isActionEnabled`.
- Produces: `struct SendView: View`, `struct SendControl: View { let phase: SendPhase; let enabled: Bool; let action: () -> Void }`.

- [ ] **Step 1: `SendView`** — solid rounded drop zone (system secondary background, dashed border when empty) showing either "Choose or drop a file" or the chosen file name + size. Tapping it opens `.fileImporter(allowedContentTypes: [.item])`; `.dropDestination(for: URL.self)` also calls `choose(fileAt:)`. Above it: "To \(selectedItem.name)" or, with no sendable selection, a `ContentUnavailableView` "Pick a paired device" with a button that switches to the Devices tab.
- [ ] **Step 2: `SendControl`** — floating at the bottom inside one `GlassEffectContainer`. `.idle`/`.ready`/`.failed`: capsule `Button` with `actionSymbol` + `actionTitle`, `.buttonStyle(.glassProminent)`, tinted `Accent.action.color` (`.failed` tinted `Accent.attention.color`). `.sending`: wider capsule with a progress bar and percentage, `glassEffect(.regular)`. `.sent`: checkmark capsule tinted `Accent.signal.color`. All phases share one `glassEffectID("send", in: namespace)` so changes morph; wrap phase changes in `withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : .bouncy)` and use `.transition(.opacity)` when `@Environment(\.accessibilityReduceMotion)` is true. In `.idle` the action opens the file importer; otherwise it calls `sendSelectedFile()`. Accessibility value during sending: "\(Int(progress * 100)) percent".
- [ ] **Step 3: Build, run, send a file to the real Mac.** Expected: button morphs Send → progress pill → check → back to Choose a file; the file arrives in `~/Downloads/LinkBridge` with matching SHA-256 (`shasum -a 256`). Stop the Mac app mid-send: the control turns coral "Retry" and the rail shows the error. Screenshot each phase.
- [ ] **Step 4: Commit** — `"iOS: send tab with morphing glass send control"`

---

### Task 7: History tab and Settings

**Files:**
- Create: `ios/LinkBridgeIOS/Views/HistoryView.swift`
- Modify: `ios/LinkBridgeIOS/Views/SettingsView.swift`, `ios/LinkBridgeIOS/LinkBridgeIOSApp.swift`

**Interfaces:**
- Consumes: `model.history`, `HistoryRowContent`, `model.retry(_:)`, `model.identity.deviceName`, `model.trusted`.

- [ ] **Step 1: `HistoryView`** — solid `List` of all history (max 50). Row: `symbol` in `accent.color`, title (1 line, middle truncation), detail (caption, secondary), `failureReason` (caption, attention colour) when present. `canRetry` rows get a trailing `Button("Retry")` and a leading swipe action "Retry". Empty: `ContentUnavailableView("No transfers yet", systemImage: "clock", description: Text("Files you send will appear here."))`.
- [ ] **Step 2: `SettingsView`** — inset-grouped `Form`: section "This iPhone" (device name); section "Paired devices" (name + platform symbol + "Paired <date>"; empty text "No paired devices"); section "About" (app version from `CFBundleShortVersionString`). Done button dismisses.
- [ ] **Step 3: Build, run, verify** history shows the sends from Task 6, Retry on a failed row re-sends the same file, Retry after deleting the source file shows "That file is no longer available" in the rail *(Review Focus 4)*. Screenshot history (empty and filled) and Settings.
- [ ] **Step 4: Commit** — `"iOS: history tab and settings"`

---

### Task 8: Accessibility pass, verification matrix and docs

**Files:**
- Modify: any view from Tasks 4–7 that fails a check below
- Modify: `DESIGN.md` (replace with a short pointer: "The design language lives in `docs/superpowers/specs/2026-10-17-liquid-glass-design-language-design.md`." plus the token table)
- Modify: `ios/README.md` ("What works" describes the new tabs)

- [ ] **Step 1: Screenshot matrix** in the simulator for every state (empty, searching, ready, sending, sent, failed, pairing sheet, history empty/filled, settings) × light, dark, Reduce Transparency, largest accessibility text size (`xcrun simctl ui booted content_size accessibility-extra-extra-extra-large`, `xcrun simctl ui booted appearance dark`). Save to `docs/screenshots/ios/`. Expected: glass turns opaque under Reduce Transparency; nothing clips at the largest size; a 60-character device name truncates in the rail and wraps in rows *(Review Focus 5)*.
- [ ] **Step 2: Contrast check** — rail title and Send label over the backdrop in both modes measure ≥ 4.5:1 (Xcode Accessibility Inspector audit). Adjust the dark accent hex values in `Theme.swift` and `ThemeTests` together if they fail.
- [ ] **Step 3: VoiceOver run** — with VoiceOver on, pair with the Mac and send a file using only swipes and double-taps. Every control announces a label; the rail announces "Connected, <Mac name>".
- [ ] **Step 4: Run the test command and build command.** Expected: PASS / `BUILD SUCCEEDED`.
- [ ] **Step 5: Commit** — `"iOS: accessibility pass, screenshots and docs for the redesign"`
