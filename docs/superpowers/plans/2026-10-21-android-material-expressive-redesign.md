# Android Material 3 Expressive Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace LinkBridge Android's hand-built View UI with Jetpack Compose, Material 3 Expressive and Haze glass, keeping networking and protocol behaviour identical.

**Architecture:** A pure-Kotlin `presentation` package (JVM-tested, mirroring `macos/Sources/LinkBridgePresentation`) derives everything the screens show. `LinkBridgeHub` becomes `StateFlow`s plus a single approval resolver. `MainViewModel` merges hub, stores and the pairing flow into one `UiState`, and thin composables under `ui/` render it.

**Tech Stack:** Kotlin (AGP 9.4 built-in), Jetpack Compose (BOM), Material 3 Expressive, Haze 1.x, AndroidX Lifecycle/ViewModel, kotlinx-coroutines, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-21-android-material-expressive-redesign-design.md`. Read it first; this plan does not repeat screen copy that the spec already fixes.

## Global Constraints

- `minSdk = 31`, `targetSdk = 36`, `compileSdk = 36`. Do not change them.
- `/pair/finalize` read timeout `120_000` ms and the upload `soTimeout` `120_000` ms do not change. The Mac relies on them staying above its 110 s.
- Incoming-file approval timeout stays `60` s.
- Pairing flow stays begin → show code → Continue → finalize (protocol v3). No protocol, discovery-tag, TLS-PSK or share-intent validation changes.
- Glass only on: top app bar (scrolled), nav bar, connection chip, Send FAB, sheet scrims. Never on lists, rows, the pick zone or body text.
- Blur only when `WindowManager.isCrossWindowBlurEnabled`; otherwise draw an opaque `surfaceContainerHigh` with a 1 dp `outlineVariant` border.
- Accents (light/dark): cobalt `#3E62E8`/`#7C95FF`, mint `#35C98B`/`#4ADBA0`, coral `#F08A5D`/`#FF9F78`; labels on fills: white on light cobalt, ink `#10151F` otherwise. Backdrop is cobalt 6% (light) / 10% (dark) over `background`.
- Dynamic colour on; cobalt light/dark schemes when it is unavailable.
- Touch targets ≥ 48 dp. Every icon and state dot has a content description. Animations off (`ANIMATOR_DURATION_SCALE == 0`) → cross-fades only.
- All copy exactly as in the spec's Screens section.
- Pin exact released dependency versions. Debug and release (R8) builds must pass.
- Commands, all from `android/`: tests `./gradlew :app:testDebugUnitTest`; build `../scripts/build-android.sh`; install `../scripts/install-android-debug.sh`.

## Review Focus

1. **Finalize returns success after the user pressed Cancel.** The Mac has paired, so the phone must save the `TrustedPeer` silently. Pinned by `MainViewModelPairingTest.lateSuccessAfterCancelStillSavesTrust` (Task 9).
2. **An approval answered from the notification while the in-app sheet shows it.** The sheet must close and the answer must count once. Pinned by `ApprovalCenterTest.notificationAnswerRemovesFromPending` (Task 5).
3. **The selected device is unpaired, or vanishes, while Send is open.** Selection falls back to another sendable device or none, and Send shows the empty state. It must never send to an untrusted peer. Pinned by `DeviceSelectionTest.unpairedSelectionFallsBack` (Task 3).
4. **Stored history is corrupt or from the old format.** Old entries load with defaults; corrupt JSON gives an empty list, not a crash. Pinned by `TransferHistoryCodecTest.corruptJsonGivesEmptyList` (Task 4).
5. **The service is stopped (notification Stop, or Settings switch) while a send or approval is in flight.** Pending approvals are declined and cleared, the send ends `Failed`, and `ReceiveState` becomes `Stopped`. Pinned by `ApprovalCenterTest.declineAllClearsPending` (Task 5) and checked manually in Task 13.

---

### Task 1: Name and guard the timeouts

**Files:**
- Modify: `app/src/main/java/com/linkbridge/android/ControlClient.kt:85`, `SecureTransport.kt:87`, `LinkBridgeService.kt:286`
- Create: `app/src/main/java/com/linkbridge/android/Timeouts.kt`
- Test: `app/src/test/java/com/linkbridge/android/TimeoutsTest.kt`

