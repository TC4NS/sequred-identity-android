# SeQured Identity — Android

A stateless deterministic password manager. Your passwords are *derived* from
master + PIN + site + username on demand, not stored. A small encrypted vault
holds credential metadata + optional TOTP seeds. Biometric unlock supported.

Cross-platform vault file format is interchangeable with the iOS app.

## Distribution

- **Accrescent** (recommended): coming soon.
- **Sideload APK**: download the signed APK from the Releases page.
- **F-Droid**: not currently planned (the iOS App Store version is paid;
  releasing on F-Droid as well would undercut that. Subject to change.)

## Build from source

Prerequisites:

- Android Studio (Iguana / Koala or newer)
- Android NDK (auto-detected from `~/Library/Android/sdk/ndk/<latest>` or
  set `ANDROID_NDK_HOME`)
- Rust toolchain with `aarch64-linux-android` + `x86_64-linux-android`
  targets, and `cargo-ndk`:
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
- The sibling [`sequred-identity-core`](../sequred-identity-core/) repo
  checked out at `../sequred-identity-core/` (the build script reads from
  `../core/` relative to this repo).

Build steps:

```sh
# 1. Rebuild the Rust core + regenerate Kotlin bindings + copy .so to jniLibs.
./build-core.sh

# 2. Build the debug APK.
./gradlew :app:assembleDebug

# 3. Install on a connected device.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first launch creates a vault under `/data/data/com.sequred.identity/files/vault.enc`.

## Architecture

| Layer | Where | Notes |
|---|---|---|
| UI | `app/src/main/kotlin/com/sequred/identity/ui/` | Jetpack Compose + Material 3 |
| Session / state | `data/VaultSession.kt` | StateFlow of Locked / NeedsSetup / Unlocked |
| Persistence | `data/VaultRepository.kt`, `data/PinStore.kt`, `data/BiometricStore.kt` | EncryptedSharedPreferences for PIN + biometric; atomic vault file w/ fsync |
| Crypto FFI | `crypto/CoreBridge.kt` | Thin wrapper over UniFFI-generated Kotlin bindings |
| Rust core | sibling [sequred-identity-core](../sequred-identity-core/) | Argon2id, AES-256-GCM, PBKDF2-SHA3-256, fingerprint, export envelope |

## Security posture

- `FLAG_SECURE` globally → screenshots / Recents thumbnails / screen recording blocked.
- `EncryptedSharedPreferences` for PIN hash + throttle counter + biometric ciphertext.
- Argon2id 64 MiB / 3 iters for vault keys (auto-upgrades from older params on unlock).
- Biometric Keystore key: `setUserAuthenticationRequired(true)` + `setUnlockedDeviceRequired(true)` (API 28+) + `setInvalidatedByBiometricEnrollment(true)`.
- Clipboard auto-clears 60 s after a sensitive copy.
- Vault writes fsync the file + parent dir before completing the rename.
- Zero network calls (`usesCleartextTraffic=false` + a network_security_config that denies cleartext as defense in depth).
- Auto-lock on background, configurable inactivity timeout, synchronous idle check on resume.

## License

GPL-3.0 — see [LICENSE](LICENSE).

The iOS App Store version of SeQured Identity is distributed under a separate
proprietary license owned by the same copyright holder. Both ship from the
same Rust core.
