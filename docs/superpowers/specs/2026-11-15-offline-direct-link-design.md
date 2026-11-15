# Offline direct link (no Wi-Fi) — design

Status: approved section by section in brainstorming, 2026-11-15. Section 0 and the offline hello were added the same day after planning found Android↔iPhone missing; they need re-approval. Platform claims fact-checked; sources at the end.

## Intent

Today LinkBridge only works when both devices are on the same Wi-Fi. Users asked: what if the Wi-Fi is hostile, or there's a different Wi-Fi, or no Wi-Fi at all? Hostile Wi-Fi is already handled (pairing + TLS-PSK protects transfers once paired). This project covers the **no Wi-Fi nearby** case for every pair: Android↔Mac, iPhone↔Mac, Android↔iPhone. The UX is guided, and automatic wherever the platform allows.

## Goals

- Send files between any two paired (or newly pairing) LinkBridge devices with no router.
- iPhone↔Mac needs no user action.
- Android pairs take a short, guided flow: Android hosts, and the other device joins.
- Pairing, TLS-PSK and the v3 protocol messages are unchanged. The only addition is the offline hello (section 2).
- **New prerequisite (added 2026-11-15, user decision):** Android↔iPhone pairing and two-way transfer, on Wi-Fi and offline. Today neither exists: Android and iOS are both pairing *clients* only, and iOS can't receive. See section 0.

## 0. iPhone as a server (prerequisite)

- The iPhone runs LinkBridgeCore's existing `ControlServer` (plain pairing listener + TLS-PSK receive listener + Bonjour advertisement with `platform=ios`), the same code the Mac runs. iPhone-specific bits (storage paths, approval UI, trust store) plug in through the existing init parameters and hooks; any Mac-only assumptions inside `ControlServer` are factored out, not forked.
- **Android → iPhone pairing:** Android's existing `ControlClient` pairing flow (`/pair/request`, `/pair/confirm`, `/pair/finalize`) targets the iPhone exactly as it targets a Mac. Android treats `platform == "ios"` as a pairable server peer wherever it treats `"macos"` today. The iPhone shows the code and the approval prompt, like the Mac does.
- **Transfers:** iPhone → Android uses the existing `FileSender` path (the same one the Mac uses to send to Android). Android → iPhone uses Android's existing sender against the iPhone's TLS listener.
- **Mac → iPhone** becomes possible with the same server (Claude's call: in scope, since it falls out of the same work; the Mac device list stops saying "coming soon" for iPhones it has paired with).
- **Received files on iPhone** go to the app's Documents folder, visible in the Files app (`UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace`). The approval prompt and size limits match the Mac's.
- **Limit:** the iPhone receives and accepts pairing only while LinkBridge is open in the foreground. iOS suspends background listeners. The server stops on background and restarts on foreground. Senders that find the iPhone gone see "Open LinkBridge on the iPhone".
- Pairing between iPhone and Mac stays iPhone-initiated, as today.

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
  - Mac: join manually → Bonjour and the offline hello to the default gateway (below), in parallel → otherwise manual address entry (Android shows the address under the QR code).
- Unpaired devices pair over the same link with the six-digit code, unchanged.
- **Offline hello (new, replaces the gateway-probe guess).** Android must also find the joiner, because Android → iPhone/Mac sends and Android → iPhone pairing are started by Android. While the hotspot is on, Android opens a plain listener bound to its hotspot address on fixed port **49154**, serving only `POST /offline/hello`.
  - Request body: the joiner's discovery record as JSON, with the same fields as the TXT record: `rid`, `tags`, `deviceName`, `platform`, `protocol`, `port`, `tlsPort`, `features`.
  - Response body: Android's own record in the same shape.
  - Android adds the joiner as a discovered peer at the request's source IP. The joiner adds Android.
  - The iPhone sends hello to the address from the QR code right after joining. The Mac sends hello to its default gateway at 49154 after the manual join. Both also run normal Bonjour, and whichever answers first wins.
  - Manual address entry stays as the last resort on the Mac.
  - The hello listener closes when the hotspot stops.

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
- **Offline hello** reveals only what Bonjour already broadcasts.
  - It is bound to the hotspot address, so only devices that know the hotspot passphrase can reach it.
  - Requests from non-private source addresses are rejected.
  - Bodies are capped at 4 KB with strict JSON field validation.
  - At most 20 hellos per minute.
  - It never pairs or accepts files.
- **iPhone server:** the same pairing approval, transfer approval, rate limits and receive limits as the Mac's `ControlServer`. Received file names are sanitized the same way. The listeners run only in the foreground.
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
- **iPhone in the background:** senders and pairing see "Open LinkBridge on the iPhone". A transfer to the iPhone in progress fails cleanly if it's backgrounded mid-transfer, and the partial file is removed.
- **Hello port 49154 busy on Android:** start the hotspot anyway, skip hello, and the Mac falls back to Bonjour and then manual entry.

## 6. Testing

**Automated (existing suites):**
- QR link builder (Android) and parser (iOS/macOS) with shared test vectors, including rejects.
- `OfflineHotspot` state machine with a fake `WifiManager`: idle timer, failure mapping, system stop.
- Mac fallback order with fake discovery (`LinkBridgeCoreChecks`).
- Peer-to-peer flags set on listener, browser and connection; TLS reuses the resolved interface (`LinkBridgeCoreChecks`).
- iPhone join/cleanup with a fake hotspot manager (`LinkBridgeIOSTests`).
- Existing tag, pairing and TLS tests stay green.
- `ControlServer` with iOS identity and storage: pairing with an Android-style client, receiving a file, declining, and the foreground stop/start (`LinkBridgeCoreChecks` + `LinkBridgeIOSTests`).
- Android `ControlClient` pairs with a `platform=ios` peer; the device list maps `"ios"`.
- Offline hello: request/response round trip, rejects (oversize, bad JSON, public source), rate limit, closes on hotspot stop.
- Manual: Android↔iPhone pair and send both ways on shared Wi-Fi *and* offline; Mac→iPhone send; iPhone backgrounded mid-transfer.

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
- **App Store (iOS), receiving:** add `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace`. The existing local-network purpose string should mention receiving files. `NSBonjourServices` already lists the service type.
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