**Interfaces:**
- Produces: `object Timeouts { const val PAIR_FINALIZE_READ_TIMEOUT_MS = 120_000; const val UPLOAD_SOCKET_TIMEOUT_MS = 120_000; const val APPROVAL_TIMEOUT_SECONDS = 60L }`

- [ ] **Step 1: Write the failing test**

```kotlin
class TimeoutsTest {
    // The Mac gives up at 110 s; Android must wait longer.
    @Test fun finalizeOutlastsMac() = assertTrue(Timeouts.PAIR_FINALIZE_READ_TIMEOUT_MS >= 120_000)
    @Test fun uploadOutlastsMac() = assertTrue(Timeouts.UPLOAD_SOCKET_TIMEOUT_MS >= 120_000)
    @Test fun approvalIsSixtySeconds() = assertEquals(60L, Timeouts.APPROVAL_TIMEOUT_SECONDS)
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*TimeoutsTest'`. Expected: FAIL, unresolved `Timeouts`.
- [ ] **Step 3: Create `Timeouts.kt`, and replace the three literals with the constants** (remove `APPROVAL_TIMEOUT_SECONDS` from the service companion).
- [ ] **Step 4: Run the full suite.** Expected: all PASS.
- [ ] **Step 5: Commit** `refactor(android): name transfer timeouts and guard them`.

### Task 2: SendPhase, ConnectionState, Accent

**Files:**
- Create: `app/src/main/java/com/linkbridge/android/presentation/{Accent,SendPhase,ConnectionState}.kt`
- Test: `app/src/test/java/com/linkbridge/android/presentation/{SendPhaseTest,ConnectionStateTest,AccentTest}.kt`

**Interfaces:**
- Produces:
  - `enum class Accent(val lightHex: Long, val darkHex: Long) { ACTION, SIGNAL, ATTENTION }` with `fun contrastRatio(fgHex: Long, bgHex: Long): Double` in its companion.
  - `sealed interface SendPhase { Idle; Ready(fileName); Sending(fileName, progress: Float); Sent(fileName); Failed(fileName, message) }`, with `actionTitle: String` and `fun isEnabled(targetSendable: Boolean): Boolean`.
  - `sealed interface ConnectionState { Searching; NoPairedDevice; Ready(deviceName); Sending(deviceName, progress); Error(message) }`, with `companion fun derive(nearbyCount: Int, selected: DeviceListItem?, sendPhase: SendPhase, errorMessage: String?)` and `title`, `dotLabel`, `accent: Accent?`.
  - (`DeviceListItem` comes from Task 3. Write Task 3's data class first if needed, or do Tasks 2 and 3 together.)

- [ ] **Step 1: Write the failing tests.** Port the cases from the Mac checks (`macos/Sources/LinkBridgeCoreChecks`, the ConnectionState/SendPhase/Accent sections):
  - `actionTitle`: "Choose a file" / "Send" / "Sending…" / "Sent" / "Retry"
  - `isEnabled`: true only for Ready/Failed with a sendable target
  - `derive` priority: error > sending (even if the target is no longer sendable) > ready > searching (nearby 0) > noPairedDevice
  - `dotLabel`: "Connected" / "Searching" / "Error"
  - `AccentTest`: every accent/on-colour pair from Global Constraints is ≥ 4.5
- [ ] **Step 2: Run them.** Expected: FAIL (unresolved).
- [ ] **Step 3: Implement the three files.** `contrastRatio` uses the WCAG relative-luminance formula.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): presentation SendPhase, ConnectionState, Accent`.

### Task 3: DeviceListItem and DeviceSelection

**Files:**
- Create: `presentation/DeviceListItem.kt`
- Test: `presentation/DeviceListItemTest.kt`, `presentation/DeviceSelectionTest.kt`

**Interfaces:**
- Consumes: `DiscoveredPeer`, `TrustedPeer` (existing).
- Produces:
  - `enum class DevicePlatform { MAC, ANDROID, IPHONE, UNKNOWN; companion fun from(raw: String?) }`
  - `data class DeviceListItem(id: String, deviceId: String?, name: String, platform: DevicePlatform, isPaired: Boolean, isNearby: Boolean, isSendable: Boolean, canPair: Boolean, subtitle: String, peer: DiscoveredPeer?)`
  - `companion fun merge(nearby: List<DiscoveredPeer>, trusted: List<TrustedPeer>): List<DeviceListItem>`
  - `object DeviceSelection { fun resolve(current: String?, preferred: String?, items: List<DeviceListItem>, isSending: Boolean): String? }`. IDs are `DeviceListItem.id`.

- [ ] **Step 1: Write the failing tests.**
  - Order: nearby paired, then nearby unpaired, then away; A→Z within each.
  - `id = deviceId ?: serviceName`.
  - An unpaired macOS peer with `deviceId == null` and a `tlsPort` gives `canPair = true`, subtitle "Not paired".
  - `tlsPort == null` gives "Needs a LinkBridge update", with `canPair` and `isSendable` both false.
  - Paired + nearby + `tlsPort`: "Paired · Nearby", sendable.
  - Trusted but not nearby: "Paired · Not nearby", not sendable.
  - Unpaired Android peer: "Pair from the Mac app".
  - Selection tests (from the Mac rules): preferred wins when sendable; current kept when sendable; else first sendable; unchanged while sending or when items are empty.
  - `unpairedSelectionFallsBack`: current is no longer paired, so the result is the other sendable id, or `null` when there is none. Never the unpaired id.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): device list merge and send-target selection`.

