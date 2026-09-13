# LinkBridge Design System

## Direction

LinkBridge is a private transfer desk for two devices on one local network. The interface should feel calm, technical, and immediate: one clear connection state, one primary transfer action, and quiet operational detail around it.

## Tokens

- Canvas: `#F4F5F1` (paper grey-green)
- Ink: `#10151F` (near-black blue ink)
- Surface: `#FFFFFF`
- Line: `#D9DED8`
- Action: `#3E62E8` (cobalt)
- Action soft: `#E8EDFF`
- Signal: `#35C98B` (mint)
- Signal soft: `#E7F8F0`
- Attention: `#F08A5D` (coral)
- Muted: `#68717D`

## Composition

The transfer canvas is the primary object on both platforms. Connection and device identity sit above it as a single rail; history and diagnostics stay below the action. Cards are framed tools, not decorative containers, with 8-12px corners and thin borders.

## Typography

Use the platform system sans for legibility. Reserve heavy weight for product name, device names, and the primary action. Labels are compact uppercase utility text with generous tracking. Body copy is sentence case and action-oriented.

## Interaction rules

- Show one dominant action at a time.
- Keep the connection state visible while scanning, pairing, transferring, and receiving.
- Pairing and incoming transfers require explicit approval.
- A failed transfer must state the recovery action, not only the error.
- Respect platform focus, reduced motion, and native touch/keyboard behavior.
