# LinkBridge 0.2.0 release test (2026-11-21)

Built from origin/main `e6ef5b0`. Devices: OnePlus CPH2767 over USB and Wi-Fi, MacBook on macOS 27.2.1.

## Artifacts

| | Android APK | Mac zip |
|---|---|---|
| File | `android/app/build/outputs/apk/release/app-release.apk` | `dist/LinkBridge-0.2.0-macOS-arm64.dmg` (zip also built) |
| Version | 0.2.0 (versionCode 2) | 0.2.0 (build 2), arm64 only, macOS 26+ |
| Size | 9,969,223 bytes (9.5 MB) | DMG 1,326,503 bytes; zip 920,214 bytes |
| SHA-256 | `8c6d9df50e122d6ecc86ed10d53a38b8321380eb555885624bb3f6a3e986b165` | DMG `0b9b7eb1380ebf889f86e8ba9d7cda032f5c6f306c12f7f69f1d5c9f61d442f0`; zip `9503543efa6d9cd9c0f5900017a996d7560aecbdf940869ea4644713697ca34c` |
| Signing | APK Signature Scheme v2, new key "LinkBridge Private Release", cert SHA-256 `ba867a924c51713f0d2999dd3dbeb1fad46bdb067d9f44cbc805e6aa5189965d` | ad-hoc, no Team ID |
| SDK levels | targetSdk 36, minSdk 31, not debuggable | — |

The release key is new (2026-11-21) because the Oct 16 keystore password was lost. The old file is kept at `~/.linkbridge/linkbridge-release.old-2026-10-16.jks`. Back up the new `.jks` and its passwords: every future APK update must be signed with this key.

Artifacts were rebuilt the same day with the new app icon (no code changes). The end-to-end results below were recorded on the first builds; the rebuilt APK was installed as an update and stayed paired.

## Automated checks (on e6ef5b0)

- `./gradlew :app:testDebugUnitTest`: pass
- `./gradlew :app:lintRelease`: pass, no errors
- `swift run --build-system native LinkBridgeCoreChecks`: all 291 checks pass
- `./scripts/package-mac-app.sh`: pass (zip made with `ditto -c -k --keepParent`)

## End-to-end, release builds on real devices

| # | Test | Result |
|---|---|---|
| 1 | Fresh pairing with the six-digit code | PASS |
| 2 | Mac→Android small file (46 B) | PASS, SHA-256 matches |
| 3 | Mac→Android large file (150 MB) | PASS, SHA-256 matches |
| 4 | Mac→Android multi-file (4 files) | PASS, but the phone asks to accept each file separately |
| 5 | Android→Mac from inside the app (non-ASCII name) | PASS, SHA-256 and name `Ünïcødé_文件.txt` match |
| 6 | Android→Mac via share sheet (Files → Share → LinkBridge, 150 MB) | PASS, SHA-256 matches |
| 7 | Receive with the Android app in the background | PASS: "Accept file from Laptop?" notification with Accept/Decline, connectedDevice foreground service running, file saved |
| 8 | Reconnect after force-quitting both apps | PASS, no re-pairing needed |
| 9 | Non-ASCII filename Mac→Android (`Résumé_日本語_✓.txt`) | PASS, name and hash intact |
| 10 | Decline an incoming file | PASS, nothing saved |
| 11 | Downloaded Mac zip opened with quarantine | Blocked by Gatekeeper as expected (`spctl`: rejected). Open Anyway works |

Logcat (warnings and up, whole session): no crashes from LinkBridge, and no R8 errors (`ClassNotFound`, `NoSuchMethod`, `VerifyError`) in the app process. TLS-PSK through BouncyCastle worked in both directions on the minified build. The only `NoSuchMethodException` lines came from OnePlus system components (`Oplus*`), not from the app.

## Bugs and notes

No release-only bugs found and nothing was changed in code.

- **UX:** a multi-file send from the Mac shows one accept prompt per file on Android. Not release-only; consider a single prompt per batch.
- **Unconfirmed:** during the 4-file batch, `large-150MB.bin` appeared to arrive without my tapping Accept. A rerun with the file sent alone showed the prompt as expected, so this could not be reproduced. Most likely I missed seeing the prompt, but it is worth one manual check.
- **Mac:** after a relaunch, the app opens with no window (menu bar only). Expected, but testers may not know to look in the menu bar.

## Draft install instructions for testers

### Android (Android 12 or later)

1. Download `LinkBridge-0.2.0.apk` from the GitHub release on your phone.
2. Open it from your browser's downloads or the Files app.
3. If Android says the app you opened it from isn't allowed to install unknown apps, tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**. On Android 8 and later this permission is per app, under **Install unknown apps**.
4. If Google Play Protect warns about an unrecognised app, choose to install anyway only if the SHA-256 matches the one in the release notes.
5. If you had an earlier LinkBridge build, uninstall it first; it was signed with a different key. You will have to pair again.

### Mac (macOS 26 or later, Apple silicon)

1. Download `LinkBridge-0.2.0-macOS-arm64.zip` and double-click it to unzip.
2. Move `LinkBridge.app` to Applications and open it. macOS will say it can't verify the app. Click **Done** (don't move it to the Bin).
3. Open **System Settings › Privacy & Security**, scroll to **Security**, and click **Open Anyway** next to LinkBridge. The button stays for about an hour after you try to open the app.
4. Enter your login password, then click **Open** in the warning that follows. You only need to do this once.
5. LinkBridge lives in the menu bar. Allow local network access when asked.

Unverified wording, check before publishing: the exact Android "Allow from this source" and Play Protect texts (vary by phone maker), the macOS dialog button names ("Done", "Move to Bin"), and the local network prompt.

## Sources checked

- Apple, "Safely open apps on your Mac": https://support.apple.com/en-us/102445 (published 27 May 2026). Open Anyway is under System Settings › Privacy & Security.
- Apple, Mac User Guide, "Open a Mac app from an unknown developer": https://support.apple.com/guide/mac-help/open-a-mac-app-from-an-unknown-developer-mh40616/mac. The button lasts about an hour and you need your login password.
- Android Developers, "Alternative distribution options": https://developer.android.com/distribute/marketing-tools/alternative-distribution. On Android 8.0+, enable installs per source in the "Install unknown apps" settings screen.
