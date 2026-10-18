# LinkBridge iOS Companion

The iOS companion uses the existing `LinkBridgeCore` Swift package for discovery,
pairing, TLS-PSK transport, AES-GCM chunk encryption, and SHA-256 verification.

## Requirements

- macOS with Xcode installed
- iOS 26 or later
- An Apple ID signing team for installing on a physical iPhone
- Both devices on the same Wi-Fi network

## Project layout

- `LinkBridgeIOS.xcodeproj` — app target `LinkBridgeIOS`, bundle id
  `com.pshahlab.linkbridge.ios`, iOS 26+. Depends on the local package `../macos`
  (product `LinkBridgeCore`).
- `LinkBridgeIOS/` — app sources. Xcode picks up every file in this folder
  automatically.
- `LinkBridgeIOSTests/` — Swift Testing unit tests (target `LinkBridgeIOSTests`,
  in the shared `LinkBridgeIOS` scheme). Run them with:

  ```bash
  xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= test
  ```
- `Info.plist` — extra keys (local network prompt, `_linkbridge._tcp` Bonjour)
  merged into the generated Info.plist.

Build for the Simulator from the command line. Sign ad hoc as below (`CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual
DEVELOPMENT_TEAM=`): an unsigned build can't use the Keychain, so pairing
secrets can't be saved or read.

```bash
xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'platform=iOS Simulator,name=iPhone Duo' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= build
```

## Try it without an iPhone

The Simulator runs on your Mac and reaches the Mac app over your Wi-Fi like a real
phone. Open the LinkBridge Mac app, run the iOS app in the Simulator, tap **Pair**
next to your Mac and approve the code on the Mac. Then send a file. Received files
land in `~/Downloads/LinkBridge` on the Mac. To get a test file into the Simulator,
drag it onto the Simulator window; it shows up in Files under On My iPhone.

## What works

- Liquid Glass interface (iOS 26): Devices, Send and History tabs with a glass tab
  bar, a glass connection capsule showing the active device, and a Send button
  that turns into a progress bar. Settings lists this iPhone and paired devices.
- Discovers nearby LinkBridge devices over Bonjour while the app is open.
- Pairs with the Mac app using the six-digit code in a glass sheet (iPhone
  initiates, the Mac approves). The Android app only initiates pairing, so it
  can't pair with the iPhone yet.
- Sends a chosen or dropped file to a paired device over TLS-PSK with progress,
  keeps a history of sends, and can retry a failed send from History.
- Secrets (identity token, pairing key, peer secrets) live in the iOS Keychain.

Not yet: receiving files on iOS (iOS can't keep a background server running) and
pairing with Android (needs a pairing responder in the Android app).

The app is intentionally file-transfer-only in this first iOS phase. Wireless
display will be added after Android and iOS transfer interoperability is verified.

## Install privately

Connect the iPhone, select it as the Xcode run destination, select your Apple ID
under Signing & Capabilities, then press Run. App installation without the App
Store still requires a provisioning profile and has Apple's normal development
profile limits.
