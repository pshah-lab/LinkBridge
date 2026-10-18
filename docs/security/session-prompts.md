# Security session prompts

Copy-paste prompts for running a LinkBridge security review in a fresh Claude Code
session. Each one loads the project skill `linkbridge-security`
(`.claude/skills/linkbridge-security/SKILL.md`), which holds the threat model,
file list, checklist and report format.

Run them in order. Each is sized for one session, to fit a tight usage plan.
Start a new session for the next prompt rather than continuing a long one.

## 1. Pairing (start here: highest risk)

```
Use the linkbridge-security skill. Review the pairing flow only: PairingCode,
PairingKeyStore, PairingClient and the /pair/* handlers in ControlServer.swift,
plus android ControlClient.kt, PairingCode.kt and PairingKeyStore.kt. Follow the
Pairing section of the checklist. Write findings to
docs/security/findings-<today>.md. Don't fix anything yet. Keep output short.
```

## 2. Transport and upload

```
Use the linkbridge-security skill. Review TLS-PSK and the upload path:
SecureTransport.swift, Security.swift, FileSender.swift and handleUpload plus
the HTTP parsing in ControlServer.swift; android SecureTransport.kt,
ReceiveServer.kt and Security.kt. Check that the three platforms agree on TLS
settings and that a malicious paired or unpaired peer can't write files, exhaust
memory or disk, or replay an upload. Append findings to today's findings file.
Don't fix yet.
```

## 3. Files on receive and secret storage

```
Use the linkbridge-security skill and the swift-security skill. Review (a) how
received files are named, written and verified on Mac and Android (path
traversal, overwrites, partial files) and (b) where every secret lives on each
platform: CredentialStore.swift (Keychain on iOS, 0600 file on macOS),
TrustedPeerStore, android CredentialStore.kt, backups and logs. Append findings.
Don't fix yet.
```

## 4. Platform surface

```
Use the linkbridge-security skill with android-intent-security and
android-permissions-security. Review AndroidManifest.xml (two exported
components), ShareActivity, LinkBridgeService, network_security_config.xml and
data_extraction_rules.xml; macos/LinkBridge.entitlements; ios/Info.plist. Also
check whether a LAN attacker can spoof the approval dialogs on Mac. Append
findings. Don't fix yet.
```

## 5. Fix

```
Read docs/security/findings-<date>.md. Fix the Critical and High findings on a
new branch, one finding per commit, adding a check in LinkBridgeCoreChecks or an
Android unit test for each where practical. Keep `swift run --build-system native
LinkBridgeCoreChecks` (macos/) and `./gradlew :app:testDebugUnitTest` (android/)
passing. List Medium and Low findings you didn't fix. Don't push.
```

For heavy implementation in this step, consider `/model opus` and switching back afterwards.

## 6. Before store submission (once)

```
Run /security-review on the current branch, then play-policy-insights on the
Android app. Summarize anything new in today's findings file.
```

The full `claude-security` plugin scan is the most thorough option but uses a
lot of tokens. Save it for one pass right before the first App Store / Play
submission:

```
Use the claude-security agent to scan this repository and write patch files for
what it finds. I understand it will use a lot of tokens.
```

## Quick prompt for any new feature

```
Use the linkbridge-security skill. Check only the changes on this branch
against the checklist and threat model. Report findings inline, most severe first.
```
