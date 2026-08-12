#!/usr/bin/env bash
set -euo pipefail

missing=0
DEFAULT_BREW_ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"

check_command() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    printf "ok: %s -> %s\n" "$name" "$(command -v "$name")"
  else
    printf "missing: %s\n" "$name"
    missing=1
  fi
}

check_command java
check_command gradle
check_command sdkmanager

if ! command -v adb >/dev/null 2>&1 && [[ -x "$DEFAULT_BREW_ANDROID_HOME/platform-tools/adb" ]]; then
  export PATH="$DEFAULT_BREW_ANDROID_HOME/platform-tools:$PATH"
fi

check_command adb

if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platforms" ]]; then
  printf "ok: ANDROID_HOME=%s\n" "$ANDROID_HOME"
elif [[ -d "$DEFAULT_BREW_ANDROID_HOME/platforms" ]]; then
  export ANDROID_HOME="$DEFAULT_BREW_ANDROID_HOME"
  printf "ok: ANDROID_HOME=%s\n" "$ANDROID_HOME"
else
  printf "missing: ANDROID_HOME\n"
  missing=1
fi

if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  printf "ok: ANDROID_SDK_ROOT=%s\n" "$ANDROID_SDK_ROOT"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  printf "ok: ANDROID_SDK_ROOT=%s\n" "$ANDROID_SDK_ROOT"
else
  printf "missing: ANDROID_SDK_ROOT\n"
fi

if [[ "$missing" -ne 0 ]]; then
  printf "\nSee docs/android-cli.md for setup.\n"
  exit 1
fi
