# SeQured Identity — Android

> **Your passwords don't exist until you need them.**

A stateless deterministic password manager for Android.

Instead of storing your passwords, SeQured *derives* them on demand from four
factors you provide: a master password, a 6+ digit PIN, the site name, and
your username. The same four inputs always produce the same password, on any
device, with no servers involved. A small encrypted vault on the device
stores credential metadata + optional TOTP seeds — never the passwords
themselves.

The vault file is byte-for-byte interchangeable with the iOS App Store
build, so you can move between platforms by exporting an encrypted
`.sqvault` file.

---

## Install

### Sideload the APK (no app store required)

1. Download the latest signed APK from the [Releases page][releases].
2. Verify the SHA-256 against the value published next to the same release:
   ```sh
   shasum -a 256 sequred-identity-v0.1.0.apk
   ```
3. Transfer to your phone and tap to install. First install needs you to
   allow "Install unknown apps" for whichever browser / file-manager opened
   the APK — Settings → Apps → \[that app\] → Install unknown apps.
4. After install, revoke that permission again. It's a one-time grant.

[releases]: https://github.com/TC4NS/sequred-identity-android/releases

---

## What you get

- Stateless derivation — your passwords don't exist on disk anywhere
- Encrypted on-device vault for credential metadata + TOTP seeds
- Biometric unlock (Face Unlock / fingerprint, gated by Android Keystore)
- Built-in TOTP authenticator (with QR scanner)
- Import from Bitwarden / LastPass / 1Password / Chrome / KeePass / generic CSV
- Encrypted export interchangeable with the iOS app
- No network calls. No analytics. No tracking. No ads.

---

## Privacy + security posture

| What | How |
|---|---|
| Screenshots blocked | `FLAG_SECURE` on every screen — won't appear in Recents thumbnails or screen recording |
| Vault encryption | Argon2id (64 MiB / 3 iters) → AES-256-GCM. Per-vault stored params so older vaults stay decryptable |
| PIN storage | Hashed via Argon2id, stored in `EncryptedSharedPreferences` (AES-256 GCM under an Android Keystore master key) |
| Biometric unlock | Keystore key with `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)` + (API 28+) `setUnlockedDeviceRequired(true)` |
| Clipboard auto-clear | Sensitive copies are flagged `IS_SENSITIVE`, wiped 60 s after copy unless the user has copied something else since |
| Network policy | `usesCleartextTraffic=false`, `networkSecurityConfig` denies all cleartext. App makes zero network calls — verified by `grep` over the source |
| Auto-lock | On background + configurable inactivity timeout (default 5 min). Synchronous lock on resume if expired — no UI flash window |
| Memory wipe | KDF key buffers + decrypted plaintext held in `Zeroizing` in the Rust core; wiped on stack unwind |
| Backup | `allowBackup=false` enforced over library overrides |

Full audit notes live in the
[Releases page changelogs](https://github.com/TC4NS/sequred-identity-android/releases).

---

## License

MPL-2.0 — see [LICENSE](LICENSE). Upstream attributions are in
[NOTICES.md](NOTICES.md).

The iOS App Store build is distributed under a separate proprietary license
owned by the same copyright holder. Both ship from the same MPL-2.0
[Rust core](https://github.com/TC4NS/sequred-identity-core).

MPL-2.0 is purpose-built for this split: modifications to MPL files must
be released as MPL, but combining MPL files with other-licensed files in
a larger work is allowed. Bitwarden's mobile clients, Firefox, and
LibreOffice all use this pattern.

---

## Source layout (for the curious)

| Where | What |
|---|---|
| `app/src/main/kotlin/com/sequred/identity/ui/` | Jetpack Compose UI |
| `app/src/main/kotlin/com/sequred/identity/data/` | Session, vault repo, PIN + biometric stores, import/export |
| `app/src/main/kotlin/com/sequred/identity/crypto/` | Thin wrapper over UniFFI-generated Kotlin bindings |
| `app/src/main/jniLibs/` | Pre-built Rust core `.so` files |
| `app/proguard-rules.pro` | R8 keep rules for UniFFI / JNA / Tink / kotlinx-serialization |
| `build-core.sh` | Cross-compiles the sibling [Rust core](https://github.com/TC4NS/sequred-identity-core), regenerates Kotlin bindings, copies `.so` into `jniLibs/` |

Building from source is documented in
[BUILDING.md](BUILDING.md) — but for normal users, just sideload the APK.
