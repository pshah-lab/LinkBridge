# Offline direct link (no Wi-Fi) — design

Status: approved section by section in brainstorming, 2026-11-15. Platform claims fact-checked; sources at the end.

## Intent

Today LinkBridge only works when both devices are on the same Wi-Fi. Users asked: what if the Wi-Fi is hostile, or there's a different Wi-Fi, or no Wi-Fi at all? Hostile Wi-Fi is already handled (pairing + TLS-PSK protects transfers once paired). This project covers the **no Wi-Fi nearby** case for every pair: Android↔Mac, iPhone↔Mac, Android↔iPhone. The UX is guided, and automatic wherever the platform allows.

## Goals

- Send files between any two paired (or newly pairing) LinkBridge devices with no router.
- iPhone↔Mac needs no user action.
- Android pairs take a short, guided flow: Android hosts, and the other device joins.
- Pairing, TLS-PSK and the v3 protocol are unchanged.

## Non-goals

- Different Wi-Fi networks / internet relay.
- Android↔Android offline.
- Pairing by QR code (the QR code never carries pairing secrets).
- Resuming an interrupted transfer (the protocol has no resume today).
- Offline mode on Android 12/12L.
- Screen mirroring over the offline link.

## Anti-goals

- Requesting location permission on Android, or anything that makes LinkBridge look like it tracks location.
- New Mac sandbox entitlements or exceptions.
- Leaving a saved network, a running hotspot, or a listener open after a session ends.
- Any way for a web page or message to make a device join a network (the QR link works only through the in-app scanner).

## Approaches

Approach A was chosen: **Android LocalOnlyHotspot plus Apple peer-to-peer (AWDL)**. It reuses the existing TCP/TLS stack unchanged over a real IP link on every pair, and it needs no restricted entitlements. Alternatives were rejected: Wi-Fi Direct / Wi-Fi Aware have no Apple counterpart, and Bluetooth is too slow for files and would need a second transport stack.

## Current state (checked in code)

- `PeerBrowser.swift`, `PeerResolver.swift`, `ControlServer.swift`: Bonjour via `NWBrowser`/`NWListener`; `includePeerToPeer` is never set.
- `FileSender.swift:74` connects to `NWEndpoint.Host(host)` + port from the resolved record (`PeerBrowser` stores `host: String`).
- `ControlServer.advertisedService()` advertises the plain listener; the TXT record has `rid`, `tags`, `deviceName`, `platform`, `protocol`, `tlsPort`, `features`. The TLS listener isn't advertised separately.
- Android `DiscoveryService.kt` uses `NsdManager` and doesn't bind to a network. `minSdk 31`, `targetSdk 36`.
- macOS app is sandboxed (network client + server entitlements).

## 1. Architecture

