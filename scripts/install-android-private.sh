#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT_DIR/android/app/build/outputs/apk/release/app-release.apk"

source "$ROOT_DIR/scripts/check-android-env.sh"

"$ROOT_DIR/scripts/build-android-release.sh"

"${ANDROID_HOME}/platform-tools/adb" install -r "$APK"
printf 'Installed private LinkBridge release on the connected Android device.\n'
