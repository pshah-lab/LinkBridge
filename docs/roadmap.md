# LinkBridge Roadmap

Goal: ship LinkBridge on the Mac App Store and Google Play. File transfer launches
first (v1.0); screen mirroring between Mac and Android follows on the same pairing
and identity (v1.1+).

## Done

- Discovery over Bonjour/NSD (`_linkbridge._tcp`).
- Pairing with a six-digit code (commit/reveal, see `protocol.md`), verified on real devices.
- Encrypted, signed, replay-protected file transfer in both directions, verified on real devices.
- Mac package split into core library, sandboxed app, developer CLI and checks.
- Mac app bundle with App Sandbox entitlements and local-network usage text.

## Milestone 1: Production foundation

Mac:
- Real signing with an Apple Development / Distribution certificate; move secrets back
  to the Keychain once signing is stable.
- App icon and Assets.
- Replace `PeerResolver`'s deprecated `NetService` with `NWBrowser` + `NWConnection`.
- Move the checks into XCTest / Swift Testing once Xcode is installed.

Both platforms:
- TLS transport with certificates exchanged and pinned during pairing (replaces plain
  HTTP + app-layer crypto; required before screen mirroring).

Android (done, verified on device):
- Target API 36 with AGP 9.4.1 and a Gradle wrapper; minSdk 31 (Android 12+).
- `LinkBridgeService` foreground service (`connectedDevice`) for discovery, receiving
  and sending; Accept/Decline notifications for files arriving in the background.
- Share sheet ("Share → Send to Mac"), content:// streams from other apps only.
- Modern APIs (ComponentActivity, result contracts, edge-to-edge, ServiceInfoCallback),
  unused permissions removed, data extraction rules.
- Unit tests sharing vectors with the Mac checks.

## Milestone 2: Transfer polish

- Progress, cancel and retry on both platforms; resume for large files.
- Multiple files and folders per transfer.
- Transfer history.
- Choose which paired device to send to (the Mac currently picks the first phone found).

## Milestone 3: Store launch (v1.0, file transfer)

- Apple Developer Program and Google Play Console accounts.
- Privacy policy page (no data collected; everything stays on the local network).
- App Store privacy label and Play Data safety form.
- Screenshots, descriptions, review notes and a demo video showing both devices
  (App Review cannot pair a phone themselves).
- Play Console foreground service declaration for `connectedDevice`, with a video of
  receiving a file while the app is in the background.
- App icons (the Android app and Mac app have none yet).
- Google Play closed test: 12 testers for 14 consecutive days (new personal accounts).
- Mac App Store submission and Play production release.

## Milestone 4: Screen mirroring, Android to Mac (v1.1)

- Android `MediaProjection` capture in a `mediaProjection` foreground service
  (Play requires a declaration with a demo video).
- Hardware H.264 encoding with `MediaCodec`.
- Mac receive and decode with VideoToolbox, shown in its own window.
- Separate streaming transport over the paired, encrypted channel (WebRTC or a TLS/UDP
  stream; decide when starting this milestone).
- Orientation and resolution changes, latency target under 100 ms on the same Wi-Fi.
- Audio routing decision.

## Milestone 5: Screen mirroring, Mac to Android (v1.2)

- macOS `ScreenCaptureKit` capture (needs Screen Recording permission and
  `NSScreenCaptureUsageDescription`; works in the sandbox).
- H.264/HEVC encode with VideoToolbox, decode with `MediaCodec` on Android.
- Use the phone as a second display or a remote view of the Mac.
- Input experiments. Note: controlling the phone from the Mac needs an Android
  Accessibility service, which Google Play restricts for non-accessibility apps.
  Mac-side input from the phone (touch to Mac pointer) does not have this problem.

## Milestone 6: Product polish

- Auto-reconnect trusted devices.
- Clipboard sync.
- Local hotspot fallback when there is no shared Wi-Fi.
- Menu bar mode on the Mac.
