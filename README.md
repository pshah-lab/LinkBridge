# LinkBridge

LinkBridge sends files directly between a Mac, an Android phone and an iPhone on the
same Wi-Fi network. Nothing goes through the internet. Devices pair once with a
six-digit code, and every transfer is encrypted over TLS-PSK keyed from that pairing.
The goal is to ship on the Mac App Store, the App Store and Google Play. Screen
mirroring between Mac and Android comes after file transfer, on the same pairing.

## What works today

| | Mac | Android | iPhone |
|---|---|---|---|
| Find nearby devices (Bonjour / NSD) | ✓ | ✓ | ✓ |
| Pair with a six-digit code | Approves requests | Starts pairing with the Mac | Starts pairing with the Mac |
| Send files | ✓ | ✓ | ✓ |
| Receive files | ✓ | ✓ (also in the background) | Not yet |

Mac ↔ Android and iPhone → Mac transfers are verified end to end. The iPhone can't
pair with Android yet, because the Android app can only start pairing, not answer it.

## Repository layout

- `macos/` — Swift package: the Mac app, the shared core library, a developer CLI and checks.
- `ios/` — the iPhone app (Xcode project), built on the same core library. See [ios/README.md](ios/README.md).
- `android/` — the Android app (Kotlin). See [android/README.md](android/README.md).
- `docs/` — protocol ([protocol.md](docs/protocol.md)), [roadmap](docs/roadmap.md),
  design specs and screenshots.
- `scripts/` — build, package and install scripts for Mac and Android.

## Local development

### macOS

Requires macOS 26 and Xcode 26 (Swift 6.2 tools or newer). The package in `macos/`
has five targets:

- `LinkBridgeCore`: protocol, encryption, pairing, discovery and transfer (no UI). Used by the Mac and iPhone apps.
- `LinkBridgePresentation`: view logic shared by the Mac and iPhone apps (device list, send state, history, approvals).
- `LinkBridge`: the Mac app. Sandboxed, lives in the menu bar with a main window.
- `linkbridge-cli`: developer tool for a headless server and command-line sends.
- `LinkBridgeCoreChecks`: checks for the core and presentation libraries.

Build, install and open the app:

```sh
./scripts/install-mac-app.sh
```

To build without installing, run `./scripts/package-mac-app.sh`. It writes `dist/LinkBridge.app`.

Run the checks. They use a throwaway home folder, never your real keys or Downloads:

```sh
cd macos
swift run --build-system native LinkBridgeCoreChecks
```

Developer tool:

```sh
cd macos
swift run --build-system native linkbridge-cli                     # headless server, declines new pairings
swift run --build-system native linkbridge-cli send-peer /path/to/file
```

On this toolchain the default build system can fail with "Unknown error parsing
property list", so pass `--build-system native` to `swift build` and `swift run`.
The packaging script does this automatically.

Mac keyboard shortcuts: ⌘, Settings · ⌘O choose a file to send · ⇧⌘R open received
files · ⌘1 / ⌘2 / ⌘3 Devices / History / Settings · ⌘0 show the window · ⌘Q quit
(also from the menu bar popover).

Version, bundle ID and build number live in `macos/version.env`. Sandbox permissions
are in `macos/LinkBridge.entitlements`.

### iPhone

See [ios/README.md](ios/README.md). The app runs in the iOS Simulator, so you don't
need an iPhone to try it.

### Android

Android Studio is optional. To build from the terminal, see [docs/android-cli.md](docs/android-cli.md).

```sh
./scripts/check-android-env.sh
./scripts/build-android.sh
```
