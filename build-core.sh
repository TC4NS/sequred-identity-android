#!/usr/bin/env bash
# Rebuild the Rust core for Android and refresh the bundled .so + Kotlin
# bindings inside app/src/main/. Run this any time you change anything under
# ../core/ — Android Studio does not re-trigger Cargo on its own.

set -euo pipefail

# Locate the NDK. Honour ANDROID_NDK_HOME if exported, otherwise pick the
# newest one installed by Android Studio.
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_PARENT="${HOME}/Library/Android/sdk/ndk"
    if [[ ! -d "${NDK_PARENT}" ]]; then
        echo "error: NDK not found under ${NDK_PARENT}. Install via Android Studio SDK Manager." >&2
        exit 1
    fi
    ANDROID_NDK_HOME="${NDK_PARENT}/$(ls -1 "${NDK_PARENT}" | sort -V | tail -1)"
fi
export ANDROID_NDK_HOME

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${REPO_ROOT}/core"
APP_MAIN="${REPO_ROOT}/android/app/src/main"

echo "▸ Cross-compiling Rust core for arm64-v8a + x86_64…"
cd "${CORE_DIR}"
cargo ndk -t arm64-v8a -t x86_64 -o jniLibs build --release

echo "▸ Generating Kotlin bindings from compiled library…"
cargo run --bin uniffi-bindgen -- generate \
    --library target/aarch64-linux-android/release/libsequred_core.so \
    --language kotlin \
    --out-dir target/uniffi-bindings

echo "▸ Copying artifacts into app/src/main/…"
mkdir -p "${APP_MAIN}/jniLibs/arm64-v8a" "${APP_MAIN}/jniLibs/x86_64"
mkdir -p "${APP_MAIN}/kotlin/uniffi/sequred_core"
cp jniLibs/arm64-v8a/libsequred_core.so "${APP_MAIN}/jniLibs/arm64-v8a/"
cp jniLibs/x86_64/libsequred_core.so    "${APP_MAIN}/jniLibs/x86_64/"
cp target/uniffi-bindings/uniffi/sequred_core/sequred_core.kt \
   "${APP_MAIN}/kotlin/uniffi/sequred_core/sequred_core.kt"

echo "✓ Native core refreshed. Re-sync Gradle in Android Studio if needed."
