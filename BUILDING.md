# Building SeQured Identity for Android

> Most users should just [sideload the APK](README.md#sideload-the-apk).
> This page is for contributors, auditors, and people who want to compile
> their own.

## Prerequisites

- Android Studio (Iguana / Koala or newer) with the Android SDK + NDK
- JDK 21 (Android Studio's bundled JBR works)
- Rust toolchain with Android targets:
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
- The sibling [`sequred-identity-core`](https://github.com/TC4NS/sequred-identity-core)
  repo checked out at `../sequred-identity-core/` (the build script reads
  from `../core/` relative to this repo).

## Quick build (debug APK)

**First time after cloning, you MUST run `./build-core.sh`** — the
compiled Rust core `.so` files are not in git (they're build artifacts,
not source). The build script cross-compiles them into
`app/src/main/jniLibs/` where the Android build expects them.

```sh
./build-core.sh               # rebuild Rust core + regenerate Kotlin bindings
./gradlew :app:assembleDebug  # builds app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You only need to re-run `build-core.sh` when the sibling `core/` repo
changes. Day-to-day Kotlin changes can use `:app:assembleDebug` directly.

## Release build

See [RELEASING.md](RELEASING.md) for the signed-build flow (keystore
generation, R8, the manual GitHub Release upload).

## Reproducibility

This is set up to produce reproducible builds for independent auditing:

- `gradle/wrapper/gradle-wrapper.properties` pins
  `distributionSha256Sum=31c55713…`
- All build-time dependencies are pinned to exact versions in
  `app/build.gradle.kts`
- The Rust core compiles deterministically — `core/Cargo.lock` is committed

Two builds of the same git SHA on the same NDK version should produce
byte-identical APKs (modulo the V2 signing block, which is per-keystore).