### Task 4: History data, codec and row content

**Files:**
- Modify: `TransferHistoryStore.kt` (entry fields; delegate JSON to the codec), `TrustedPeerStore.kt` (`pairedAt`, `remove`)
- Create: `TransferHistoryCodec.kt`, `presentation/HistoryRowContent.kt`
- Test: `TransferHistoryCodecTest.kt`, `presentation/HistoryRowContentTest.kt`

**Interfaces:**
- Produces:
  - `TransferHistoryEntry(..., succeeded: Boolean = true, detail: String? = null, contentUri: String? = null)`
  - `object TransferHistoryCodec { fun encode(entries: List<TransferHistoryEntry>): String; fun decode(raw: String?): List<TransferHistoryEntry> }`
  - `TrustedPeer(..., pairedAt: Long? = null)` and `TrustedPeerStore.remove(deviceId: String)`
  - `class HistoryRowContent(entry: TransferHistoryEntry, now: Long, zone: java.time.ZoneId)` with `title`, `detail`, `direction` (`SENT`/`RECEIVED`/`FAILED`), `accent`, `failureReason: String?`, `canRetry`, `canOpen`
  - `fun dayLabel(completedAt: Long, now: Long, zone: ZoneId): String` ("Today", "Yesterday", "21 Oct")

- [ ] **Step 1: Write the failing tests.**
  - The codec decodes old-format JSON (five fields only) with defaults, and round-trips new fields.
  - `corruptJsonGivesEmptyList`: `decode("not json")` and `decode(null)` return `emptyList()`.
  - Row detail: "To Mac mini · 2.1 MB · …" and "From …".
  - `canRetry` only for sent && !succeeded && contentUri != null.
  - `canOpen` only for received && succeeded && contentUri != null.
  - `dayLabel` boundaries.
  - Use `android.text.format.Formatter`-free byte formatting (a pure helper, as `formatBytes` already is) so the tests stay on the JVM.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `TransferHistoryStore` keeps prefs name, key and cap 50, and calls the codec.
- [ ] **Step 4: Run the full suite.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): richer transfer history and row content`.

### Task 5: ApprovalQueue and ApprovalCenter

**Files:**
- Create: `presentation/ApprovalQueue.kt`, `ApprovalCenter.kt`
- Test: `presentation/ApprovalQueueTest.kt`, `ApprovalCenterTest.kt`

**Interfaces:**
- Produces:
  - `data class PendingApproval(id: Int, request: IncomingTransferRequest, expiresAt: Long)`
  - `data class ApprovalQueue(val items: List<PendingApproval> = emptyList())` with `add`, `resolve(id)`, `expire(now)`, `oldest`
  - `class ApprovalCenter(clock: () -> Long, onWithdrawn: (id: Int) -> Unit)`:
    - `val pending: StateFlow<List<PendingApproval>>`
    - `fun register(request: IncomingTransferRequest): Int`
    - `fun await(id: Int, timeoutSeconds: Long): Boolean`, which blocks the receive thread
    - `fun resolve(id: Int, accept: Boolean): Boolean`. Returns false if the request was already resolved.
    - `fun declineAll()`
  - `onWithdrawn` fires exactly once per id (the service uses it to cancel the notification).

- [ ] **Step 1: Write the failing tests.**
  - Queue: ordering, resolve, and expire.
  - Center:
    - `firstAnswerWins`: `resolve(id, true)` then `resolve(id, false)` → `await` returns true and the second call returns false.
    - `notificationAnswerRemovesFromPending`: after `resolve`, `pending` excludes the id and `onWithdrawn` was called once.
    - `timeoutDeclinesAndWithdraws`: `await(id, 0)` returns false, `pending` is empty, and `onWithdrawn` was called once.
    - `declineAllClearsPending`.
  - Use a `java.util.concurrent` latch in the tests, not coroutines.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Per id, use an `ArrayBlockingQueue<Boolean>(1)`, as the service does today; `pending` is a `MutableStateFlow`. Add `org.jetbrains.kotlinx:kotlinx-coroutines-core` (pinned) for `StateFlow`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): approval queue and single approval resolver`.

