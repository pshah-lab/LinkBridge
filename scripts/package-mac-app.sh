#!/usr/bin/env bash
# Build the LinkBridge Mac app bundle at dist/LinkBridge.app.
#
#   ./scripts/package-mac-app.sh            # release build for this Mac, ad-hoc signed
#   ARCHES="arm64 x86_64" ./scripts/package-mac-app.sh   # universal binary
#   APP_IDENTITY="Apple Development: ..." ./scripts/package-mac-app.sh   # real signing
#
# Based on the macos-spm-app-packaging skill template.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MACOS_DIR="$ROOT_DIR/macos"
CONF=${CONF:-release}
APP_IDENTITY=${APP_IDENTITY:-}

source "$MACOS_DIR/version.env"

APP="$ROOT_DIR/dist/${APP_NAME}.app"
ENTITLEMENTS="$MACOS_DIR/${APP_NAME}.entitlements"

ARCH_LIST=( ${ARCHES:-$(uname -m)} )

cd "$MACOS_DIR"

# Newer toolchains (Swift 6.4+) can fail in the default build system with
# "Unknown error parsing property list"; fall back to the native one.
# Prints the built binary's path; the two build systems use different output folders.
build() {
  local arch="$1"
  if swift build -c "$CONF" --arch "$arch" --product "$APP_NAME" >&2; then
    echo "$(swift build -c "$CONF" --arch "$arch" --show-bin-path)/$APP_NAME"
  else
    echo "Default build system failed; retrying with --build-system native" >&2
    swift build -c "$CONF" --arch "$arch" --product "$APP_NAME" --build-system native >&2
    echo "$(swift build -c "$CONF" --arch "$arch" --build-system native --show-bin-path)/$APP_NAME"
  fi
}

binaries=()
for arch in "${ARCH_LIST[@]}"; do
  binaries+=("$(build "$arch")")
done

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

if [[ ${#binaries[@]} -gt 1 ]]; then
  lipo -create "${binaries[@]}" -output "$APP/Contents/MacOS/$APP_NAME"
else
  cp "${binaries[0]}" "$APP/Contents/MacOS/$APP_NAME"
fi
chmod +x "$APP/Contents/MacOS/$APP_NAME"

if [[ -f "$MACOS_DIR/Resources/AppIcon.icns" ]]; then
  cp "$MACOS_DIR/Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
fi

GIT_COMMIT=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>${APP_NAME}</string>
    <key>CFBundleDisplayName</key><string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key><string>${BUNDLE_ID}</string>
    <key>CFBundleExecutable</key><string>${APP_NAME}</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>${MARKETING_VERSION}</string>
    <key>CFBundleVersion</key><string>${BUILD_NUMBER}</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>LSMinimumSystemVersion</key><string>${MACOS_MIN_VERSION}</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSLocalNetworkUsageDescription</key>
    <string>LinkBridge finds your phone on this Wi-Fi network and transfers files directly between your devices. Nothing goes through the internet.</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_linkbridge._tcp</string>
    </array>
    <key>GitCommit</key><string>${GIT_COMMIT}</string>
</dict>
</plist>
PLIST
plutil -lint "$APP/Contents/Info.plist" >/dev/null

# Strip extended attributes so the code signature seals cleanly.
xattr -cr "$APP"

if [[ -n "$APP_IDENTITY" ]]; then
  codesign --force --timestamp --options runtime --entitlements "$ENTITLEMENTS" --sign "$APP_IDENTITY" "$APP"
else
  # Ad-hoc: fine for local testing, not for distribution.
  codesign --force --entitlements "$ENTITLEMENTS" --sign - "$APP"
fi

echo "Created $APP (${MARKETING_VERSION} build ${BUILD_NUMBER}, ${ARCH_LIST[*]})"
