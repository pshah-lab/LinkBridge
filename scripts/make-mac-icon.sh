#!/usr/bin/env bash
# Regenerates macos/Resources/AppIcon.icns from design/icon/linkbridge-icon.svg.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SVG="$ROOT_DIR/design/icon/linkbridge-icon.svg"
OUT="$ROOT_DIR/macos/Resources/AppIcon.icns"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ICONSET="$WORK/AppIcon.iconset"
mkdir -p "$ICONSET" "$(dirname "$OUT")"
sips -s format png "$SVG" --out "$WORK/1024.png" >/dev/null

for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$WORK/1024.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$WORK/1024.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done

iconutil -c icns "$ICONSET" -o "$OUT"
printf 'Created %s\n' "$OUT"
