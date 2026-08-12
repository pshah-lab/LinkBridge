#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"

source "$ROOT_DIR/scripts/check-android-env.sh"

if [[ ! -f "$APK" ]]; then
  printf "Debug APK not found. Building first...\n"
  "$ROOT_DIR/scripts/build-android.sh"
fi

"${ANDROID_HOME}/platform-tools/adb" install -r "$APK"
