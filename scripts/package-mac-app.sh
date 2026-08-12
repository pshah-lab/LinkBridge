#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MACOS_DIR="$ROOT_DIR/macos"
APP_DIR="$ROOT_DIR/dist/LinkBridge.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_APP_DIR="$CONTENTS_DIR/MacOS"

cd "$MACOS_DIR"
swift build -c release

rm -rf "$APP_DIR"
mkdir -p "$MACOS_APP_DIR"

cp "$MACOS_DIR/.build/release/LinkBridgeMac" "$MACOS_APP_DIR/LinkBridgeMac"

cat > "$MACOS_APP_DIR/LinkBridge" <<'SCRIPT'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/LinkBridgeMac" ui
SCRIPT
chmod +x "$MACOS_APP_DIR/LinkBridge"

cat > "$CONTENTS_DIR/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>LinkBridge</string>
    <key>CFBundleIdentifier</key>
    <string>local.linkbridge.mac</string>
    <key>CFBundleName</key>
    <string>LinkBridge</string>
    <key>CFBundleDisplayName</key>
    <string>LinkBridge</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>0.1.0</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSLocalNetworkUsageDescription</key>
    <string>LinkBridge uses the local network to discover nearby devices and transfer files.</string>
</dict>
</plist>
PLIST

echo "Created $APP_DIR"

