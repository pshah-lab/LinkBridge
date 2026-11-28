# LinkBridge Local Protocol

## Goals

- Work on a local Wi-Fi network without cloud routing.
- Pair intentionally, with a visible confirmation on both devices.
- Keep transfer and display transports separate so file transfer can ship first.

## Discovery

Devices advertise an mDNS/Bonjour service:

```text
_linkbridge._tcp.local
```

TXT records:

```text
rid=<16 random bytes, hex, new each launch>
tags=<comma-separated discovery tags, at most 14>
deviceName=<human-readable-name>
platform=macos|android
protocol=2
minProtocol=2
tlsPort=<TLS-PSK port for transfers>
features=file-transfer,display-send,display-receive
```

There is no stable device ID in TXT. For each paired device the advertiser publishes
`tag = hex(HMAC-SHA256(K, rid)[0..8])` with `K = HKDF-SHA256(secret, salt "linkbridge-discovery-v1",
info "tag", 32)`. A scanner computes the tag for each of its own peers to recognize them;
unpaired devices learn the ID from `GET /device` when pairing. Scanners still accept a
`deviceId` key from older advertisers.

The advertised port is the control server port. The Mac prefers 49152 and picks any
free port if that one is taken; peers must use the port from discovery.

## Versions

Each device has a **protocol** (what it speaks, now `2`) and a **minimum protocol** (the oldest it
still works with, now `2`). Two devices work together when each one's protocol is at least the
other's minimum. Otherwise the device that notices tells its user which device to update:

- peer's protocol below my minimum → "Update LinkBridge on <peer name>"
- my protocol below the peer's minimum → "Update LinkBridge on this Mac" (Android, iOS: "this device")

Where the numbers travel (all additive, so 0.2.x keeps working):

- TXT: `protocol`, `minProtocol`.
- `GET /device` and the `/pair/request` body and reply: `"protocol"` and `"minProtocol"`. The older
  `protocolVersion` field is stale on every platform and is ignored.
- Uploads: `X-LinkBridge-Protocol` and `X-LinkBridge-Min-Protocol` headers.

Missing values: no `minProtocol` means 2. No `protocol` but a `tlsPort` (TXT), or no fields at all
(JSON, headers), means protocol 2. No `protocol` and no `tlsPort` means protocol 1 (predates TLS).
Values that aren't positive integers count as missing.

A receiver that can't work with the requester answers before asking its user:

```text
426 {"error":"update_required","update":"sender"|"receiver","protocol":2,"minProtocol":2}
```

`update` names the side that must update. Senders write the whole body before reading the reply, so
after authenticating the sender a receiver reads and discards the rest of the body (for up to 60 s)
before answering `426`, `409` or (Mac) `429`; closing with unread data would reset the connection
and lose the reply. The requester shows the matching message; a Mac or
Android receiver also tells its own user, at most once per sender per hour. The device list shows
the message as the device's subtitle and turns Send and Pair off for it. `426 tls_required` on the
plain port is unchanged.

## Pairing

Each device holds a P-256 key-agreement key (Keychain on macOS, AndroidKeyStore on
Android). Public keys are sent as base64 of the 65-byte uncompressed X9.63 point
(`0x04 || X || Y`).

Pairing version 4 (current): the initiator sends `pairingVersion: 4` in `/pair/request` and the
responder echoes it. Both sides refuse anything lower (`426` on the Mac), since the version
travels over plain HTTP. v4 is v3 plus the finalize confirmation in step 4. The shared secret is
`HKDF-SHA256(ECDH x-coordinate, salt = phoneNonce || macNonce, info = "linkbridge-pair-v3" || phoneId(16) || macId(16), 32)`,
so each pairing of the same two devices gives a new secret. Device IDs are the 16 UUID bytes in
RFC 4122 order.

The phone initiates. The Mac commits to a random nonce before it sees the phone's
nonce, so an attacker in the middle cannot try keys offline until the codes match:
its only option is a one-in-a-million guess.

1. `POST /pair/request` with `{deviceId, deviceName, platform, protocolVersion, features, publicKey}`.
   The Mac replies `{status: "pending", publicKey, commitment, pairingVersion}` where
   `commitment = SHA-256("linkbridge-commit-v1" || macKey || macNonce)`.
2. `POST /pair/confirm` with `{deviceId, nonce}` (32 random bytes, base64). Accepted once per request.
   The Mac replies `{status: "awaiting_approval", nonce: macNonce}` and shows an approval
   dialog. The phone checks the commitment.
3. Both devices show
   `code = uint32_be(SHA-256("linkbridge-code-v1" || phoneKey || macKey || phoneNonce || macNonce)[0..4]) mod 1_000_000`
   formatted as six digits. In v3 the hash is
   `SHA-256("linkbridge-code-v2" || phoneKey || macKey || phoneNonce || macNonce || phoneId || macId)`,
   with `macId` taken from `GET /device`. The phone shows the code; the Mac user types it, and
   the Mac's Pair button stays disabled until it matches.
4. `POST /pair/finalize` with `{deviceId}` blocks until the Mac user decides:
   `200 {status: "paired", confirm}` or `403 {error: "pair_declined"}`, where
   `confirm = base64(HMAC-SHA256(Kc, "linkbridge-confirm-v4" || u8 version || phoneKey || macKey || phoneNonce || macNonce || phoneId || macId))`
   and `Kc = HKDF-SHA256(secret, salt = empty, info = "linkbridge-confirm-v4", 32)`. The phone
   checks `confirm` in constant time and fails pairing if it is missing or wrong.
