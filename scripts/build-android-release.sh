#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/check-android-env.sh"

: "${LINKBRIDGE_KEYSTORE:?Set LINKBRIDGE_KEYSTORE to your private .jks path}"
: "${LINKBRIDGE_KEYSTORE_PASSWORD:?Set LINKBRIDGE_KEYSTORE_PASSWORD without committing it}"
: "${LINKBRIDGE_KEY_ALIAS:?Set LINKBRIDGE_KEY_ALIAS}"
: "${LINKBRIDGE_KEY_PASSWORD:?Set LINKBRIDGE_KEY_PASSWORD without committing it}"

cd "$ROOT_DIR/android"
./gradlew :app:assembleRelease \
  -PlinkbridgeStoreFile="$LINKBRIDGE_KEYSTORE" \
  -PlinkbridgeStorePassword="$LINKBRIDGE_KEYSTORE_PASSWORD" \
  -PlinkbridgeKeyAlias="$LINKBRIDGE_KEY_ALIAS" \
  -PlinkbridgeKeyPassword="$LINKBRIDGE_KEY_PASSWORD"

printf '\nCreated signed APK: %s\n' "$ROOT_DIR/android/app/build/outputs/apk/release/app-release.apk"
