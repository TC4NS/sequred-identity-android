# Releasing SeQured Identity for Android

## One-time setup — generate the release keystore

If you lose this keystore, you cannot ship updates to the same app on
Accrescent (or any other store). Back it up to a safe place (encrypted USB,
password manager attachment, etc.) and never commit it to git.

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

## Pushing to Accrescent

Accrescent accepts AABs, not APKs, for new submissions. Build one with:

```sh
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

Upload at <https://accrescent.app/console>. First submission requires the
app ID (`com.sequred.identity`) to be claimed and basic metadata
(description, screenshots, privacy policy) to be filled in.