5. The phone saves nothing yet. It asks the user whether the Mac lists the phone as paired,
   and saves the Mac only on yes. `confirm` can't prove that on its own: a MITM holds the
   phone-side secret and can compute it. Cancel or No at any point saves nothing. If the
   phone already trusts the Mac's device ID, it warns that pairing replaces it, and warns
   more strongly (with a second confirmation) when the Mac's pairing key changed.
6. If the phone cancels while it waits in step 4, it sends `POST /pair/cancel` with
   `{deviceId}` (best effort). The Mac declines that pairing, drops it, and closes its dialog,
   so the phone can pair again at once. Only the address and device that started the pairing
   can cancel it; the reply is always `200 {status: "cancelled"}`, so it reveals nothing.

Confirm and finalize must come from the address that sent the request. At most one
unconfirmed pairing per address and 8 in total are kept (`429` beyond that). Device names are
cut to one line of 40 visible characters. Pending pairings expire after 110 seconds. Headless mode (`linkbridge-cli`) declines
all pairing; pair from the LinkBridge app.

After confirmation, each side stores the peer device ID, display name and shared secret
(the secret goes in secure storage).

## Control Channel

There are two channels:

- **Plain HTTP** on the advertised service port (Mac only): `/health`, `/device` and the
  `/pair/*` endpoints. Pairing needs no prior secret and is protected by the six-digit
  code. An upload sent here gets `426 {"error":"tls_required"}`.
- **TLS-PSK** on `tlsPort` (both platforms): transfers between paired devices.

### TLS-PSK

- TLS 1.2 only, cipher suite `TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256` (forward
  secrecy). Apple's Network.framework does not complete TLS 1.3 external-PSK handshakes.
- PSK = HKDF-SHA256(pairing secret, salt `linkbridge-tls-v1`, info `psk`, 32 bytes).
- PSK identity = the connecting device's ID, lowercased (the Mac writes UUIDs uppercase).
- The server selects the key by identity; an unknown identity or wrong key fails the
  handshake. Android also requires the upload's `X-LinkBridge-Device-Id` to match the
  TLS identity.
- BouncyCastle servers must not echo the `status_request` extension; Apple clients reject
  a ServerHello that acknowledges OCSP stapling without a certificate.

Uploads keep the application-layer encryption and HMAC described below inside TLS.

Initial endpoints:

```text
GET  /health
GET  /device
POST /pair/request
POST /pair/confirm
POST /pair/finalize
POST /pair/cancel
POST /transfer/upload   (TLS-PSK port only)
```

## File Transfer

A transfer is a single streaming `POST /transfer/upload` to the receiver's TLS-PSK port.

Headers:

```text
X-LinkBridge-Device-Id:   sender device ID (must be a trusted peer)
X-LinkBridge-Timestamp:   milliseconds since epoch (must be within 5 minutes)
X-LinkBridge-Transfer-Id: uuid (rejected if seen in the last 10 minutes)
X-LinkBridge-Encryption:  aes-gcm-chunked-v2
X-LinkBridge-Protocol:    2 (see Versions; not signed, only used to explain a refusal)
X-LinkBridge-Min-Protocol: 2
X-LinkBridge-Signature:   base64 HMAC-SHA256(macKey, "POST\n/transfer/upload\n<deviceId>\n<timestamp>\n<transferId>\n<scheme>\n<contentLength>")
```

The device ID must match the TLS-PSK identity of the connection.

v2 keys: `encKey` and `macKey` are `HKDF-SHA256(secret, salt "linkbridge-chunk-v2", info "enc" / "mac", 32)`.
The body is a sequence of records, each `nonce(12) || lengthField(4, big-endian) || ciphertext+tag`,
with plaintext of at most 1 MiB. The top bit of `lengthField` marks the last record. Each record is
sealed with AAD `transferId(16) || recordIndex(8, big-endian) || lengthField(4)`. Nothing may follow
the last record, and a body without one is rejected. Receivers also accept `aes-gcm-chunked-v1`
(the raw secret for both keys, no AAD, no last-record marker) from older senders. The first record is encrypted JSON metadata:

```json
{ "transferId": "uuid", "fileName": "video.mp4", "bytes": 48129312, "sha256": "hex" }
```

The remaining records are the file contents. Before asking its user, the receiver refuses
(`507 insufficient_storage`) a file that would leave less than 512 MB free. On the Mac it also
refuses (`429`) a second pending prompt from the same sender, more than 3 prompts in total, and
uploads within 30 s of the user declining that sender. The file is written to a hidden
`.linkbridge-<transferId>.part` (Android: `IS_PENDING`) and gets its real name only after the
length and SHA-256 check. The receiver then replies `{status: "received", fileName, bytes, sha256}`.

## Wireless Display

Wireless display is intentionally deferred until the pairing and transfer base is stable.

Planned transport:

- WebRTC for NAT resilience and built-in jitter handling.
- H.264 baseline profile first for hardware decode support.
- Android capture via `MediaProjection`.
- macOS capture via `ScreenCaptureKit`.

