# Android CLI Setup

You do not need Android Studio to build LinkBridge for Android. You do need:

- Java 17 or newer
- Android command-line tools
- Android SDK platform 36 and build tools 36

Gradle comes from the wrapper in `android/gradlew` (Gradle 9.8.1, Android Gradle
Plugin 9.4.1), so no system Gradle install is needed.

## Install Tools With Homebrew

```sh
brew install --cask android-commandlinetools
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
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## Build From Terminal

```sh
./scripts/build-android.sh
```

The debug APK will be created under:

```text
android/app/build/outputs/apk/debug/
```

## Run Unit Tests

```sh
cd android
./gradlew :app:testDebugUnitTest
```

The pairing-code and encryption tests use the same vectors as the Mac checks, so a
change that breaks compatibility between the apps fails on both sides.

## Install On A Phone

Enable Developer Options and USB debugging on the Android phone, then run:

```sh
adb devices
./scripts/install-android-debug.sh
```

## Private Release APK

Create a release keystore once outside the repository:

```sh
./scripts/create-android-keystore.sh
```

Build a signed, R8-optimized APK by supplying the keystore values through the
environment. Do not commit these values or put them in `local.properties`:

```sh
export LINKBRIDGE_KEYSTORE="$HOME/.linkbridge/linkbridge-release.jks"
export LINKBRIDGE_KEY_ALIAS="linkbridge-release"
export LINKBRIDGE_KEYSTORE_PASSWORD='your-keystore-password'
export LINKBRIDGE_KEY_PASSWORD='your-key-password'
./scripts/build-android-release.sh
```

Install the signed APK on a connected device:

```sh
./scripts/install-android-private.sh
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`.

## Notes

- Android Studio is optional. It is only a GUI around the same SDK and Gradle tools.
- Wireless display will later require runtime permissions for screen capture via `MediaProjection`.
- Local peer discovery requires Wi-Fi multicast support on the network.
