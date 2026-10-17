# LinkBridge iOS Companion

The iOS companion uses the existing `LinkBridgeCore` Swift package for discovery,
pairing, TLS-PSK transport, AES-GCM chunk encryption, and SHA-256 verification.

## Requirements

- macOS with Xcode installed
- iOS 16 or later
- An Apple ID signing team for installing on a physical iPhone
- Both devices on the same Wi-Fi network

## Project layout

- `LinkBridgeIOS.xcodeproj` — app target `LinkBridgeIOS`, bundle id
  `com.pshahlab.linkbridge.ios`, iOS 16+. Depends on the local package `../macos`
  (product `LinkBridgeCore`).
- `LinkBridgeIOS/` — app sources. Xcode picks up every file in this folder
  automatically.
- `Info.plist` — extra keys (local network prompt, `_linkbridge._tcp` Bonjour)
  merged into the generated Info.plist.

Build for the Simulator from the command line:

```bash
xcodebuild -project ios/LinkBridgeIOS.xcodeproj -scheme LinkBridgeIOS -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The app is intentionally file-transfer-only in this first iOS phase. Wireless
display will be added after Android and iOS transfer interoperability is verified.

## Install privately

Connect the iPhone, select it as the Xcode run destination, select your Apple ID
under Signing & Capabilities, then press Run. App installation without the App
Store still requires a provisioning profile and has Apple's normal development
profile limits.
