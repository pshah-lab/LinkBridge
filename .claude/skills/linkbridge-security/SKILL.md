---
name: linkbridge-security
description: Security review of LinkBridge (Mac, iOS, Android LAN file transfer). Use when asked to review, audit or harden LinkBridge security: pairing, TLS-PSK, the upload path, secret storage, file handling on receive, Android components, or store-submission security checks. Holds the threat model, the files that matter, the checklist and the report format.
---

# LinkBridge security review

LinkBridge moves files between paired devices on the same Wi-Fi with no server.
Every trust decision is made on the device, so the review is about the local
protocol, what each app exposes on the LAN, and how secrets are stored.

## Threat model

Attackers, in priority order:

1. **Someone on the same Wi-Fi** (café, office, shared flat). Can see and inject
   traffic, browse Bonjour, connect to any open port, and try pairing.
2. **A malicious app on the same phone or Mac.** Can send intents, read
   world-readable files, or reach localhost ports.
3. **A paired device that turns hostile** or is stolen. Holds a valid secret.
4. **Someone holding an unlocked device for a minute.**

Out of scope: an attacker with root on the device, or one who can watch the user
compare the six-digit code.

Assets: the files being sent, pairing secrets and identity keys, the received
files folder, and the user's trust (a fake "approve" dialog is an attack).

## How the protocol works (read `docs/protocol.md` for detail)

- Discovery: Bonjour/NSD `_linkbridge._tcp`. TXT carries deviceId, deviceName,
  platform, tlsPort. All of it is unauthenticated.
- Pairing: plain HTTP to the Mac's port 49152 (`/device`, `/pair/request`,
  `/pair/confirm`, `/pair/finalize`). P-256 ECDH, then a commit/reveal nonce
  exchange so both sides show the same six-digit code. The Mac user approves.
  The shared secret is SHA-256 of the ECDH output.
- Transfer: TLS-PSK keyed from the pairing secret (Mac 49162, Android 49153,
  iOS sends only). On top of TLS, the upload is chunked AES-GCM with an HMAC
  signature over method, path, deviceId, timestamp, transferId, scheme and size,
  plus a replay cache. The receiver can ask its user to accept the file.

## Files that matter

Shared Swift core (`macos/Sources/LinkBridgeCore/`), used by Mac and iOS:
- `ControlServer.swift`: hand-written HTTP parsing, pairing handlers, upload
  handler, file name sanitizing, replay cache, approval flow.
- `PairingCode.swift`, `PairingKeyStore.swift`, `PairingClient.swift`: pairing crypto.
- `SecureTransport.swift`: TLS-PSK parameters. `Security.swift`: AES-GCM chunks, HMAC.
- `CredentialStore.swift`: data-protection Keychain on iOS and on Team-ID-signed Mac
  builds (which migrate and delete the old file); a 0600 JSON file only for ad-hoc
  signed Mac builds.
- `TrustedPeerStore.swift`, `DeviceIdentity.swift`, `FileSender.swift`, `PeerBrowser.swift`.

Mac app: `macos/Sources/LinkBridge/` (approval UI, notifications) and
`macos/LinkBridge.entitlements` (sandbox, network server/client, Downloads read-write).

iOS app: `ios/LinkBridgeIOS/`, `ios/Info.plist` (`NSAllowsLocalNetworking`, Bonjour).

Android (`android/app/src/main/java/com/linkbridge/android/`): `ReceiveServer.kt`,
`SecureTransport.kt` (BouncyCastle TLS-PSK), `ControlClient.kt` (pairing over
HttpURLConnection, needs a cleartext exception), `Security.kt`, `PairingCode.kt`,
`PairingKeyStore.kt`, `CredentialStore.kt`, `ShareActivity.kt`,
`LinkBridgeService.kt`, plus `AndroidManifest.xml` and the network security config.

## Checklist

Work through these and cite file:line for each finding.

**Pairing**
- Commit/reveal is checked before the code is shown, on every initiator (Swift and Kotlin).
- Comparisons of commitments and MACs are constant-time.
- Only one pending approval at a time; pairing spam can't stack dialogs or exhaust memory.
- Pending pairings expire; a stale nonce can't be replayed.
- Re-pairing a known deviceId warns the user and replaces the secret cleanly.
- The six-digit code's guess rate: can an attacker retry fast enough to matter?

**Transport and upload**
- TLS-PSK cipher suites, TLS version, and identity handling match on all three platforms.
- An unpaired or revoked device is rejected before any file bytes are written.
- HMAC covers every field that matters; timestamp window and replay cache are bounded.
- Size limits: header size, small-body size, declared vs actual length, disk-full handling.
- Hand-written HTTP parsing: malformed requests, slowloris, huge headers, partial reads.

**Files on receive**
- File names: path traversal (`../`, absolute paths, Unicode tricks), hidden files,
  overwriting existing files, very long names.
- Partial or failed transfers leave no half-written file that looks complete.
- SHA-256 is checked before the file is reported as received.

**Secrets**
- Where each secret lives on each platform, its protection class, and whether it syncs or backs up.
- macOS file store: permissions, sandbox container path, plan to move back to Keychain.
- No secrets in logs, notifications, crash reports or UserDefaults.

**Platform surface**
- Android: exported components, intent handling in ShareActivity, foreground service
  type, network security config scope, permissions (use the `android-intent-security`
  and `android-permissions-security` skills).
- Mac: entitlements are the minimum needed; Bonjour TXT doesn't leak more than needed.
- iOS: ATS exception is local-only; Keychain accessibility class.
- UI: can a nearby attacker make an approval dialog look like it came from a trusted device?

## Report format

Write findings to `docs/security/findings-YYYY-MM-DD.md`, most severe first:

```
### [High] Short title
- Where: path/to/file.swift:123
- What: one or two sentences.
- Attack: who (from the threat model), what they do, what they get.
- Fix: the concrete change.
```

Severity: Critical (remote file write, secret theft, pairing bypass), High
(DoS of the receiver, info leak of secrets or files), Medium (hardening gaps
with a plausible attack), Low (defence in depth). Mark anything you couldn't
confirm as "Unconfirmed".

Fix findings on a branch, one finding per commit. Keep
`swift run --build-system native LinkBridgeCoreChecks` (in `macos/`) and
`./gradlew :app:testDebugUnitTest` (in `android/`) passing, and add a check for
each fixed bug where practical.

## Related skills

- `swift-security`: Keychain, CryptoKit and Apple security APIs.
- `android-intent-security`, `android-permissions-security`, `play-policy-insights`.
- `/security-review`: quick review of a branch's changes.
- `claude-security` plugin: full multi-agent scan. Heavy on tokens; use once before store submission.
