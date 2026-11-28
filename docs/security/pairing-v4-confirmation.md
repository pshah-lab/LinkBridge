# Pairing v4: initiator-side confirmation (F1, F2)

Status: implemented (approved 2026-11-23). Targets a release after 0.2.2.

## Problem

The phone (initiator) saves trust after any 2xx from plain-HTTP `/pair/finalize`. Only the
Mac user checks the six-digit code. A LAN attacker who answers the phone's pairing calls
becomes a trusted peer, and can take over the real Mac's deviceId (F1). On Android, Cancel
doesn't stop the background finalize from saving trust (F2).

## Why a MAC alone is not enough

A MAC over the transcript, keyed from the pairing secret, proves the sender holds the
**phone-side** secret. In a MITM (phone <-K1-> attacker <-K2-> Mac) the attacker holds K1,
so it can send a valid MAC while the real Mac rejects the codes (c1 != c2). The MAC blocks
only weaker attacks: a spoofed or raced finalize reply from someone outside the key
exchange, and a downgrade of the version. Commit/reveal plus the user check on the Mac
already make c1 = c2 hold only when there is no MITM. What's missing is a way for the phone
to learn the result of that check. Only the user can tell it.

## Design

1. **Pairing v4 + responder MAC (defense in depth).** The Mac's 200 reply to `/pair/finalize`
   becomes `{status: "paired", confirm}`, where
   - `Kc = HKDF-SHA256(ikm = pairing secret, salt = "", info = "linkbridge-confirm-v4", 32)`
   - `confirm = HMAC-SHA256(Kc, "linkbridge-confirm-v4" || u8 version || initiatorKey ||
     responderKey || initiatorNonce || responderNonce || initiatorId(16) || responderId(16))`

   The initiator checks it in constant time (`MessageDigest.isEqual`, or CryptoKit's
   `HMAC.isValidAuthenticationCode`). A missing or wrong `confirm` fails pairing. Both sides
   send `pairingVersion: 4` and refuse anything lower. The version is inside the MAC, so
   stripping it breaks the MAC. Rollout: a v4 phone can't pair with a v3 Mac, and a v3 phone
   can't pair with a v4 Mac. Both get an "update required" message, the same way v1/v2 are
   handled today.
2. **User confirms on the phone (the real F1 fix).** After a valid finalize, the phone does
   not save yet. It asks: "Does <Mac> list this phone as paired?" with **Yes** and **No**.
   Trust is saved only on Yes. After Pair, the phone appears in the Mac's device list. The
   Mac's Pair button stays disabled until the typed code matches, so if the codes differ or
   no request reached it, the phone never shows up there, and the user answers No. This is
   Bluetooth numeric-comparison style: each device gets confirmation from the user. No Mac
   UI change was needed.
3. **Cancel never saves (F2), on Android and iOS.** The finalize result is applied only if
   the pairing generation is still current and the user hasn't cancelled. The finalize no
   longer runs in `appScope`; only the save after Yes does. If the Mac accepted after the phone cancelled, the Mac holds a one-sided
   entry, which the user can remove. Sending `/pair/cancel` is left as a follow-up.
4. **Replacing an existing pairing.** Trusted peers gain an optional `pairingKey` (the
   peer's P-256 public key, x9.63 base64), saved by the initiator (Android, iOS). The Mac
   keeps its existing `replacesExisting` warning.
   On the code screen:
   - same deviceId, same key: show "Already paired. Pairing again replaces it."
   - same deviceId, different key: show a stronger warning ("This device's identity
     changed. If you didn't reset or reinstall LinkBridge on it, cancel.") and make the
     user confirm a second time.
   - old entries without a key: the first warning.

## Tests

- Mac checks (`LinkBridgeCoreChecks`): valid `confirm` accepted. Wrong, missing or
  truncated `confirm` rejected. MAC computed with version 3 rejected (downgrade). A v3
  `/pair/request` refused with 426. `PairingClient` doesn't save without the user's
  confirmation, and doesn't save after cancel.
- Android unit tests: `PairingCode.confirm` matches a Swift-generated vector. Wrong or
  missing `confirm` fails `finalizePairing`. Version 3 in the MAC is rejected. Cancel during
  Waiting saves nothing. Answering No saves nothing. A key change is flagged.
