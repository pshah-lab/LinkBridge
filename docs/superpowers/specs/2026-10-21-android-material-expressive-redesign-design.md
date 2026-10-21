# LinkBridge redesign — Part 4: Android

Status: draft for review · 2026-10-21

Builds on [Part 1: design language](2026-10-17-liquid-glass-design-language-design.md),
with [Part 3: macOS](2026-10-18-macos-liquid-glass-redesign-design.md) and
`macos/Sources/LinkBridgePresentation/` as the reference for state and copy parity.

## Goal

Replace the hand-built View UI (`MainActivity.kt`, `ShareActivity.kt`, `Ui.kt`) with
Jetpack Compose, Material 3 Expressive and Haze-blurred glass surfaces: a floating glass
nav bar (Devices · Send · History), Settings in the top app bar, a connection chip, an
Expressive Send FAB, and bottom sheets for pairing and incoming-file approval.

Behaviour stays the same:

- Protocol v3 pairing: the phone initiates, shows the 6-digit code, and the Mac user types
  it. Same two steps as today: begin → show code → Continue → finalize.
- Tag-based discovery, TLS-PSK transfers, the share-sheet target and its intent validation.
- `LinkBridgeService` owns discovery, receiving and sending, runs as a `connectedDevice`
  foreground service, and keeps running when the UI is gone.
- Timeouts: `/pair/finalize` read timeout and the upload socket timeout stay **120 s**,
  because the Mac gives up at 110 s and relies on them staying above that. The
  incoming-file approval timeout stays 60 s.
- Minimum Android 12 (API 31), target 36.

Out of scope: protocol changes, cancelling a transfer in flight, multi-file send queues
in the main app (the share sheet keeps its existing multi-file behaviour), screen mirroring,
removing the Continue step from pairing.

## Decisions

| Decision | Choice |
|---|---|
| Migration | Full rewrite in one PR. Both activities become Compose; `Ui.kt` and the View code are deleted. Networking and the service are only touched where the UI needs typed data (below). |
| Blur fallback | Follow the system. Blur only when `WindowManager.isCrossWindowBlurEnabled` is true (it is false with battery saver, on low-end GPUs, and with the developer toggle off), and update live through `addCrossWindowBlurEnabledListener`. Otherwise glass draws an opaque tonal surface. No in-app switch. |
| Approval UX | In the app: an M3 bottom sheet over a blurred scrim, oldest request first. In the background: the existing high-priority notification with Accept/Decline. Waiting files also show as live rows in History. Answering anywhere resolves the request everywhere. |
| Receive service in UI | The connection chip shows state. A banner on Devices appears when receiving is stopped or failed, or notifications are off, with one action. Incoming files appear as live rows in History. Settings shows "Receiving" on/off. |
| Presentation logic | A pure-Kotlin `presentation` package mirrors `LinkBridgePresentation` and is covered by JVM unit tests. Composables stay thin. |

## Architecture

### Build (`android/app/build.gradle.kts`, root `build.gradle.kts`)

