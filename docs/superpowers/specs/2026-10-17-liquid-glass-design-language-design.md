# LinkBridge redesign — Part 1: design language

Status: draft for review · 2026-10-17

## Goal

Give LinkBridge a modern, store-ready interface before the App Store and Play
launch: real Liquid Glass on macOS 26 and iOS 26, and Material 3 Expressive
with glass surfaces on Android 12+. Behaviour (pairing, send, receive,
history) stays the same; only presentation and navigation change.

This document is the shared design language. It replaces `DESIGN.md` when
Part 2 lands. Each platform gets its own spec, plan and PR, in this order:

1. Design language (this document)
2. iOS — already SwiftUI; proves the language first
3. macOS — AppKit → SwiftUI rewrite, menu bar popover + window
4. Android — Views → Jetpack Compose with Material 3 Expressive

Out of scope: protocol changes, receiving on iOS, screen mirroring.

## Decisions

| Decision | Choice |
|---|---|
| Apple minimum OS | macOS 26, iOS 26 (raised from 13 / 16). One code path, no material fallback. |
| Android look | Material 3 Expressive + blurred translucent surfaces; not an iOS imitation. |
| Android minimum | Unchanged: Android 12 (API 31). |
| Mac shape | Menu bar popover (`MenuBarExtra`) for quick sends and approvals, plus a full window. |

## Direction

Keep the "calm private transfer desk" idea: one clear connection state, one
dominant action, quiet detail around it. Content sits on a soft tinted
backdrop; glass lives only on the control layer floating above it.

### Glass rules

- Glass on: app frame (tab bar / toolbar / nav bar), connection rail, the
  primary Send control, approval sheets.
- Never glass on: lists, history rows, the drop zone, body text.
- Apple: neighbouring glass elements share one `GlassEffectContainer` so they
  blend and morph. Tint glass only to signal state (cobalt = action, coral =
  needs attention), never decoratively.
- Android: blur via the Haze library over the content layer. When blur is
  unavailable or disabled (battery saver, low-end GPU, Reduce Transparency
  equivalent), fall back to an opaque-ish surface fill at the same tone.

## Tokens

Accents carry over from `DESIGN.md`; neutrals become system-adaptive.

| Role | Light | Dark | Use |
|---|---|---|---|
| Action | `#3E62E8` cobalt | `#7C95FF` | Primary button, links, selection |
| Signal | `#35C98B` mint | `#4ADBA0` | Connected / success |
| Attention | `#F08A5D` coral | `#FF9F78` | Errors, approvals waiting |
| Backdrop tint | cobalt at 6% over system background | cobalt at 10% over system background | Behind content |
| Text / surfaces | System label and background colours | same | Everything else |

- Android: dynamic colour on (Android 12+); cobalt is the seed when dynamic
  colour is off. Mint and coral stay fixed as semantic colours.
- Typography: system font everywhere (SF on Apple; the device font via the
  M3 type scale on Android).
- Dark-mode accent values above are starting points; adjust during Part 2 if
  they miss the 4.5:1 contrast target.
  Pairing codes use monospaced digits at large size.
- Shapes: Apple uses system glass shapes (capsule buttons, concentric
  corners). Android uses M3 Expressive shape scale.

## Navigation

| Platform | Frame |
|---|---|
| iOS | `TabView` with glass tab bar: Devices · Send · History. Settings is a toolbar button. |
| macOS window | `NavigationSplitView`, sidebar: Devices · History · Settings. Send is the window's main area for the selected device. |
| macOS menu bar | `MenuBarExtra` popover: connection rail, compact drop target, last 3 transfers, inline approvals, "Open LinkBridge". |
| Android | Floating glass navigation bar: Devices · Send · History. Settings in the top app bar. |

## Shared components

| Component | Content | iOS | macOS | Android |
|---|---|---|---|---|
| App frame | Navigation above | Glass tab bar | Split view + popover | Blurred floating nav bar |
| Connection rail | Active device name + state dot (mint connected, grey searching, coral error) | Glass capsule at top | Capsule in toolbar and popover header | Blurred chip in top bar |
| Device row | Icon, name, platform, last seen, paired badge | Solid list row | Same | M3 `ListItem` |
| Send surface | Drop / pick zone + one primary Send control that morphs into a progress pill | Floating glass button | Drop zone fills main area; compact target in popover | Expressive FAB → progress |
| Approval sheet | Pairing: 6-digit code, Accept / Reject. Incoming file: name, size, sender, Accept / Decline | Glass sheet | Window sheet; inline row in popover | Bottom sheet over blurred scrim |
| History row | Name, size, direction arrow, time, status; failed rows show Retry | Solid row | + "Show in Finder" | + "Open" |
| Empty / error state | One sentence + one action, e.g. "No devices yet — check both are on the same Wi-Fi" | Same everywhere | | |

## Motion

- The Send control morphs into the progress pill and back to a result
  (check or Retry) — `glassEffectID` + namespace on Apple, shared-element /
  shape morph on Android.
- Sheets rise from the control that triggered them where the platform allows.
- Durations follow platform defaults; no custom springs beyond M3 Expressive's
  motion scheme.
- Reduce Motion: morphs become cross-fades; no scale or bounce.

## Accessibility

- Contrast: text on glass must reach 4.5:1 against the worst-case backdrop.
  Keep text off glass where possible (glass holds icons and short labels).
- Reduce Transparency (Apple) / blur disabled (Android): glass renders as an
  opaque surface; verify every screen in this mode.
- Increase Contrast: rely on system glass behaviour; custom tints get a
  darker border.
- Dynamic Type / font scale up to the largest accessibility size: rows wrap,
  the rail truncates the device name, nothing clips.
- Every control has a label (VoiceOver / TalkBack); state dot also has a
  text label ("Connected", "Searching", "Error").
- Hit targets: 44 pt Apple, 48 dp Android.
- Keyboard: full keyboard navigation on macOS; Return accepts and Escape
  declines on approval sheets.

## Verification (applies to Parts 2–4)

Each platform PR is done when:

1. Every screen and state (empty, searching, connected, sending, failed,
   approval pending) is captured in light, dark, Reduce Transparency and the
   largest text size — iOS Simulator, macOS window and popover, Android
   emulator — and the screenshots are attached to the PR.
2. Existing tests still pass (`LinkBridgeCoreChecks`, `:app:testDebugUnitTest`).
3. A real-device run: pair, send both ways, accept an incoming file, see it in
   history (Mac ↔ the user's Android phone; iOS → Mac in the simulator).
4. VoiceOver / TalkBack can complete pair and send without sight.
