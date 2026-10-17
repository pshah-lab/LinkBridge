#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_DIR="${LINKBRIDGE_KEYSTORE_DIR:-$HOME/.linkbridge}"
KEYSTORE_PATH="${LINKBRIDGE_KEYSTORE:-$KEYSTORE_DIR/linkbridge-release.jks}"
KEY_ALIAS="${LINKBRIDGE_KEY_ALIAS:-linkbridge-release}"

if [[ -e "$KEYSTORE_PATH" ]]; then
  printf 'Keystore already exists: %s\n' "$KEYSTORE_PATH"
  exit 0
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"
printf 'Create a private Android release keystore. Passwords are not saved in the repository.\n'
read -r -s -p 'Keystore password: ' STORE_PASSWORD
printf '\n'
read -r -s -p 'Key password: ' KEY_PASSWORD
printf '\n'

keytool -genkeypair \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -dname 'CN=LinkBridge Private Release, OU=LinkBridge, O=Private, C=IN'

chmod 600 "$KEYSTORE_PATH"
printf '\nCreated private keystore: %s\n' "$KEYSTORE_PATH"
printf 'Use scripts/build-android-release.sh with the same passwords.\n'
