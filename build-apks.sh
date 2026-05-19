#!/usr/bin/env bash
# Generate a signed .apks (APK set) bundle for Accrescent submission.
#
# Accrescent does NOT accept monolithic APKs or raw AAB files — it requires
# bundletool's APK set format. This script bridges the standard AGP
# `bundleRelease` output (AAB) to the `.apks` archive Accrescent wants.
#
# Prerequisites:
#   - keystore.properties + release.jks present (see RELEASING.md for the
#     one-time keystore generation).
#   - bundletool installed. The script will auto-download the latest release
#     into tools/bundletool.jar if missing.
#
# Output:
#   app/build/outputs/apkset/release/sequred-identity-<version>.apks

set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f keystore.properties ]]; then
    echo "error: keystore.properties not present — generate the release keystore first (see RELEASING.md)." >&2
    exit 1
fi

KEYSTORE_PATH=$(grep '^storeFile=' keystore.properties | cut -d= -f2-)
KEYSTORE_PASS=$(grep '^storePassword=' keystore.properties | cut -d= -f2-)
KEY_ALIAS=$(grep '^keyAlias=' keystore.properties | cut -d= -f2-)
KEY_PASS=$(grep '^keyPassword=' keystore.properties | cut -d= -f2-)

if [[ -z "${KEYSTORE_PATH:-}" || -z "${KEYSTORE_PASS:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASS:-}" ]]; then
    echo "error: keystore.properties is missing one of: storeFile, storePassword, keyAlias, keyPassword." >&2
    exit 1
fi

# Resolve relative keystore path.
[[ "${KEYSTORE_PATH:0:1}" == "/" ]] || KEYSTORE_PATH="$PWD/$KEYSTORE_PATH"

BUNDLETOOL_VERSION="1.17.2"
BUNDLETOOL_JAR="tools/bundletool-${BUNDLETOOL_VERSION}.jar"
if [[ ! -f "$BUNDLETOOL_JAR" ]]; then
    mkdir -p tools
    echo "▸ Downloading bundletool ${BUNDLETOOL_VERSION}…"
    curl -fsSL -o "$BUNDLETOOL_JAR" \
        "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"
fi

echo "▸ Building signed AAB via ./gradlew :app:bundleRelease …"
./gradlew :app:bundleRelease

AAB="app/build/outputs/bundle/release/app-release.aab"
[[ -f "$AAB" ]] || { echo "error: expected $AAB to exist after bundleRelease" >&2; exit 1; }

VERSION_NAME=$(grep -E '^\s*versionName' app/build.gradle.kts | head -1 | sed 's/.*= "\(.*\)"/\1/')
OUT_DIR="app/build/outputs/apkset/release"
OUT="${OUT_DIR}/sequred-identity-${VERSION_NAME}.apks"
mkdir -p "$OUT_DIR"

echo "▸ Generating signed .apks via bundletool…"
java -jar "$BUNDLETOOL_JAR" build-apks \
    --bundle="$AAB" \
    --output="$OUT" \
    --ks="$KEYSTORE_PATH" \
    --ks-pass="pass:${KEYSTORE_PASS}" \
    --ks-key-alias="$KEY_ALIAS" \
    --key-pass="pass:${KEY_PASS}" \
    --mode=default \
    --overwrite

echo ""
echo "✓ APK set written to:"
echo "  $OUT"
echo ""
echo "SHA-256 (publish this next to the release on GitHub):"
shasum -a 256 "$OUT"
echo ""
echo "Upload $OUT to https://console.accrescent.app — 'New app' or 'New version'."