### Task 6: ReceiveState and typed receive callbacks

**Files:**
- Create: `presentation/ReceiveState.kt`
- Modify: `ReceiveServer.kt` (constructor callbacks; `ReceivedTransfer` gains `contentUri: String?`)
- Test: `presentation/ReceiveStateTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface ReceiveState { Starting; Listening; Receiving(fileName, received: Long, total: Long); Stopped; Failed(message) }`
  - `fun bannerFor(receive: ReceiveState, notificationsAllowed: Boolean): Banner?`
  - `enum class BannerAction { START, RETRY, ENABLE_NOTIFICATIONS }` and `data class Banner(text: String, action: BannerAction)`. Priority and copy as in the spec's Devices section.
  - `ReceiveServer(context, port, onListening: () -> Unit, onProgress: (fileName: String, received: Long, total: Long) -> Unit, onFailed: (String) -> Unit, onTransferCompleted: (ReceivedTransfer) -> Unit, onApprovalRequested: (IncomingTransferRequest) -> Boolean)`

- [ ] **Step 1: Write the failing tests** for `bannerFor`:
  - Stopped → START, "LinkBridge isn't receiving — tap to start."
  - Failed → RETRY, "Receiving failed: <reason>"
  - Listening with notifications off → ENABLE_NOTIFICATIONS, with the spec's sentence
  - Listening with notifications on → null
  - Stopped with notifications off → START (priority)
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement `ReceiveState`, then swap `ReceiveServer`'s `onStatus` calls** at lines 46, 58, 232 and 254 for the typed callbacks, and pass `savedUri?.toString()` into `ReceivedTransfer`. Update the service's construction to compile; Task 7 does the hub wiring.
- [ ] **Step 4: Run the full suite and `../scripts/build-android.sh`.** Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(android): typed receive state`.

### Task 7: Hub flows and service wiring

**Files:**
- Modify: `LinkBridgeHub.kt`, `LinkBridgeService.kt`, `MainActivity.kt`/`ShareActivity.kt` (only to keep compiling)

**Interfaces:**
- Consumes: `ApprovalCenter` (Task 5), `ReceiveState` (Task 6), `TransferHistoryEntry` fields (Task 4).
- Produces (on `LinkBridgeHub`):
  - `peers: StateFlow<List<DiscoveredPeer>>`
  - `receiveState: StateFlow<ReceiveState>`
  - `sendState: StateFlow<SendState?>`
  - `pendingApprovals: StateFlow<List<PendingApproval>>`
  - `historyVersion: StateFlow<Int>`
  - `@Volatile var uiVisible: Boolean`
  - `fun resolveApproval(id: Int, accept: Boolean)`
  - `fun send(peer, trustedPeer, uri, persistable: Boolean): Boolean`
  - `fun rescan()`, `fun history(context)`
  - `fun stopService(context)`, `fun startService(context)`
- The old `Listener`, `foregroundApprover` and `Approver` stay as a thin shim fed by the flows, so the old activities compile. Task 12 deletes them.

- [ ] **Step 1: Rewire the service.**
  - Map the `ReceiveServer` callbacks and `onDestroy` to `receiveState`.
  - `requestApproval`:
    1. `id = center.register(request)`.
    2. If `!uiVisible`: post the notification when `canPostNotifications()`; otherwise `center.resolve(id, false)` and return false.
    3. `return center.await(id, Timeouts.APPROVAL_TIMEOUT_SECONDS)`.
  - `ACTION_ACCEPT`/`ACTION_DECLINE` go through `hub.resolveApproval`. `onWithdrawn` cancels `APPROVAL_NOTIFICATION_BASE + id`.
  - `onDestroy` calls `center.declineAll()`.
  - A failed send appends `TransferHistoryEntry(succeeded = false, detail = message, contentUri = uri if persistable)`. A received file stores `contentUri`. Every history write bumps `historyVersion`.
- [ ] **Step 2: Run the full suite and the build.** Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 3: Manual smoke test on the emulator or phone** (`../scripts/install-android-debug.sh`): the old UI still discovers, sends, and the approval dialog still works.
- [ ] **Step 4: Commit** `refactor(android): hub publishes state flows; approvals resolve in one place`.

### Task 8: Compose build, theme and glass

**Files:**
- Modify: root `build.gradle.kts` (Compose compiler plugin), `app/build.gradle.kts` (plugin, `buildFeatures.compose`, dependencies from the spec's Build section), `res/values/styles.xml` (`AppTheme` → `Theme.Material3.DayNight.NoActionBar`; add a translucent `AppTheme.Share`), `proguard-rules.pro` if R8 needs it
- Create: `ui/theme/{LinkBridgeTheme,Accents,Backdrop}.kt`, `ui/glass/{BlurAvailability,Glass}.kt`

**Interfaces:**
- Consumes: `Accent` (Task 2).
- Produces:
  - `@Composable fun LinkBridgeTheme(content: @Composable () -> Unit)`
  - `val LocalAccents: ProvidableCompositionLocal<AccentColors>`, where `AccentColors(action, onAction, signal, onSignal, attention, onAttention)`
  - `fun Modifier.backdrop(): Modifier`
  - `val LocalHazeState`
  - `val LocalBlurEnabled: ProvidableCompositionLocal<Boolean>`
  - `@Composable fun rememberBlurEnabled(activity: Activity): State<Boolean>`
  - `fun Modifier.glassSurface(shape: Shape, tint: Color? = null): Modifier`
  - `@Composable fun animationsEnabled(): Boolean` (reads `ANIMATOR_DURATION_SCALE`)

- [ ] **Step 1: Add the plugin and dependencies** with pinned versions. Confirm the Kotlin version bundled with AGP 9.4.1 for the Compose plugin version.
- [ ] **Step 2: Implement the theme and glass** as the spec describes:
  - `MaterialExpressiveTheme` with `MotionScheme.expressive()`
  - `dynamicLight/DarkColorScheme` with cobalt fallback schemes
  - `glassSurface`: Haze `hazeEffect` with a ~70% `surfaceContainer` tint and a 24 dp blur when `LocalBlurEnabled`; otherwise solid `surfaceContainerHigh` plus a 1 dp `outlineVariant` border
- [ ] **Step 3: Run the tests, `../scripts/build-android.sh` and `./gradlew :app:assembleRelease`.** Expected: PASS, and both builds successful.
- [ ] **Step 4: Commit** `build(android): Compose, Material 3 Expressive, Haze; theme and glass`.

### Task 9: MainViewModel and the pairing flow

**Files:**
- Create: `MainViewModel.kt`, `PairingClient.kt`, `UiState.kt`
- Test: `MainViewModelPairingTest.kt`, `MainViewModelStateTest.kt`
- Add test dependency: `kotlinx-coroutines-test` (pinned)

**Interfaces:**
- Consumes: Tasks 2–7.
- Produces:
  - `interface PairingClient { fun fetchDevice(peer: DiscoveredPeer): Result<DeviceIdentity>; fun beginPairing(peer: DiscoveredPeer, device: DeviceIdentity): Result<PairingSession>; fun finalizePairing(peer: DiscoveredPeer): Result<Unit> }`. The production implementation wraps `ControlClient` + `IdentityStore`.
  - `sealed interface PairingStep { Connecting(name); Code(name, code: String); Waiting(name); Paired(name); Failed(name, message) }`
  - `data class UiState(devices: List<DeviceListItem>, selectedId: String?, connection: ConnectionState, sendPhase: SendPhase, stagedUri: Uri?, live: List<LiveRow>, history: List<HistorySection>, approvals: List<PendingApproval>, banner: Banner?, pairing: PairingStep?, thisDevice: DeviceIdentity, trusted: List<TrustedPeer>, receivingOn: Boolean)`
  - `class MainViewModel(app: Application, hub: HubPort, stores: Stores, pairing: PairingClient, io: CoroutineDispatcher)`, with intents `select(id)`, `pair(id)`, `continuePairing()`, `cancelPairing()`, `dismissPairing()`, `stage(uri, name, size)`, `send()`, `retry(entry)`, `resolveApproval(id, accept)`, `unpair(deviceId)`, `rescan()`, `setReceiving(on)`, `notificationsChanged(allowed)`.
  - `sealed interface LiveRow { Sending(fileName, progress, peerName); Waiting(approval: PendingApproval); Receiving(fileName, received, total) }`
  - `data class HistorySection(label: String, rows: List<HistoryRowContent>)`. `HistoryRowContent` keeps a reference to its `TransferHistoryEntry` for `retry`.
  - `HubPort` is a small interface over the hub's flows and calls, so the tests can fake it.
  - The selected id persists in `linkbridge_ui` prefs under `selected_device`.

- [ ] **Step 1: Write the failing tests** (fake `PairingClient`/`HubPort`, `StandardTestDispatcher`):
  - `pairWalksConnectingCodeWaitingPaired`: the trusted peer is saved with the session's `authToken` and `pairedAt`; `rescan` is called; the selection becomes the new device.
  - `beginFailureShowsFailed`.
  - `lateSuccessAfterCancelStillSavesTrust`: cancel during Waiting → `pairing == null` immediately; when finalize later succeeds the `TrustedPeer` is saved and the sheet does not reappear.
  - `lateFailureAfterCancelIsDropped`.
  - `sendRefusedWithoutTrustedTarget`.
  - `sendPhaseFollowsHubSendState`: Progress → Sending, Done → Sent then Idle after 2 s of virtual time, Failed → Failed.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Combine the hub flows with `combine` and use `stateIn(viewModelScope)`. Run network calls on `io`. `DeviceSelection.resolve` runs on every devices change.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): MainViewModel with pairing flow`.

