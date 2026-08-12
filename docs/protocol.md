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
protocol=1
features=file-transfer,display-send,display-receive
```

The advertised port is the local HTTPS control server port.

## Pairing

Pairing starts with an out-of-band code shown as text and QR:

```json
{
  "protocol": 1,
  "deviceId": "uuid",
  "deviceName": "Pratham's MacBook",
  "host": "192.168.1.24",
  "port": 49152,
  "publicKey": "base64-ed25519-public-key"
}
```

The peer confirms a six-digit code derived from both public keys. After confirmation, each side stores:

- Peer device ID
- Peer display name
- Peer public key
- Last known endpoint

## Control Channel

The control channel uses HTTPS or WebSocket over TLS.

Initial endpoints:

```text
GET  /health
GET  /device
POST /pair/request
POST /pair/confirm
POST /transfer/request
POST /transfer/chunk
POST /transfer/complete
```

## File Transfer

File transfer is chunked to support progress, pause/resume, and large files.

Transfer request:

```json
{
  "transferId": "uuid",
  "name": "video.mp4",
  "size": 48129312,
  "mimeType": "video/mp4",
  "sha256": "hex"
}
```

Chunk request:

```json
{
  "transferId": "uuid",
  "offset": 0,
  "data": "base64"
}
```

## Wireless Display

Wireless display is intentionally deferred until the pairing and transfer base is stable.

Planned transport:

- WebRTC for NAT resilience and built-in jitter handling.
- H.264 baseline profile first for hardware decode support.
- Android capture via `MediaProjection`.
- macOS capture via `ScreenCaptureKit`.

