# LinkBridge System Requirements & Setup Guide

This document lists all required software, dependencies, environment configurations, and setup steps required to run **LinkBridge** locally on a new machine.

---

## 1. System Requirements

### macOS Desktop App (`macos/`)
- **Operating System**: macOS 13.0 (Ventura) or newer
- **Build System / Toolchain**: Swift 5.9+ (Included with Xcode or Xcode Command Line Tools)
- **Network Environment**: Wi-Fi local network with mDNS / Multicast enabled (for LAN device discovery and file transfer)

### Android Mobile App (`android/`)
- **Java Development Kit (JDK)**: OpenJDK 17
- **Android SDK Specifications**:
  - `compileSdk`: 36 (Android 16)
  - `minSdk`: 26 (Android 8.0 Oreo or higher)
  - `targetSdk`: 36 (required by Google Play)
  - `buildToolsVersion`: 36.0.0
- **Gradle**: provided by the wrapper (`android/gradlew`, Gradle 9.8.1, Android Gradle Plugin 9.4.1 with built-in Kotlin)
- **Target Physical/Virtual Device**: Android 8.0+ device with **Developer Options & USB Debugging** enabled.

---

## 2. Required Software & Toolchain Summary

| Component | Required Version | Description | Installation Command |
| :--- | :--- | :--- | :--- |
| **Xcode Command Line Tools** | System Default (Swift 5.9+) | Swift toolchain to build the `LinkBridge` app | `xcode-select --install` |
| **Java Development Kit** | OpenJDK 17 | Required for Android build system | `brew install openjdk@17` |
| **Android Command Line Tools** | Latest | Package manager (`sdkmanager`, `adb`) | `brew install --cask android-commandlinetools` |
| **Android SDK Packages** | API 36 (`android-36`) | Android SDK platforms and ADB | `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"` |
| **Android Studio** *(Optional)* | Ladybug / Jellyfish+ | IDE for Kotlin/Android (terminal scripts can build without it) | `brew install --cask android-studio` |

---

## 3. Environment & Shell Configuration

Add the following environment variables to your shell profile (`~/.zshrc` or `~/.bashrc`):

```sh
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Reload shell settings:
```sh
source ~/.zshrc
```

---

## 4. Setup & Verification Workflow

### Step A: Verify Environment
Run the included verification script:
```sh
./scripts/check-android-env.sh
```

### Step B: Build & Run macOS App
Package into a sandboxed application (`dist/LinkBridge.app`):
```sh
./scripts/package-mac-app.sh
open dist/LinkBridge.app
```

### Step C: Build & Install Android App
Connect an Android device via USB and run:
```sh
./scripts/build-android.sh
./scripts/install-android-debug.sh
```
