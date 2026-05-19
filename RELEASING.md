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

Accrescent **only** accepts `.apks` files (bundletool APK sets) — neither
monolithic APKs nor raw AABs work. The `build-apks.sh` script handles the
full pipeline (AAB build → bundletool sign):

```sh
./build-apks.sh
```

Output: `app/build/outputs/apkset/release/sequred-identity-<version>.apks`
plus a printed SHA-256.

Submission flow (per Accrescent docs):

1. **Account.** Sign-up is currently allowlist-only; request access at
   <https://console.accrescent.app> using your GitHub login.
2. **App ID + domain.** Our app ID `co.sequred.identity` matches the
   `sequred.co` domain Accrescent will ask you to verify. After uploading,
   they'll email a verification code; add it as a DNS `TXT` record at host
   `_accverify.sequred.co` (your registrar's DNS console).
3. **New app.** On console.accrescent.app → "New app" → upload the
   `.apks` file (≤ 1 GiB; ours is ~8 MB) + a 512×512 PNG icon.
4. **App info.** The fields autofill from the bundle. Edit the description
   to call out the CAMERA permission's narrow use (in-app QR scanner for
   importing TOTP secrets) — CAMERA is on Accrescent's sensitive-permission
   list and triggers manual review.
5. **Privacy policy URL.** Point at `https://sequred.co/privacy` (see
   `PRIVACY.md` in this repo for the canonical text).
6. **Submit.** Accrescent assigns a reviewer; once approved, they
   cryptographically sign the metadata and the app appears in the store
   under "My apps".

Subsequent versions: re-run `./build-apks.sh` after bumping
`versionCode`/`versionName`, upload as a new version under the same app.

## Bonus: GitHub Releases (for sideloaders)

Sideloaders prefer plain APKs. After tagging:

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk (signed)
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

Upload that APK to the GitHub Release page with the SHA-256 in the body
so sideloaders can verify before installing.

## Legacy: raw AAB build (rarely needed)

```sh
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

AAB is the intermediate format `build-apks.sh` uses internally. You
shouldn't need to upload the AAB anywhere — Accrescent wants the
`.apks` file, not the AAB.