- Add the Kotlin Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`), with a
  version matching the Kotlin version bundled in AGP 9.4, and `buildFeatures { compose = true }`.
- Dependencies: Compose BOM, `material3` (a release with the Expressive APIs:
  `MaterialExpressiveTheme`, `MotionScheme.expressive()`, `FloatingToolbar` /
  short nav bar, `LoadingIndicator`), `ui-tooling-preview` (+ `ui-tooling` debug),
  `activity-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`,
  `material-icons-extended` (or the needed icons copied as vectors),
  `dev.chrisbanes.haze:haze` (1.x).
- Pin exact, released versions in the PR; the release build (R8) must pass.
- `styles.xml`: `AppTheme` becomes a no-action-bar Material3 DayNight parent (for the
  window background before Compose draws). `ShareActivity` gets a translucent variant.

### Packages (`com.linkbridge.android`)

Existing networking files stay where they are. New code:

| Path | Role |
|---|---|
| `presentation/ConnectionState.kt` | `Searching`, `NoPairedDevice`, `Ready(name)`, `Sending(name, progress)`, `Error(message)`; `derive(nearbyCount, selected, sendPhase, errorMessage)`; `title`, `dotLabel`, `accent`. Same rules and copy as the Mac. |
| `presentation/SendPhase.kt` | `Idle`, `Ready(fileName)`, `Sending(fileName, progress)`, `Sent(fileName)`, `Failed(fileName, message)`; `actionTitle` ("Choose a file" / "Send" / "Sending…" / "Sent" / "Retry"), `isEnabled(targetSendable)`. |
| `presentation/DeviceListItem.kt` | `DevicePlatform`, `DeviceListItem`, `merge(nearby, trusted)` and `DeviceSelection.resolve(current, preferred, items, isSending)`. Order: nearby paired, nearby unpaired, paired away; A→Z in each group. |
| `presentation/HistoryRowContent.kt` | Title, detail ("To Mac mini · 2.1 MB · 21 Oct, 14:05" / "From …"), direction, accent, `failureReason`, `canRetry`, `canOpen`. |
| `presentation/ApprovalQueue.kt` | Ordered pending file requests with `add`, `resolve(id)`, `expire(now)`, `oldest`. |
| `presentation/ReceiveState.kt` | `Starting`, `Listening`, `Receiving(fileName, received, total)`, `Stopped`, `Failed(message)`; `bannerText` and `bannerAction` where relevant. |
| `ui/theme/` | `LinkBridgeTheme` (dynamic colour on; cobalt light/dark schemes as fallback; `MaterialExpressiveTheme` with `MotionScheme.expressive()`), `Accents` (mint, coral, cobalt as a `CompositionLocal` with on-colours from `DESIGN.md`), `Backdrop` (cobalt 6% light / 10% dark over `background`). |
| `ui/glass/` | `BlurAvailability` (a state holder over the WindowManager signal), `LocalHazeState`, `Modifier.glassSurface(shape, tint)`. |
| `ui/` screens | `LinkBridgeApp` (scaffold, top bar, nav bar, sheet host), `DevicesScreen`, `SendScreen`, `HistoryScreen`, `SettingsScreen`. |
| `ui/` components | `ConnectionChip`, `GlassNavBar`, `SendFab`, `DeviceRow`, `HistoryRow`, `LiveTransferRow`, `StatusBanner`, `ApprovalSheet`, `PairingSheet`, `EmptyState`. |
| `MainViewModel.kt` | `AndroidViewModel`. Collects hub flows, reads the stores, runs the pairing flow on `Dispatchers.IO` through `ControlClient`, and exposes `StateFlow<UiState>` plus intents (`select`, `pair`, `continuePairing`, `cancelPairing`, `stage(uri)`, `send`, `retry`, `resolveApproval`, `unpair`, `rescan`, `setReceiving`). |
| `ShareViewModel.kt` | Share-sheet state: sendable targets, per-file progress, finished count. |

### Hub and service changes (UI-facing, behaviour unchanged)

`LinkBridgeHub` moves from listener callbacks to `StateFlow`s, which Compose collects with
lifecycle awareness:

- `peers: StateFlow<List<DiscoveredPeer>>` (as today).
- `receiveState: StateFlow<ReceiveState>`, replacing the free-text `receiveStatus`.
  `ReceiveServer`'s `onStatus(String)` is replaced by typed callbacks: listening, receiving
  progress, failed. The service maps these, plus its own start and stop, to `ReceiveState`.
- `sendState: StateFlow<SendState?>` (the existing sealed type).
- `pendingApprovals: StateFlow<List<PendingApproval>>`, where `PendingApproval` is `id`,
  `IncomingTransferRequest` and `expiresAt`.
- `historyVersion` (a counter bumped on every history write), so screens reload the store.
- `resolveApproval(id, accept)`: the one path used by the sheet, the notification
  actions and the timeout. The first call wins and later calls are ignored. It removes the
  request from `pendingApprovals` and cancels the request's notification.
- The `foregroundApprover` callback is replaced by `uiVisible: Boolean`, which
  `MainActivity` sets in `onStart` and clears in `onStop`. `requestApproval` always adds to
  `pendingApprovals`, and also posts the notification when the UI is not visible. If the UI
  is not visible and notifications are not allowed, it declines immediately and sets
  `ReceiveState` so the banner explains why (same outcome as today).
- If the user opens the app while a notification-routed request is still pending, the sheet
  shows it too.
- After 60 s the service resolves the request as declined (as today), so the sheet and the
  live row disappear.

Data additions:

- `TransferHistoryEntry` gains `succeeded: Boolean = true`, `detail: String? = null` and
  `contentUri: String? = null`. Old JSON loads with these defaults, and the cap stays 50.
- Failed sends are now recorded, with the error as `detail`. The URI is stored only when the
  app holds a persistable read grant (files picked in-app via `OpenDocument` +
  `takePersistableUriPermission`; share-sheet URIs are not persistable, so their failures
  have no Retry).
- Received entries store the MediaStore URI that `ReceiveServer` already creates. The
  completion callback passes it through.
- JSON parsing moves into a pure function (`TransferHistoryCodec`) so it can be tested
  without Android.
- `TrustedPeer` gains optional `pairedAt: Long?`; old entries load with null.
  `TrustedPeerStore.remove(deviceId)` backs Unpair and triggers a rescan, which
  re-advertises the discovery tags.

Timeouts move to named `internal const val`s (`PAIR_FINALIZE_READ_TIMEOUT_MS`,
`UPLOAD_SOCKET_TIMEOUT_MS`, `APPROVAL_TIMEOUT_SECONDS`) so a test can guard them. Their
values do not change.

## Screens

### Frame (`MainActivity` → `LinkBridgeApp`)

- `enableEdgeToEdge()`. The content draws behind the system bars on the cobalt backdrop and
  is the `hazeSource`.
- **Top app bar** (transparent, glass when content scrolls under it): `ConnectionChip` on
  the left; Settings icon button on the right.
- **Floating glass nav bar** at the bottom, above the gesture inset: Devices · Send ·
  History, with icon and label. The Send FAB floats next to it on the Send tab.
- Devices is the start tab. If a sendable device exists, the app opens on Send instead.
- Settings is a full-screen destination with a back arrow and no nav bar. The system back
  gesture returns to the tab.
- `ApprovalSheet` is hosted at the app root, so it appears over any tab.

### Connection chip

State dot (mint `Ready`/`Sending`, grey `Searching`/`NoPairedDevice`, coral `Error`) and
`ConnectionState.title`, truncated to one line. When `Sending`, a thin progress ring
replaces the dot. Tapping it opens Devices. TalkBack reads "<dotLabel>, <title>".

### Devices

- `DeviceRow` (M3 `ListItem`): platform icon, name, subtitle, "Paired" badge.
  Subtitles:
  - nearby + paired: "Paired · Nearby"
  - nearby + unpaired Mac: "Not paired" with a trailing **Pair** button
  - other unpaired: "Pair from the Mac app"
  - no device ID: "Needs a LinkBridge update"
  - paired + away: "Paired · Not nearby"
- Tapping a sendable row selects it (persisted in preferences) and switches to Send. Tapping
  an unpaired Mac opens `PairingSheet`.
- Pull-to-refresh calls `rescan()`. It replaces the Refresh button.
- `StatusBanner` at the top, at most one, in priority order:
  1. "LinkBridge isn't receiving — tap to start." → Start (starts the service).
  2. "Receiving failed: <reason>" → Retry (restarts the service).
  3. "Notifications are off, so files sent while LinkBridge is in the background are
     declined." → Turn on (permission request, or app settings if permanently denied;
     same logic as today).
- Empty: "No devices yet — open LinkBridge on your Mac on the same Wi-Fi."

### Pairing sheet

Modal bottom sheet, not dismissible by swipe while finalize is running. States:

1. **Connecting** — "Connecting to <Mac>…" with an Expressive `LoadingIndicator`. Runs
   `fetchDevice`, then `beginPairing`.
2. **Code** — "Type this code on <Mac>". The code is shown in large monospaced digits
   (`PairingCode.display`), and TalkBack reads it digit by digit. Below it: "If the Mac
   won't accept the code, cancel — someone may be intercepting the connection." Buttons:
   Continue (primary) and Cancel.
3. **Waiting** — "Waiting for you to confirm on <Mac>…" with Cancel. Cancel abandons the
   request on the phone; the finalize thread is left to time out at 120 s and its result is
   ignored.
4. **Paired** — checkmark, "Paired with <Mac>". The sheet closes after a moment, the device
   becomes the send target, and the app switches to Send.
5. **Failed** — coral, "Couldn't pair with <Mac>: <reason>", with Try again and Close.

### Send

- No sendable target: an `EmptyState` from the Devices subtitle logic ("Pair a Mac to send
  files" → Go to Devices) and no FAB.
- Sendable: a large solid pick zone ("Choose a file to send to <Mac>", with an icon).
  Tapping it opens `OpenDocument`. A picked file stages (`SendPhase.Ready`) and shows its
  name and size.
- **Send FAB**: an Expressive extended FAB on glass that morphs through `SendPhase`:
  - Idle: "Choose a file"
  - Ready: "Send"
  - Sending: a progress pill showing percent and file name
  - Sent: checkmark "Sent". It returns to Idle after 2 s.
  - Failed: coral "Retry", with the message shown under the pick zone ("Couldn't reach
    <Mac> — check it's on the same Wi-Fi, then retry.").
  - The morph uses a shared-bounds transform. With animations off (`ANIMATOR_DURATION_SCALE
    == 0`) it is a cross-fade.
- Only one send at a time. While sending, the pick zone shows "Sending…" and is disabled.

### History

- Live rows pinned at the top:
  - Sending: progress.
  - Waiting for you: an incoming file awaiting approval, with Accept/Decline inline.
  - Receiving: progress, or indeterminate when the total is unknown.
- Finished rows grouped by day (Today, Yesterday, date), built from `HistoryRowContent`:
  - direction icon (↑ sent, ↓ received, ! failed)
  - title and detail
  - the failure reason on failed rows
- Row actions:
  - Open on received rows: `ACTION_VIEW` on the stored URI. If no app can open it, a
    snackbar says so.
  - Retry on failed sends that have a readable stored URI.
- Empty: "Files you send and receive will appear here."

### Settings

- **This phone**: device name and device ID, with Copy (snackbar "Device ID copied").
- **Receiving**: switch. On means the service is running. Off sends `ACTION_STOP`, the same
  as the notification's Stop. Below it: "Files are saved to Downloads/LinkBridge."
- **Notifications**: status row; when off, Turn on (same permission logic).
- **Paired devices**: one row each with Unpair. Unpair asks first: "Unpair <Mac>? You'll
  need to pair again to send files."
- **About**: version.

### Approval sheet

Shown for the oldest entry in `pendingApprovals`, over a blurred scrim:

- sender, file name and size
- countdown "Declines in 42 s"
- Accept (primary) and Decline

Dismissing the sheet by swipe or back counts as Decline (as today's dialog cancel does).
If more requests are queued, the sheet shows the next one, with "1 more waiting".

### Share sheet (`ShareActivity`)

Translucent activity showing a Compose bottom sheet:

- Header: "Send N files".
- Body: list of sendable devices. If there are none: "Pair a Mac in LinkBridge first" →
  Open LinkBridge.
- Tapping a device starts sending through the service (as today). Progress shows "Sending
  2 of 3 — photo.jpg".
- When done: "Sent N files to <Mac>".
- Failure: coral, with the message.
- Hide: moves the task to the back while sending, otherwise finishes.

Intent parsing and validation (`sharedUris`, own-authority rejection) are unchanged.

## Glass

- Only these are glass: top app bar (when scrolled), nav bar, connection chip, Send FAB,
  and the scrims behind the sheets. Lists, rows, the pick zone and body text stay solid
  (Part 1 rule).
- `Modifier.glassSurface(shape, tint = null)`:
  - Blur available: `hazeEffect(LocalHazeState.current)`, using a style with a
    `surfaceContainer` tint at ~70% alpha and a ~24 dp blur radius. The optional tint is
    cobalt (action) or coral (attention) at low alpha, used only to signal state.
  - Blur unavailable: a solid `surfaceContainerHigh` fill and a 1 dp `outlineVariant`
    border.
- `BlurAvailability` reads `WindowManager.isCrossWindowBlurEnabled` and listens for changes
  while the activity is started. It is exposed as `LocalBlurEnabled`.
- Sheet scrims: blurred backdrop when available; otherwise the standard M3 scrim colour.

## Accessibility and motion

Per Part 1, specifically:

- Every icon button and the state dot have content descriptions. The chip reads its state
  in words.
- Touch targets are at least 48 dp.
- At font scale 2.0, rows wrap, the chip truncates the name, and the FAB label wraps or
  collapses to icon + progress. Nothing clips.
- Text contrast is 4.5:1 on glass and on accent fills (on-colours from `DESIGN.md`).
  Checked in opaque mode too.
- Motion uses `MotionScheme.expressive()` springs. When animations are off, morphs become
  cross-fades with no scale or bounce.
- TalkBack must be able to complete pairing (code read digit by digit) and a send.

## Testing

`:app:testDebugUnitTest` (JVM, JUnit 4) gains:

- `ConnectionStateTest`, `SendPhaseTest`, `DeviceListItemTest` (merge order, subtitles,
  selection rules), `HistoryRowContentTest` (sent / received / failed, retry and open
  flags), `ApprovalQueueTest`, `ReceiveStateTest`. These are ported from the Mac checks where
  they exist, so copy and rules stay identical.
- `TransferHistoryCodecTest`: old JSON (no new fields) loads with defaults; round-trip
  works.
- `ApprovalResolutionTest`: the hub-side resolver (extracted into a pure class that takes a
  clock and a cancel-notification callback). First answer wins, a late answer is ignored,
  timeout declines and removes, and the notification-cancel hook fires once.
- `TimeoutsTest`: the finalize and upload timeouts are ≥ 120 s; approval is 60 s.
- All existing tests stay green.

Manual (Part 1 verification list):

- Screenshots of every screen and state (empty, searching, connected, pairing code,
  sending, failed, approval pending) in light, dark, blur off (battery saver) and font scale
  2.0, captured on the emulator into `docs/screenshots/android/`.
- A real-device run with the Mac: pair, send both ways, accept an incoming file in the
  foreground (sheet) and in the background (notification), and see the transfers in
  History.
- TalkBack pairs and sends.

Build with `scripts/build-android.sh`, install with `scripts/install-android-debug.sh`, and
test with `./gradlew :app:testDebugUnitTest` from `android/`.

## Done when

- Both activities are Compose; `Ui.kt` and all View-building code are gone.
- Unit tests pass, and the debug and release builds succeed.
- Screenshots and the real-device run are attached to the PR.
- `DESIGN.md` links the Android screenshots.
