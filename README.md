# LinkBridge

LinkBridge is a planned Mac + Android companion app for local file transfer and wireless display.

The first milestone is local pairing and file transfer over the same Wi-Fi network. Wireless display will build on the same pairing, identity, and transport layer later.

## Repository Layout

- `macos/` - macOS Swift package for the desktop companion app foundation.
- `android/` - Android/Kotlin project skeleton for the mobile companion app.
- `docs/` - shared protocol and product notes.

## MVP Scope

1. Discover nearby LinkBridge devices on the same LAN.
2. Pair devices with a short code or QR payload.
3. Transfer files directly over the local network.
4. Ship file transfer on the Mac App Store and Google Play.
5. Add screen mirroring between Mac and Android (both directions) on the same pairing.

## Local Development

### macOS

The Swift package in `macos/` has four targets:

- `LinkBridgeCore`: protocol, encryption, pairing, discovery and transfer (no UI)
- `LinkBridge`: the Mac app (sandboxed, ships in the App Store bundle)
- `linkbridge-cli`: developer tool for a headless server and command-line sends
- `LinkBridgeCoreChecks`: checks for the core library

Build and open the app:

```sh
./scripts/package-mac-app.sh
open dist/LinkBridge.app
```

Run the checks (they use a throwaway home folder, never your real keys or Downloads):

```sh
cd macos
swift run LinkBridgeCoreChecks
```

Developer tool:

```sh
cd macos
swift run linkbridge-cli                     # headless server, declines new pairings
swift run linkbridge-cli send-peer /path/to/file
```

On Swift 6.4 the default build system can fail with "Unknown error parsing property
list"; add `--build-system native` to `swift build` / `swift run`. The packaging
script does this automatically.

Version, bundle ID and build number live in `macos/version.env`. Sandbox permissions
are in `macos/LinkBridge.entitlements`.

### Android

Android Studio is optional. To build from the terminal, see [docs/android-cli.md](docs/android-cli.md).

```sh
./scripts/check-android-env.sh
./scripts/build-android.sh
```
