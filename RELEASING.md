# Releasing SeQured Identity for Android

## One-time setup — generate the release keystore

If you lose this keystore, existing installs can never update to a new
release — users would have to uninstall and start over. Back it up to a
safe place (encrypted USB, password manager attachment, etc.) and never
commit it to git.

```sh
cd android

keytool -genkey -v -keystore release.jks \
    -alias sequred-identity \
    -keyalg RSA -keysize 4096 \
    -validity 36500 \
    -dname "CN=Tucker Nelson,O=SeQured Identity,L=,ST=,C=US"

cp keystore.properties.example keystore.properties
# Edit keystore.properties — fill in storePassword + keyPassword. Don't
# commit it; it's already in .gitignore.
```

The keystore + keystore.properties are gitignored. The build picks them up
automatically; if absent, the release build assembles unsigned (useful for
R8 dry-runs but not for distribution).

For CI you can skip keystore.properties and set the four values as env vars
instead: `SQ_STORE_FILE`, `SQ_STORE_PASSWORD`, `SQ_KEY_ALIAS`, `SQ_KEY_PASSWORD`.

## Cutting a release

1. Update the version in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 10100        // MMmmpp: 0.1.1 → 10100
   versionName = "0.1.1"
   ```
2. Update the Rust core if any of its work landed:
   ```sh
   ./build-core.sh
   ```
3. Build the signed release APK:
   ```sh
   ./gradlew :app:assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk` (signed).
4. Compute the SHA-256 for the Releases page:
   ```sh
   shasum -a 256 app/build/outputs/apk/release/app-release.apk
   ```
5. Tag + push:
   ```sh
   git tag -a v0.1.1 -m "v0.1.1 — short summary"
   git push origin v0.1.1
   ```
6. On GitHub: go to
   <https://github.com/TC4NS/sequred-identity-android/releases>,
   click "Create new release", select the tag, upload the APK as a binary
   asset, and paste the SHA-256 + changelog into the description.

Naming convention for the uploaded APK:
```
sequred-identity-v0.1.1.apk
```

## Reproducibility check (optional, for auditors)

Anyone with the same git SHA + the same NDK version should be able to
produce the same APK bytes (minus the V2 signing block, which depends on
your keystore). To verify:

```sh
git checkout v0.1.1
./build-core.sh
./gradlew :app:assembleRelease
# diff their unsigned APK contents against yours via apksigner verify and
# unzip --extract --check.
```

## Distribution

### Releases (sideload)

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk (signed)
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

Rename the APK to `sequred-identity-v<version>.apk`, upload to the
GitHub Releases page along with the SHA-256 in the release body so
sideloaders can verify before installing.

### Reproducible builds (optional but encouraged)

Reproducible builds let auditors verify our signed APK byte-for-byte
matches one they rebuilt from source. We've already laid the groundwork
(pinned Gradle wrapper SHA, locked Cargo + Kotlin dependencies,
deterministic R8 config). To prove reproducibility:

```sh
git checkout v0.1.1
./build-core.sh
./gradlew :app:assembleRelease
# Diff the resulting APK against the published build using `apksigcopier`
# or `diffoscope`. Goal: identical contents minus the V2 signing block.
```

## Legacy: raw AAB build (rarely needed)

```sh
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

We don't currently use the AAB anywhere — the sideload path is plain
APK. Kept here in case a future store requires it.
