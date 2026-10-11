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
deviceId=<stable-install-id>
deviceName=<human-readable-name>
platform=macos|android
protocol=2
tlsPort=<TLS-PSK port for transfers>
features=file-transfer,display-send,display-receive
```

The advertised port is the control server port. The Mac prefers 49152 and picks any
free port if that one is taken; peers must use the port from discovery.

## Pairing

Each device holds a P-256 key-agreement key (Keychain on macOS, AndroidKeyStore on
Android). Public keys are sent as base64 of the 65-byte uncompressed X9.63 point
(`0x04 || X || Y`). The shared transfer secret is `SHA-256(ECDH(x-coordinate))`.

The phone initiates. The Mac commits to a random nonce before it sees the phone's
nonce, so an attacker in the middle cannot try keys offline until the codes match:
its only option is a one-in-a-million guess.

1. `POST /pair/request` with `{deviceId, deviceName, platform, protocolVersion, features, publicKey}`.
   The Mac replies `{status: "pending", publicKey, commitment}` where
   `commitment = SHA-256("linkbridge-commit-v1" || macKey || macNonce)`.
2. `POST /pair/confirm` with `{deviceId, nonce}` (32 random bytes, base64). Accepted once per request.
   The Mac replies `{status: "awaiting_approval", nonce: macNonce}` and shows an approval
   dialog. The phone checks the commitment.
3. Both devices show
   `code = uint32_be(SHA-256("linkbridge-code-v1" || phoneKey || macKey || phoneNonce || macNonce)[0..4]) mod 1_000_000`
   formatted as six digits. The user compares the codes and accepts on both devices.
4. `POST /pair/finalize` with `{deviceId}` blocks until the Mac user decides:
   `200 {status: "paired"}` or `403 {error: "pair_declined"}`.

Pending pairings expire after three minutes. Headless mode (`linkbridge-cli`) declines
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
POST /transfer/upload   (TLS-PSK port only)
```

## File Transfer

A transfer is a single streaming `POST /transfer/upload` to the receiver's TLS-PSK port.

Headers:

```text
X-LinkBridge-Device-Id:   sender device ID (must be a trusted peer)
X-LinkBridge-Timestamp:   milliseconds since epoch (must be within 5 minutes)
X-LinkBridge-Transfer-Id: uuid (rejected if seen in the last 10 minutes)
X-LinkBridge-Encryption:  aes-gcm-chunked-v1
X-LinkBridge-Signature:   base64 HMAC-SHA256(secret, "POST\n/transfer/upload\n<deviceId>\n<timestamp>\n<transferId>\n<scheme>\n<contentLength>")
```

The body is a sequence of records, each `nonce(12) || length(4, big-endian) || ciphertext+tag`,
with plaintext of at most 1 MiB. The first record is encrypted JSON metadata:

```json
{ "transferId": "uuid", "fileName": "video.mp4", "bytes": 48129312, "sha256": "hex" }
```

The remaining records are the file contents. The receiver asks its user to accept, then checks
the length and SHA-256 before it replies `{status: "received", fileName, bytes, sha256}`.

## Wireless Display

Wireless display is intentionally deferred until the pairing and transfer base is stable.

Planned transport:

- WebRTC for NAT resilience and built-in jitter handling.
- H.264 baseline profile first for hardware decode support.
- Android capture via `MediaProjection`.
- macOS capture via `ScreenCaptureKit`.

