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
4. Add Android screen mirroring to Mac after transfer is reliable.
5. Add Mac screen mirroring to Android after the receive path is stable.

## Local Development

### macOS

```sh
cd macos
swift build
swift run LinkBridgeMac
```

Run the native Mac window:

```sh
cd macos
swift run LinkBridgeMac ui
```

Send a file from Mac to the first discovered Android peer:

```sh
cd macos
swift run LinkBridgeMac send-peer /path/to/file
```

Package the Mac window as a clickable app:

```sh
./scripts/package-mac-app.sh
open dist/LinkBridge.app
```

### Android

Android Studio is optional. To build from the terminal, see [docs/android-cli.md](docs/android-cli.md).

```sh
./scripts/check-android-env.sh
./scripts/build-android.sh
```
