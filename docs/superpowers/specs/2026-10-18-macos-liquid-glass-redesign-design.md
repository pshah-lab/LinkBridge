# LinkBridge redesign — Part 3: macOS

Status: draft for review · 2026-10-18

Builds on [Part 1: design language](2026-10-17-liquid-glass-design-language-design.md)
and the finished iOS app (Part 2, `ios/LinkBridgeIOS/`).

## Goal

Replace the AppKit-in-code Mac UI (`macos/Sources/LinkBridge/MacGuiApp.swift`) with a
SwiftUI app using real Liquid Glass on macOS 26: a `MenuBarExtra` popover for quick
sends and approvals, and a `NavigationSplitView` window. Behaviour stays the same:
the Mac is the pairing responder, sends to paired devices, and receives files only
with explicit approval.

Out of scope: protocol changes, cancelling a transfer in flight, multi-file send
queues, screen mirroring.

## Decisions

| Decision | Choice |
|---|---|
| Minimum OS | macOS 26 (and iOS 26 for the shared package). No fallback path. |
| App presence | Dock icon and menu bar extra are both always present. Closing the window does not quit; receiving keeps working. |
| Approval UX | System notification + coral menu bar icon. Files can be accepted/declined from the notification; pairing offers only "Review" (the code must be compared in the app). The same request appears inline in the popover and as a sheet in the window (when key). Answering anywhere resolves it everywhere. |
| Transfer queue | No queue view. Active and waiting transfers are live rows pinned above finished rows (History, and the popover's last 3). Waiting rows have Decline; running rows have no cancel. Sends stay one at a time. |
| Shared code | New Foundation-only `LinkBridgePresentation` target used by the Mac app, the iOS app and `LinkBridgeCoreChecks`. Views stay per platform. |

## Architecture

### Package (`macos/Package.swift`)

- Platforms: `.macOS(.v26)`, `.iOS(.v26)`; tools version 6.2 (required for `.v26`) in Swift 5 language mode.
- New target `LinkBridgePresentation` (depends on `LinkBridgeCore`, imports only
  Foundation) and a matching library product.
- `LinkBridge` (Mac app) depends on Core + Presentation.
- `LinkBridgeCoreChecks` depends on Core + Presentation.
- The iOS Xcode project adds the `LinkBridgePresentation` product from the existing
  local package reference (`../macos`).

### Shared presentation module

Moved from `ios/LinkBridgeIOS/Presentation/` and `Theme/`:

- `ConnectionState`, `SendPhase`, `DevicePlatform`, `DeviceListItem`,
  `DeviceSelection`, `DroppedFile`, `HistoryRowContent`.
- `TransferRecord` (from `LinkBridgeModel.swift`), extended with
  `direction: TransferDirection` (`.sent` / `.received`), defaulting to `.sent` when
  decoding so existing iOS history still loads.
- `Accent` with its hex values, `onLightHex`/`onDarkHex`, and `contrastRatio`.
  The SwiftUI `Color` bridge stays per platform (`Accent+Color.swift` in each app:
  `UIColor` on iOS, `NSColor` on macOS), as does the backdrop modifier.
- `HistoryRowContent` gains direction-aware text and symbol:
  "To Pixel 8" / `arrow.up.circle.fill` vs "From Pixel 8" / `arrow.down.circle.fill`,
  and `canShowInFinder` for received, succeeded records with a file URL.
- New `ApprovalQueue` value type: an ordered list of pending requests
  (`.pair(IncomingPairRequest, expiresAt)` / `.file(IncomingTransferRequest)`) with
  `add`, `resolve(id:)`, `expire(now:)`, and `oldest`. Pure, so the checks test it.

All types become `public` where the apps need them. The iOS XCTests switch their
imports to `LinkBridgePresentation`; behaviour is unchanged.

### Core change (additive)

`ControlServer` gains a completion-based initializer:

```swift
pairApproval: @escaping (IncomingPairRequest, @escaping (Bool) -> Void) -> Void
transferApproval: @escaping (IncomingTransferRequest, @escaping (Bool) -> Void) -> Void
```

- Pairing: the server calls `pairApproval` on the main queue and records the decision
  when the completion fires, instead of blocking main on a `Bool`. A completion after
  the 3-minute expiry is ignored.
- Transfers: the receive thread waits on a semaphore for the completion (it blocks
  today too, just inside the closure). The completion may be called from any thread;
  only the first call counts.
- The existing `Bool` initializer stays and wraps the new one, so `linkbridge-cli`
  and existing checks compile unchanged.
- `IncomingPairRequest` / `IncomingTransferRequest` gain a stable `id: UUID` so the UI
  and notifications can refer to one request; `IncomingPairRequest` also exposes
  `expiresAt` for the countdown.
- An unanswered incoming file is declined after 300 s and the app is told
  (`transferWithdrawn`) so its row disappears.
- The completion for a received file includes its final URL (for Show in Finder).
- `TrustedPeerStore.remove(deviceId:)` backs Unpair in Settings.

### Mac app (`macos/Sources/LinkBridge/`)

`MacGuiApp.swift` and `main.swift` are deleted. New files, each with one job:

| File | Role |
|---|---|
| `LinkBridgeApp.swift` | `@main` SwiftUI `App`: `Window("LinkBridge", id: "main")`, `MenuBarExtra(style: .window)`, `Settings` not used (settings live in the sidebar). Activation policy `.regular`. `applicationShouldTerminateAfterLastWindowClosed` → `false`. |
| `MacModel.swift` | `@Observable @MainActor`. Owns `ControlServer`, `PeerBrowser`, `TrustedPeerStore`, `IdentityStore`, history store, `ApprovalQueue`, send pipeline, receive progress. Single source of truth for both surfaces. |
| `HistoryStore.swift` | Loads/saves `[TransferRecord]` (cap 200). On first run migrates `linkbridge.transfer-history` (`MacTransferHistoryEntry`) into `TransferRecord` and removes the old key. |
| `NotificationController.swift` | `UNUserNotificationCenter` wrapper. Categories: `FILE` (Accept, Decline) and `PAIR` (Review). Posts on new requests, withdraws on resolve/expiry, routes actions back to `MacModel.resolve`. "Review" opens the window. |
| `Theme/Accent+Color.swift`, `Theme/Backdrop.swift` | `NSColor` bridge and cobalt-tinted backdrop. |
| `Views/MenuBarContent.swift` | Popover layout. |
| `Views/MainWindow.swift` | `NavigationSplitView` + sidebar. |
| `Views/ConnectionRail.swift` | Glass capsule: state dot + name (+ device picker menu). |
| `Views/SendSurface.swift`, `Views/SendControl.swift` | Drop zone and morphing glass Send control (window). |
| `Views/CompactDropTarget.swift` | Popover drop target. |
| `Views/ApprovalRow.swift`, `Views/ApprovalSheet.swift` | Inline and sheet approval UIs sharing one content view. |
| `Views/TransferRow.swift`, `Views/HistoryView.swift` | Live + finished rows. |
| `Views/DevicesView.swift`, `Views/SettingsView.swift` | Sidebar destinations. |

Discovery switches from one-shot `PeerResolver.resolveAndroidPeer` + Refresh button to
live `PeerBrowser`, merged with trusted peers via `DeviceListItem.merge`; the send
target follows `DeviceSelection.resolve`, as on iOS. The selected device ID persists in
`UserDefaults`.

Testing seam: `LinkBridgeCoreChecks` cannot import the executable Mac target, so all
testable logic (approval queue and resolve-once rules in `ApprovalCenter`, `HistoryStore`
and its migration) lives in `LinkBridgePresentation`; `MacModel` stays thin wiring,
verified by the manual pass. Checks keep their throwaway-home isolation and never touch
the login keychain.

## Screens

### Menu bar icon

Template glyph (`arrow.left.arrow.right.circle`). While any approval is pending it
switches to the filled variant tinted coral, and the accessibility label says
"LinkBridge — 1 request waiting".

### Popover (≈340 pt wide)

Top to bottom:

1. **Connection rail** — glass capsule in a `GlassEffectContainer`: state dot, selected
   device name (truncates), and a chevron menu to switch device when more than one is
   sendable. State from `ConnectionState.derive`.
2. **Pending approvals** — inline rows, coral-tinted glass, oldest first:
   - Pair: "Pair with Pixel 8?", the 6-digit code in large monospaced digits,
     "Only accept if your phone shows the same code.", the replace-existing warning when
     `replacesExisting`, a countdown to expiry, Accept / Decline.
   - File: file name, size, "from Pixel 8", Accept / Decline.
3. **Compact drop target** — solid, ~64 pt: "Drop a file to send to Pixel 8" or click
   to choose. A drop or pick sends immediately (popover is the quick path). Disabled
   with an explanatory line when nothing is sendable.
4. **Recent** — at most 3 rows: live rows first (progress bar, or "Waiting for you" for
   pending files), then finished rows. Received → Show in Finder; failed send → Retry.
5. **Footer** — "Open LinkBridge" (opens/focuses the window) and Quit.

### Window

`NavigationSplitView`, glass toolbar holding the connection rail, cobalt backdrop behind
the detail pane.

- **Sidebar**: Devices (section listing `DeviceListItem`s: icon, name, subtitle, paired
  badge), History, Settings.
- **Device detail** (default selection = the send target):
  - Sendable: large solid drop zone filling the area, click to choose, plus one glass
    Send control floating at the bottom that morphs through `SendPhase`
    (Choose a file → Send → progress pill → checkmark / Retry) with `glassEffectID`.
    Dropping or choosing a file stages it (`ready`); Send starts it.
  - Not sendable: one sentence from `DeviceListItem.subtitle` and no Send control
    ("Pair from the Android app", "Last seen 5 min ago — open LinkBridge on it").
- **History**: live rows pinned at top, then finished rows grouped by day. Row actions:
  Show in Finder, Retry, Remove (context menu).
- **Settings**: Mac name and device ID with Copy; receive folder (Downloads) with Open in Finder;
  paired devices with Unpair (confirmation); toggle "Notify me about incoming
  files and pairing requests" (default on); hint line when notification permission is
  denied.
- **Approval sheet**: shown for `ApprovalQueue.oldest` while the window is key. Same
  content as the popover row. Return = Accept, Escape = Decline.

### Empty and error states (one sentence + one action)

| State | Text | Action |
|---|---|---|
| No devices | "No devices yet — open LinkBridge on your phone on the same Wi-Fi." | — |
| Receiver failed to start | "LinkBridge can't receive — allow it in Privacy & Security › Local Network." | Open Settings |
| No history | "Files you send and receive will appear here." | — |
| Failed send | "Couldn't reach Pixel 8 — check it's on the same Wi-Fi, then retry." | Retry |

## Behaviour details

- **One request, many surfaces**: every surface calls `MacModel.resolve(id:approved:)`;
  the model calls the server completion once, removes the request from the queue, and
  withdraws its notification.
- **Expiry**: a 1-second timer while pairings are pending drives the countdown; an
  expired pairing becomes a "Pairing request expired" row for 5 seconds, its
  notification is withdrawn, and the server's late completion is ignored.
- **Several pending**: stacked in arrival order; the sheet shows one at a time.
- **Quit with pending requests**: all are declined before exit.
- **Notifications off or denied**: approvals still work through popover and window.
- **Received files** land in `~/Downloads`, as today.
- **Sends**: one at a time; while one runs, drop targets show "Sending…" and refuse new
  files. Failed sends keep a bookmark for Retry.

## Motion and accessibility

Per Part 1. Specifically for macOS: Send control morph with `glassEffectID`; Reduce Motion
→ cross-fades; Reduce Transparency → opaque glass, verified per screen; full keyboard
navigation in window and popover (Tab order: rail → approvals → drop target → rows →
footer); every control and state dot labelled for VoiceOver; pairing code read digit by
digit.

## Testing

- `LinkBridgeCoreChecks` (run with
  `swift run --build-system native LinkBridgeCoreChecks` from `macos/`) gains:
  - Ported presentation checks (ConnectionState, SendPhase, DeviceListItem merge and
    selection, HistoryRowContent incl. received rows, Accent contrast).
  - `ApprovalQueue` add / resolve / expire / ordering.
  - Completion-based approvals against a real `ControlServer` on loopback in the
    checks' isolated home: accept, decline, late completion after expiry ignored, first
    completion wins; the `Bool` initializer still works.
  - Mac history migration from the old `MacTransferHistoryEntry` JSON.
- iOS: `LinkBridgeIOSTests` pass after switching imports; the app builds for iOS 26.
- Manual (Part 1 verification list): screenshots of popover and window in every state —
  light, dark, Reduce Transparency, largest text — in `docs/screenshots/macos/`; real
  pairing, send both ways and an accepted incoming file with the Android phone;
  VoiceOver completes pair and send.
- Build with `swift build --build-system native`. Fresh binaries trigger a
  login-keychain prompt, so only the manual pass runs the real app.

## Done when

- The Mac app is the SwiftUI app above; `MacGuiApp.swift` is gone.
- Checks pass, the iOS app builds and its tests pass on the raised platform.
- Screenshots and the real-device run are attached to the PR.
- `DESIGN.md` links macOS screenshots.
