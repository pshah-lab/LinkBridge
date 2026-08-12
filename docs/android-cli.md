# Android CLI Setup

You do not need Android Studio to build LinkBridge for Android. You do need:

- Java 17
- Android command-line tools
- Android SDK platform/build tools
- Gradle

## Install Tools With Homebrew

```sh
brew install --cask android-commandlinetools
brew install gradle
```

Add the Android SDK paths to your shell profile:

```sh
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Reload the shell:

```sh
source ~/.zshrc
```

## Install Android SDK Packages

```sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## Build From Terminal

```sh
./scripts/build-android.sh
```

The debug APK will be created under:

```text
android/app/build/outputs/apk/debug/
```

## Install On A Phone

Enable Developer Options and USB debugging on the Android phone, then run:

```sh
adb devices
./scripts/install-android-debug.sh
```

## Notes

- Android Studio is optional. It is only a GUI around the same SDK and Gradle tools.
- Wireless display will later require runtime permissions for screen capture via `MediaProjection`.
- Local peer discovery requires Wi-Fi multicast support on the network.
