# Privacy Policy — SeQured Identity

_Last updated: 2026-05-19_

SeQured Identity is a stateless deterministic password manager for Android.
The short version of this policy: **it doesn't collect, transmit, or store
anything about you anywhere off your device. There are no servers, no
accounts, no telemetry.**

The long version is below.

## What data the app handles

| Category | Stored where | Encryption | Sent anywhere? |
|---|---|---|---|
| Master password | In RAM during the active session only; cleared on lock | n/a — never persisted | No |
| PIN hash | `/data/data/co.sequred.identity/shared_prefs/sq.pin.enc.xml` | EncryptedSharedPreferences (AES-256-GCM under an Android Keystore master key) | No |
| Biometric-wrapped PIN | `/data/data/co.sequred.identity/shared_prefs/sq.bio.enc.xml` | Android Keystore key requires user authentication on every use | No |
| Encrypted vault (sites, usernames, emails, TOTP seeds) | `/data/data/co.sequred.identity/files/vault.enc` | Argon2id(PIN) → AES-256-GCM | No |
| Derived site passwords | Never stored anywhere | Derived on demand from master + PIN + site + username | No |

The app makes **zero network requests**. This is enforced at the manifest
level with `android:usesCleartextTraffic="false"` and a network security
config that denies all cleartext traffic. There are no analytics, no
crash reporters, no telemetry SDKs, no ad libraries. You can verify by
running `grep -r 'http\|okhttp\|retrofit\|firebase\|google\|analytics'
src/` over the source.

## Permissions and what they're used for

| Permission | Used for | Granted only when |
|---|---|---|
| `USE_BIOMETRIC` | Biometric unlock of your PIN, via Android Keystore | You opt in via Settings → Biometric unlock |
| `CAMERA` | In-app QR scanner that imports `otpauth://` TOTP seeds when adding a 2FA code | You tap the QR scan button on an entry / authenticator edit screen |

No other permissions are requested. The camera permission is never used
outside the QR scanning flow.

## Data shared with third parties

None.

## Data shared with us (Quillcore LLC / Tucker Nelson)

None. We have no servers. We could not collect your data if we wanted to —
the app does not have the network permission to talk to us.

## Children

The app is not directed at children but is also safe for them under the
same terms: no data collection means no collection of children's data
either.

## Changes

Updates to this policy will be published at
`https://sequred.co/privacy` with a new "Last updated" date. Material
changes will be called out in the in-app release notes.

## Contact

If you have questions or believe a logo bundled with the app infringes
your trademark, open an issue at
<https://github.com/TC4NS/sequred-identity-android/issues>.

## Verifying these claims

Because the entire source is published under MPL-2.0 at
<https://github.com/TC4NS/sequred-identity-android>, anyone can audit the
above. The two material checks are:

1. `grep -r 'INTERNET\|usesCleartextTraffic\|networkSecurityConfig'
   app/src/main/AndroidManifest.xml` — should show only the deny-cleartext
   and security-config lines, no `INTERNET` permission.
2. `grep -rE 'http(s)?://|Url|okhttp|retrofit|firebase' app/src/main/
   --include='*.kt'` — only matches should be in strings (URLs in
   comments, attribution links).

Reproducible builds are documented in `BUILDING.md`.
