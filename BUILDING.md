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

```sh
./build-core.sh               # rebuild Rust core + regenerate Kotlin bindings
./gradlew :app:assembleDebug  # builds app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release build

See [RELEASING.md](RELEASING.md) for the signed-build flow (keystore
generation, R8, the manual GitHub Release upload).

## Reproducibility

This is set up to produce reproducible builds for F-Droid-style auditing:

- `gradle/wrapper/gradle-wrapper.properties` pins
  `distributionSha256Sum=31c55713…`
- All build-time dependencies are pinned to exact versions in
  `app/build.gradle.kts`
- The Rust core compiles deterministically — `core/Cargo.lock` is committed

Two builds of the same git SHA on the same NDK version should produce
byte-identical APKs (modulo the V2 signing block, which is per-keystore).
