#!/usr/bin/env bash
# Wraps dist/LinkBridge.app (from package-mac-app.sh) in a compressed DMG with an Applications shortcut.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/macos/version.env"
APP="$ROOT_DIR/dist/LinkBridge.app"
[[ -d "$APP" ]] || { echo "Missing $APP; run scripts/package-mac-app.sh first" >&2; exit 1; }

VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")
DMG="$ROOT_DIR/dist/LinkBridge-$VERSION-macOS-arm64.dmg"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

ditto "$APP" "$STAGE/LinkBridge.app"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -volname "LinkBridge" -srcfolder "$STAGE" -fs APFS -format UDZO -ov "$DMG" >/dev/null
printf 'Created %s\n' "$DMG"