### Task 10: App frame, Devices, pairing sheet, banner

**Files:**
- Create: `ui/LinkBridgeApp.kt`, `ui/devices/{DevicesScreen,DeviceRow,PairingSheet}.kt`, `ui/components/{ConnectionChip,GlassNavBar,StatusBanner,EmptyState}.kt`
- Modify: `MainActivity.kt` (replace with `setContent { LinkBridgeTheme { LinkBridgeApp(viewModel) } }`, `enableEdgeToEdge()`, service start in `onStart`, `hub.uiVisible` on `onStart`/`onStop`, keep the notification-permission logic and launchers)

**Interfaces:**
- Consumes: `UiState` and the intents (Task 9), the theme and glass (Task 8).
- Produces:
  - `enum class Tab { DEVICES, SEND, HISTORY }`
  - `@Composable fun LinkBridgeApp(vm: MainViewModel, onRequestNotifications: () -> Unit)`
  - Each screen takes `(state: UiState, actions: …)` with no ViewModel reference, so previews work.

- [ ] **Step 1: Build the frame.**
  - Scaffold on the `backdrop()`, with content as the `hazeSource`.
  - Top bar: `ConnectionChip` + Settings icon.
  - Floating `GlassNavBar` (Devices · Send · History) above the navigation-bar inset.
  - Start tab is Send when any device is sendable, else Devices.
  - Settings is a separate destination; back returns to the tab.
