# LinkBridge Design System

The design language lives in
[docs/superpowers/specs/2026-10-17-liquid-glass-design-language-design.md](docs/superpowers/specs/2026-10-17-liquid-glass-design-language-design.md):
Liquid Glass on macOS 26 / iOS 26, Material 3 Expressive with glass surfaces on Android.

## Tokens

| Role | Light | Dark | Label on a fill of it |
|---|---|---|---|
| Action (cobalt) | `#3E62E8` | `#7C95FF` | white (light), ink `#10151F` (dark) |
| Signal (mint) | `#35C98B` | `#4ADBA0` | ink `#10151F` |
| Attention (coral) | `#F08A5D` | `#FF9F78` | ink `#10151F` |
| Backdrop | cobalt 6% over system background | cobalt 10% over system background | — |

Labels on accent fills must reach 4.5:1; `ThemeTests` in the iOS app checks every pair.
Everything else uses system colours.

## Rules

- Glass only on controls: tab bar / toolbar, connection rail, the primary Send control, sheets. Lists, history and the drop zone stay solid.
- One dominant action per screen.
- Pairing and incoming transfers require explicit approval.
- A failed transfer states the recovery action, not only the error.
- Respect Reduce Motion, Reduce Transparency, Dynamic Type and native touch/keyboard behaviour.

iOS screenshots of the current design: `docs/screenshots/ios/`.
