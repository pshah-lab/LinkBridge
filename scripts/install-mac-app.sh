#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT_DIR/dist/LinkBridge.app"
TARGET="/Applications/LinkBridge.app"

"$ROOT_DIR/scripts/package-mac-app.sh"

if [[ -d "$TARGET" ]]; then
  osascript -e 'tell application "LinkBridge" to quit' >/dev/null 2>&1 || true
  sleep 1
  rm -rf "$TARGET"
fi

ditto "$APP" "$TARGET"
open "$TARGET"
printf 'Installed private LinkBridge macOS app at %s.\n' "$TARGET"
