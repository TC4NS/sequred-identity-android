# Third-party notices — SeQured Identity Android

This app (MPL-2.0) depends on the libraries listed below. Their licenses
are reproduced or summarised here per their terms. Apache-2.0 §4(d)
attribution is satisfied by the table below; full upstream license text
ships in each artifact's `META-INF/LICENSE` inside the APK.

The shared Rust crypto core (sibling repo `sequred-identity-core`,
MPL-2.0) carries its own [NOTICES.md](https://github.com/TC4NS/sequred-identity-core/blob/main/NOTICES.md)
for its crate dependencies.

## Apache-2.0

| Library | Group | Copyright |
|---|---|---|
| activity-compose, activity-ktx, activity | androidx.activity | Copyright © Android Open Source Project |
| compose-bom (and every transitive Compose module: material3, material-icons-extended, ui, ui-tooling, ui-tooling-preview, animation, foundation, runtime, etc.) | androidx.compose | Copyright © Android Open Source Project |
| core-ktx, core | androidx.core | Copyright © Android Open Source Project |
| core-splashscreen | androidx.core | Copyright © Android Open Source Project |
| fragment-ktx, fragment | androidx.fragment | Copyright © Android Open Source Project |
| lifecycle-runtime-ktx, lifecycle-viewmodel-compose, lifecycle-runtime-compose | androidx.lifecycle | Copyright © Android Open Source Project |
| navigation-compose | androidx.navigation | Copyright © Android Open Source Project |
| biometric | androidx.biometric | Copyright © Android Open Source Project |
| security-crypto | androidx.security | Copyright © Android Open Source Project |
| kotlinx-serialization-json, kotlinx-coroutines | org.jetbrains.kotlinx | Copyright © 2010-2024 JetBrains s.r.o. |
| Kotlin stdlib + reflect | org.jetbrains.kotlin | Copyright © 2010-2024 JetBrains s.r.o. |
| zxing-core | com.google.zxing | Copyright © ZXing authors |
| zxing-android-embedded | com.journeyapps | Copyright © 2012-2024 Journeyapps Pty Ltd |
| tink (transitive via security-crypto) | com.google.crypto.tink | Copyright © Google LLC |

Full Apache-2.0 license text: <http://www.apache.org/licenses/LICENSE-2.0>

## Apache-2.0 OR LGPL-2.1 (dual; we elect Apache-2.0)

| Library | Notes |
|---|---|
| jna (5.14.0) | net.java.dev.jna. Copyright © 2007-2024 Timothy Wall. We use the Apache-2.0 option. |

## BSD-style / MIT-like

| Library | Notes |
|---|---|
| BouncyCastle (transitive via security-crypto / Tink) | Copyright © 2000-2024 The Legion of the Bouncy Castle Inc. Bouncy Castle License (MIT-like). <https://www.bouncycastle.org/licence.html> |

## Bundled site logos

The `app/src/main/res/drawable/site_*.png` files are scaled-down likenesses
of trademarks owned by their respective companies. They are bundled solely
as visual cues so a user can recognise their stored entries at a glance.
Use of a trademark in this manner is permitted under nominative-use
doctrine: we are using the marks to identify the services they belong to,
not to imply endorsement of SeQured Identity by those companies.

If you are a rights-holder and want your logo removed, open an issue at
<https://github.com/TC4NS/sequred-identity-android/issues> and
we will remove it in the next release.

---

This NOTICES file is regenerated from `app/build.gradle.kts` whenever
direct dependencies change. Transitive deps inherit their direct parent's
attribution obligation but also ship their own copyright headers in their
own source / META-INF directories.