| Pair | Link | Join |
|---|---|---|
| iPhone↔Mac | AWDL via `includePeerToPeer` | automatic |
| Android↔iPhone | Android LocalOnlyHotspot | iPhone scans the QR code in-app → `NEHotspotConfiguration` |
| Android↔Mac | Android LocalOnlyHotspot | Guided manual join from the Wi-Fi menu (CoreWLAN can't change network config under the sandbox) |

- **LinkBridgeCore (shared macOS/iOS):** `includePeerToPeer = true` on both listeners, the browser and client connections.
- **Android `OfflineHotspot` (new):** wraps `WifiManager.startLocalOnlyHotspot`. The system picks the SSID and passphrase. There's **no platform idle timeout**: our own timer stops the hotspot (`reservation.close()`) after 10 minutes without transfers.
- **Android networking:** while the hotspot is on, the receiver socket and NSD are restricted to the hotspot interface.
- Pairing and TLS-PSK run unchanged over all links.

## 2. Discovery

- v3 TXT record and tag matching are unchanged; no protocol version bump.
- **AWDL:** peer records keep the Bonjour service `NWEndpoint` instead of `host: String`. To reach the TLS listener: connect to the service, read the resolved remote address and interface from the connection path, then connect to that address at `tlsPort` with `requiredInterface` pinned. *Unverified until tested on devices.* Fallback: advertise the TLS listener as its own Bonjour service.
- **Hotspot:** Android is always the host and the gateway. The QR code is a LinkBridge link (scanned in-app only) carrying the SSID, passphrase, Android's IPv4 address, port and `tlsPort`. No secrets beyond the hotspot passphrase.
  - iPhone: join → Bonjour (about 3 s) → otherwise connect directly to the address from the QR code.
  - Mac: join manually → Bonjour → otherwise probe the default gateway at the default port → otherwise manual address entry (Android shows the address under the QR code).
- Unpaired devices pair over the same link with the six-digit code, unchanged.

## 3. UX flow

- A "No Wi-Fi? Connect directly" card appears on each Devices screen when the device has no Wi-Fi, or after about 5 s with no peers.
- **iPhone↔Mac:** paired devices just appear, with a "Direct" label.
- **Android host:** "Start offline link" → nearby-devices permission (first time) → screen with the QR code, network name, password, address, "Stops after 10 min idle", and a Stop button.
- **iPhone:** "Scan Android's code" → camera permission (first time) → iOS "Join network?" → Android appears. Don't use `joinOnce` (it drops when the app goes to the background). Remove the configuration when the session ends.
- **Mac:** a 3-step guide (start on Android; pick the network in the Wi-Fi menu and type the password; wait). The password is read from the Android screen; there's nothing to copy on the Mac.
- **Warnings:** "This network has no internet." On Android: "Your phone may leave its current Wi-Fi while this is on" (device-dependent, unverified).
- **End:** Stop on Android, or 10 minutes idle. Peers go back to normal discovery.
- **Android 12/12L:** the card says "Needs Android 13 or later".

## 4. Security

- Transfers are protected by TLS-PSK. The hotspot passphrase only controls who can join the network.
- A photographed QR code lets someone join the hotspot, but not read transfers, impersonate a paired device, or pair without the six-digit code and approval.
- A fake address or gateway is defeated by TLS-PSK (paired) or commit/reveal (pairing).
- The QR link is untrusted input. Accept only: the LinkBridge scheme, a private IPv4 address (10/8, 172.16/12, 192.168/16), ports 1–65535, and bounded field lengths. A scan never pairs or accepts files on its own. Don't register the link as a system URL handler.
- AWDL widens who can see the Bonjour ad (nearby Apple devices). Tags already rotate per launch. `deviceName` stays visible, as on Wi-Fi today (user decision).
- Android: the receiver listens only on the hotspot interface while the hotspot is on. Existing `ConnectionGate` and approval prompts are unchanged.
- New permissions: iPhone gets the Hotspot Configuration entitlement and a camera purpose string. Android gets `CHANGE_WIFI_STATE` and `NEARBY_WIFI_DEVICES` (`neverForLocation`). The Mac gets none.

## 5. Errors

Each error names what happened and gives one next step, and never leaves state behind.

- **Android hotspot start failure** (tethering on, another app holds it, Wi-Fi off, unsupported): a specific message for each cause, plus Retry. Never toggle system settings.
- **Permission denied:** explain, with an Open Settings button.
- **System stops the hotspot:** "Offline link stopped". End transfers and clean up partial files as today.
- **iPhone:** cancelled join returns to the scan screen. On join failure, remove the config and say "move closer and scan again". An invalid QR shows "This isn't a LinkBridge code". If iOS leaves the no-internet network, show a Rejoin button.
- **Mac:** if no Android is found, use the fallback chain and then manual entry. On the wrong network, say "check the name on Android".
- **AWDL drop or hang:** 10-second connect timeout. A transfer in progress fails with "Connection lost". No automatic resend.
- **TLS failure:** "This device needs to pair again", as on Wi-Fi.

## 6. Testing

**Automated (existing suites):**
- QR link builder (Android) and parser (iOS/macOS) with shared test vectors, including rejects.
- `OfflineHotspot` state machine with a fake `WifiManager`: idle timer, failure mapping, system stop.
- Mac fallback order with fake discovery (`LinkBridgeCoreChecks`).
- Peer-to-peer flags set on listener, browser and connection; TLS reuses the resolved interface (`LinkBridgeCoreChecks`).
- iPhone join/cleanup with a fake hotspot manager (`LinkBridgeIOSTests`).
- Existing tag, pairing and TLS tests stay green.

**Manual, on real devices with no router, recorded in the PR:**
- iPhone↔Mac: pair, send 1 and 50 files, leave range mid-transfer.
- Android→iPhone and Android→Mac: send both ways; check the Mac fallback; iPhone returns to normal Wi-Fi.
- Android: idle stop, stop from Settings, permission denied.
- Any: a stranger's QR code; a photographed QR code used to try pairing (must fail without the code).
- Android 13+ devices only.

## 7. Store policy

- **Play:** offline mode is Android 13+ only, so there's no location permission, no precise-location declaration, no prominent-disclosure screen and no Data safety change. Declare `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"`. Gate the feature at runtime on API 33+, and keep `minSdk 31` for the rest of the app.
- **Android 17** moves local network access to `ACCESS_LOCAL_NETWORK`. That's app-wide, so it's tracked separately on the roadmap.
- **App Store (iOS):** add the Hotspot Configuration capability (no Apple approval needed) and the camera purpose string. Explain offline mode in the review notes.
- **Mac App Store:** no change.
- **Privacy labels:** unchanged on both stores.

## Delivery (user decision)

All three pairs ship together in one release. The open risks below are still checked on devices first, inside the same project, because a failed check changes the design (e.g. the TLS-as-its-own-service fallback). They don't change the release scope.

## Calls made after review (Claude's, correctable)

- **Which Android is it?** The QR code also carries Android's current `rid` and `tags` (the same public values as in the TXT record), so on the direct-address fallback the scanner can match the paired-device record and choose the PSK without Bonjour.
- **Idle:** the 10-minute timer runs only while no transfer is active, no approval prompt is pending, and no pairing is in progress. An open but quiet connection doesn't count as activity.
- **Android listeners:** keep the existing receiver sockets. While the hotspot is on, accept only connections whose local address is on the hotspot interface. No second listener.
- **Card trigger:** show "No Wi-Fi? Connect directly" whenever no peers are found after about 5 s, whether or not Wi-Fi is connected.

## Left to the builder

- Names of the new types, files and screens. Where the offline card sits in each Devices screen.
- The exact LinkBridge QR link format (scheme, field encoding), within the constraints in section 4.
- How the Android hotspot interface is found and bound (e.g. `ConnectivityManager` / `Network` or interface-address lookup).
- Exact copy for each message, in the apps' existing tone.

## Open risks (verify early)

1. Whether Bonjour discovery works over the LocalOnlyHotspot (mDNS between the softAP and its clients).
2. AWDL connection hangs reported by other developers; the TLS-over-resolved-interface step.
3. Android devices that drop their Wi-Fi client connection when the hotspot starts.

## Sources checked

- Android: [LocalOnlyHotspot guide](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot), [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager), [LocalOnlyHotspotReservation](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotReservation), [Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions), [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- Play: [Permissions and sensitive APIs](https://support.google.com/googleplay/android-developer/answer/16585319?hl=en), [Minimum-scope location](https://support.google.com/googleplay/android-developer/answer/17033915?hl=en), [Prominent disclosure](https://support.google.com/googleplay/android-developer/answer/11150561?hl=en), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- Apple: [Hotspot Configuration entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.networking.hotspotconfiguration), [forum: no special approval](https://developer.apple.com/forums/thread/703017), [CoreWLAN associate](https://developer.apple.com/documentation/corewlan/cwinterface/1426455-associatetonetwork?language=objc), [forum: CoreWLAN vs. sandbox (2016)](https://developer.apple.com/forums/thread/44807), [WWDC19 Advances in Networking pt. 2](https://developer.apple.com/videos/play/wwdc2019/713/), [forum: P2P listener/AWDL](https://developer.apple.com/forums/thread/718461), [forum: iOS 26 AWDL](https://developer.apple.com/forums/thread/808917)
- Forum sources are anecdotal; CoreWLAN sandbox guidance is from 2016 with no newer reversal found.