- [ ] **Step 2: Build Devices, `DeviceRow`, `StatusBanner` and `PairingSheet`** using the spec's exact copy and states.
  - Pull-to-refresh calls `rescan()`.
  - The code is shown in `FontFamily.Monospace` at `displayMedium`, with `contentDescription` set to the digits separated by spaces.
  - Swipe-dismiss is disabled during Waiting.
- [ ] **Step 3: Add `@Preview`s** for: empty, searching, one paired + one unpaired, each banner, and each pairing step, in light, dark and fontScale 2.0.
- [ ] **Step 4: Run the tests, build, install and check on the emulator:** discovery list, pair with the Mac, banner after notification Stop.
- [ ] **Step 5: Commit** `feat(android): Compose app frame, Devices and pairing sheet`.

### Task 11: Send, History, approval sheet

**Files:**
- Create: `ui/send/{SendScreen,SendFab}.kt`, `ui/history/{HistoryScreen,HistoryRow,LiveTransferRow}.kt`, `ui/components/ApprovalSheet.kt`

**Interfaces:**
- Consumes: `UiState.sendPhase/stagedUri/live/history/approvals` and the intents.
- Produces: `LiveRow` (defined in Task 9's `UiState.kt`) rendering for `Sending`, `Waiting(PendingApproval)` and `Receiving`.

- [ ] **Step 1: Build Send.**
  - Pick zone → `OpenDocument`. On return, call `takePersistableUriPermission(READ)`, then `vm.stage`.
  - `SendFab` morphs through `SendPhase` with `SharedTransitionLayout` / `animateContentSize`, or a cross-fade when `!animationsEnabled()`.
  - Empty state when nothing is sendable.
- [ ] **Step 2: Build History.**
  - Live rows first (Accept/Decline inline on Waiting).
  - Then sections by `dayLabel`.
  - Open uses `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`; a snackbar appears when no activity can open it.
  - Retry calls `vm.retry`.
- [ ] **Step 3: Build `ApprovalSheet`** at the app root for `approvals.first()`:
  - countdown "Declines in N s" from `expiresAt`
  - "N more waiting"
  - swipe or back dismiss → `resolveApproval(id, false)`
  - blurred scrim via `glassSurface` when blur is on
- [ ] **Step 4: Add previews** for every `SendPhase`, history empty/mixed/failed, and the approval sheet with one and with three requests.
- [ ] **Step 5: Run the tests, build, install and check:** send to the Mac; accept a Mac→phone file in the foreground (sheet) and with the app backgrounded (notification), and see both in History; Open a received file.
- [ ] **Step 6: Commit** `feat(android): Send, History and approval sheet in Compose`.

### Task 12: Settings, share sheet, remove the View UI

**Files:**
- Create: `ui/settings/SettingsScreen.kt`, `ShareViewModel.kt`, `ui/share/ShareSheet.kt`
- Modify: `ShareActivity.kt` (Compose; keep `sharedUris` and `isOwnAuthority` unchanged), `AndroidManifest.xml` (`ShareActivity` theme `@style/AppTheme.Share`), `LinkBridgeHub.kt` (delete the `Listener`/`Approver` shim)
- Delete: `Ui.kt` and any View-building helpers left in the activities

**Interfaces:**
- Consumes: Hub (Task 7), `DeviceListItem.merge`, `TrustedPeerStore.remove`.

- [ ] **Step 1: Build Settings per the spec.**
  - Copy ID → clipboard + snackbar "Device ID copied".
  - The Receiving switch calls `vm.setReceiving`.
  - The notification row reuses the permission logic.
  - Unpair has a confirmation dialog; on confirm it calls `vm.unpair`, which removes the peer and rescans.
  - About shows `BuildConfig.VERSION_NAME`. Enable `buildFeatures.buildConfig` if it is off.
- [ ] **Step 2: Rebuild `ShareActivity`** as a Compose `ModalBottomSheet` over a translucent window, with the spec's copy:
  - Sending progress "Sending i of N — name".
  - Hide → `moveTaskToBack(true)` while sending, else `finish()`.
- [ ] **Step 3: Delete the shim and `Ui.kt`.** `grep -rn "android.widget\|LinearLayout\|AlertDialog" app/src/main/java` must return nothing.
- [ ] **Step 4: Run the tests, the debug build and `./gradlew :app:assembleRelease`.** Expected: all PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Check on the device:** share a photo from Photos to the Mac; Unpair, then confirm the device shows "Not paired" after the rescan.
- [ ] **Step 6: Commit** `feat(android): Settings and share sheet in Compose; remove View UI`.

### Task 13: Verification and docs

**Files:**
- Create: `docs/screenshots/android/*.png`
- Modify: `DESIGN.md` (link Android screenshots)

- [ ] **Step 1: Capture screenshots on the emulator** (`adb exec-out screencap -p`) of:
  - Devices: empty, searching, list, banner
  - Pairing: code, waiting
  - Send: idle, ready, sending, sent, failed
  - History
  - Approval sheet
  - Settings
  - Share sheet

  Capture each in light, dark, battery saver (blur off) and font scale 2.0 (`adb shell settings put system font_scale 2.0`).
- [ ] **Step 2: Do the real-device run with the Mac:**
  - pair
  - send phone → Mac and Mac → phone
  - foreground and background approvals
  - Stop from the notification during a pending approval → it is declined, and the banner shows Start (Review Focus 5)
  - TalkBack completes a pairing and a send
- [ ] **Step 3: Link the screenshots in `DESIGN.md`**, run the full suite once more, and commit `docs(android): redesign screenshots`.
- [ ] **Step 4: Open the PR** with the screenshots and the manual-run notes.
