# Protocol version check and "new version available" notice

Date: 2026-11-22. Status: draft for approval.

## Why

1. When two devices can't work together because one runs an older LinkBridge, the user
   gets a silent or vague failure. They should instead see, on both devices, which device
   to update.
2. People who installed LinkBridge from the website have no way to learn that a newer
   build exists.

## Goals

- A clear "Update LinkBridge on <device>" message on both sides, in the device list,
  during pairing, and on send/receive.
- Website builds (Mac, Android) show a quiet banner when a newer version is out,
  linking to the download page.

## Non-goals and anti-goals

- No auto-download or auto-install, ever. The app only links to the site.
- The checker must not ship in Mac App Store or Google Play builds. Play bars apps
  updating themselves outside Play (Device and Network Abuse policy).
- No change that stops 0.2.0/0.2.1 devices from working with this release.
- No pop-ups or system notifications for the update notice.

## Today

- Bonjour TXT carries `protocol=2`, but no scanner reads it. "Needs a LinkBridge update"
  is inferred from a missing TLS port or device ID, which only catches pre-protocol-2 apps.
- The JSON `protocolVersion` sent in `/pair/request` and stored in identities is a stale
  `1` on both platforms, so it can't be trusted.
- 0.2.x devices can't show the new messages; only this release and later can.

## Approaches considered

- **A. Advertise a minimum supported version next to the current one (chosen).**
  Additive, keeps 0.2.x working, and lets the device that knows name which side must
  update, in either direction.
- B. Bump to protocol 3 and require a match. Breaks 0.2.x pairing for no gain.
- C. Compare app version strings. Ties protocol compatibility to release numbers, which
  drift between Mac, iOS and Android.

For the notice, a banner was chosen over a system notification (less pushy, no Android
notification permission needed). The check is on by default with a Settings toggle.

## Design: protocol version check

### Numbers

- Current protocol stays **2**. New constant: minimum supported protocol **2**.
- Two devices are compatible when `local.protocol >= peer.minProtocol` and
  `peer.protocol >= local.minProtocol`.
  - `peer.protocol < local.minProtocol` → **peer too old** → "Update LinkBridge on <peer name>".
  - `local.protocol < peer.minProtocol` → **this device too old** → "Update LinkBridge on
    this Mac" (Mac) / "on this device" (Android, iOS).
- Inference for older peers that don't send the new fields:
  - TXT has `protocol` and a `tlsPort`, no `minProtocol` → protocol as given, min 2.
  - No `tlsPort` and no device ID (pre-protocol-2) → protocol 1, min 1. This replaces the
    current heuristic with the same outcome.
  - JSON without `protocol`/`minProtocol` → protocol 2, min 2 if `pairingVersion >= 3`,
    otherwise protocol 1.
- One pure function per platform, `ProtocolCompat.check(local, peer) -> ok | peerTooOld |
  localTooOld`, used everywhere below. Swift in `LinkBridgeCore`, Kotlin in the Android app.

### Wire changes (all additive)

- TXT: add `minProtocol=2`. Scanners now parse `protocol` and `minProtocol`.
- `GET /device` reply: add `protocol` and `minProtocol`.
- `/pair/request` body and reply: add `protocol` and `minProtocol` (new names; the stale
  `protocolVersion` field is left as it is for 0.2.x).
- Upload request headers: add `X-LinkBridge-Protocol` and `X-LinkBridge-Min-Protocol`.
- New error reply, HTTP 426, used by pairing and uploads:
  `{"error":"update_required","update":"sender"|"receiver","protocol":2,"minProtocol":2}`.
  `update` names the side that must update, from the receiver's point of view. The
  existing `426 tls_required` stays as it is.

### Behaviour

- **Device list (Mac, iOS, Android):** an incompatible nearby peer shows the update
  message as its subtitle, with Send and Pair disabled. This applies to both paired and
  unpaired peers.
- **Pairing:** the client reads `GET /device` first and stops with the update message if
  the devices are incompatible. The Mac also refuses an incompatible `/pair/request` with
  426 `update_required`, and the client maps that to the right message. The Mac side
  already shows the message through its device list (from TXT).
- **Sending:** the sender checks compatibility before connecting, when it knows the
  peer's numbers. On a 426 `update_required` it shows "Update LinkBridge on <peer>" or
  "on this Mac/device", depending on `update`.
- **Receiving:** the receiver rejects an incompatible upload with 426 before asking its
  user. It tells its own user once, where it reports incoming problems today (builder's
  call: notification or history entry), rate-limited to once per sender per hour. A
  missing header means protocol 2.
- `docs/protocol.md` gets a "Versions" section with these rules.

### Tests

- `LinkBridgeCoreChecks`: compat function (all three outcomes, legacy inference), TXT
  parsing, device-list subtitles, `/pair/request` 426, upload 426 both directions.
- Android unit tests: same compat cases, TXT parsing, `DeviceListItem` subtitles,
  `ControlClient` handling of 426, receive-server rejection.
- iOS shares the Swift core. Update the iOS `DeviceListItemTests` if subtitles change.

## Design: "new version available" notice

### Source

`https://linkbridge.pratham.si/latest.json`, a static file in the site repo next to the
`/download/*` redirects:

```json
{
  "mac":     { "version": "0.2.1", "build": 3,       "url": "https://linkbridge.pratham.si/download/mac",     "notes": "" },
  "android": { "version": "0.2.1", "versionCode": 3, "url": "https://linkbridge.pratham.si/download/android", "notes": "" }
}
```

- Compare the integer `build` (Mac `BUILD_NUMBER`) or `versionCode` with the running
  app's. The version string is only displayed.
- Open `url` only when it is `https` on `linkbridge.pratham.si`. Otherwise fall back to
  `https://linkbridge.pratham.si/`.
- `notes` is optional, shown as one short line, truncated.
- The site file is published by the website session; this branch only consumes it.

### Checking

- At launch, and every 24 h while running, if the last successful check was over 24 h ago.
- Plain GET with a 10 s timeout. Failures are silent and retried at the next slot.
- Nothing is sent beyond the normal HTTP request: no IDs, and a generic User-Agent.
- Stored locally: last check time, latest seen version, and the version dismissed with
  "Not now".

### Showing

- A quiet banner on the Mac main window and the Android home screen:
  `LinkBridge 0.3.0 is available.  [Download]  [Not now]`.
- "Download" opens the URL in the browser. "Not now" hides the banner until a higher
  build appears.
- The Mac menu bar gets an "Update available…" item while one is pending.
- The Settings toggle "Check for updates" is on by default. Turning it off stops all
  requests and hides the banner.

### Website builds only

- Mac: `scripts/package-mac-app.sh` (the only packaging path today, and the one the DMG
  and zip are made from) writes an Info.plist key (e.g. `LBUpdateCheck`) only when run
  with `WEBSITE_BUILD=1`. The website release steps pass it. When the key is absent, the
  checker and the toggle don't exist, so a future App Store build is safe by default.
- Android: a `BuildConfig.UPDATE_CHECK` boolean is set from a Gradle property that only
  `scripts/build-android-release.sh` passes. It's false by default, and R8 drops the
  dead code.
- iOS: none.
- Privacy page: one line saying website builds check `latest.json` once a day, sending
  nothing else, and can be switched off. The site session owns this text.

### Tests

- Version comparison, URL host check, "Not now" per version, the 24 h gate, and the
  disabled flag (Swift checks and Android unit tests, with the network faked).

## Left to the builder

- Exact file layout, the names of the Info.plist key and Gradle property, and where the
  receiver surfaces an incoming update error.
- Banner styling within each app's existing theme.
