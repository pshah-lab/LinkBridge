# LinkBridge Android

The Android app finds nearby LinkBridge devices, pairs with the Mac using the
six-digit code, and sends and receives files over TLS-PSK (BouncyCastle). A
foreground service handles discovery, receiving and sending, so transfers keep
going in the background. You can also send from the share sheet of any app.

- Package `com.linkbridge.android`, Android 12+ (minSdk 31), targetSdk 36.
- Receives on port 49153, TLS only.
- Pairing only works in one direction for now: Android starts it, and the Mac
  approves. The iPhone can't pair with Android yet.

## Build

Android Studio is optional. See [../docs/android-cli.md](../docs/android-cli.md)
for command-line setup.

```sh
../scripts/check-android-env.sh
../scripts/build-android.sh          # debug APK
../scripts/install-android-debug.sh  # build and install on a connected phone
```

Unit tests (from `android/`):

```sh
./gradlew :app:testDebugUnitTest
```

## Release build

Create a keystore once, outside the repository:

```sh
../scripts/create-android-keystore.sh
```

Then set `LINKBRIDGE_KEYSTORE`, `LINKBRIDGE_KEYSTORE_PASSWORD`,
`LINKBRIDGE_KEY_ALIAS` and `LINKBRIDGE_KEY_PASSWORD`, and run
`../scripts/build-android-release.sh`. This builds a signed, R8-minified APK.
`../scripts/install-android-private.sh` builds it and installs it on a connected
phone. Details are in [../docs/android-cli.md](../docs/android-cli.md).
