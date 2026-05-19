package co.sequred.identity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.sequred.identity.ui.theme.Brand
import co.sequred.identity.ui.theme.BrandType
import co.sequred.identity.ui.theme.LocalWindowSize
import co.sequred.identity.ui.theme.SeQuredHeader
import co.sequred.identity.ui.theme.WindowSize

/**
 * In-app attribution screen. Static structured data rendered natively so the
 * styling matches the rest of the app — earlier version loaded raw markdown
 * from assets which read as a wall of text. The same data still ships in
 * NOTICES.md / assets/notices.md for repo + APK-level Apache-2.0 §4(d)
 * compliance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val windowSize = LocalWindowSize.current
    val gutter = when (windowSize) {
        WindowSize.Compact -> 16.dp; WindowSize.Medium -> 24.dp; WindowSize.Expanded -> 40.dp
    }
    val contentMaxWidth = when (windowSize) { WindowSize.Expanded -> 720.dp; else -> 4096.dp }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses", color = Brand.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Brand.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = contentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = gutter, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    SeQuredHeader(
                        modifier = Modifier.padding(vertical = 12.dp),
                        tagline = "Third-party attributions",
                    )
                }
                item { Intro() }
                items(LICENSE_SECTIONS, key = { it.title }) { section -> LicenseSection(section) }
                item { Trademarks() }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun Intro() {
    Text(
        "SeQured Identity is licensed under MPL-2.0. It builds on the libraries listed below — " +
            "their copyright notices are reproduced here per their licenses' attribution clauses.",
        color = Brand.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LicenseSection(section: LicenseSection) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                section.title,
                color = Brand.Capri,
                style = BrandType.sectionLabel(),
            )
            section.url?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, color = Brand.TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        section.summary?.let {
            Text(it, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            color = Brand.Surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                section.items.forEachIndexed { index, item ->
                    LibraryRow(item)
                    if (index < section.items.lastIndex) {
                        HorizontalDivider(color = Brand.Border.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            item.name,
            color = Brand.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            item.copyright,
            color = Brand.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        item.note?.let {
            Text(it, color = Brand.TextSecondary.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Trademarks() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "BUNDLED SITE LOGOS",
            color = Brand.Capri,
            style = BrandType.sectionLabel(),
        )
        Text(
            "The site icons in the credential list are scaled-down likenesses of trademarks owned by their respective companies. " +
                "They appear only as visual cues so you can recognise your stored entries at a glance — nominative-use territory, not endorsement. " +
                "Rights-holders can request removal by opening an issue at github.com/TC4NS/sequred-identity-android.",
            color = Brand.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ─── Data model ──────────────────────────────────────────────────────────────

private data class LicenseSection(
    val title: String,
    val summary: String? = null,
    val url: String? = null,
    val items: List<LibraryItem>,
)

private data class LibraryItem(
    val name: String,
    val copyright: String,
    val note: String? = null,
)

private val LICENSE_SECTIONS = listOf(
    LicenseSection(
        title = "APACHE-2.0",
        summary = "Modifications + redistribution permitted under the license terms. " +
            "Each library's source carries its own copyright header.",
        url = "apache.org/licenses/LICENSE-2.0",
        items = listOf(
            LibraryItem("AndroidX (Activity, Compose, Core, Fragment, Lifecycle, Navigation, Material 3, Material Icons Extended, Splash, Biometric, Security-crypto)",
                "Copyright © Android Open Source Project"),
            LibraryItem("Kotlin standard library + reflect",
                "Copyright © 2010–2024 JetBrains s.r.o."),
            LibraryItem("kotlinx-serialization + kotlinx-coroutines",
                "Copyright © 2010–2024 JetBrains s.r.o."),
            LibraryItem("ZXing core",
                "Copyright © ZXing authors"),
            LibraryItem("ZXing Android Embedded",
                "Copyright © 2012–2024 Journeyapps Pty Ltd"),
            LibraryItem("Tink (transitive)",
                "Copyright © Google LLC",
                "Used by EncryptedSharedPreferences for the on-disk PIN + biometric blob encryption."),
            LibraryItem("Bouncy Castle (transitive)",
                "Copyright © 2000–2024 The Legion of the Bouncy Castle Inc.",
                "Bouncy Castle License — MIT-style."),
        ),
    ),
    LicenseSection(
        title = "APACHE-2.0 OR LGPL-2.1 (DUAL)",
        summary = "We elect the Apache-2.0 option.",
        items = listOf(
            LibraryItem("JNA 5.14.0",
                "Copyright © 2007–2024 Timothy Wall",
                "Bridges the Rust core's UniFFI bindings into the JVM."),
        ),
    ),
    LicenseSection(
        title = "MPL-2.0 (RUST CORE DEPS)",
        summary = "The Rust core is MPL-2.0. Same applies to the UniFFI build dep — generated bindings are tool output and not subject to MPL.",
        items = listOf(
            LibraryItem("UniFFI",
                "Copyright © Mozilla Foundation",
                "Mozilla MPL FAQ: bindings emitted by uniffi-bindgen take the license of the consuming project, not MPL."),
        ),
    ),
    LicenseSection(
        title = "APACHE-2.0 OR MIT (RUST CRYPTO + UTILS)",
        summary = "Dual-licensed Rust crates underpinning the cryptography. We elect Apache-2.0.",
        items = listOf(
            LibraryItem("aes-gcm, argon2, sha3, hmac",
                "Copyright © RustCrypto Developers",
                "Argon2id KDF · AES-256-GCM · PBKDF2-HMAC-SHA3-256."),
            LibraryItem("zeroize",
                "Copyright © The RustCrypto Project Developers",
                "Wipes key buffers on drop."),
            LibraryItem("serde, serde_json, serde_bytes, thiserror",
                "Copyright © Erick Tryzelaar, David Tolnay"),
            LibraryItem("base64",
                "Copyright © Marshall Pierce"),
            LibraryItem("uuid",
                "Copyright © Ashley Mannix, Christopher Armstrong, Dylan DPC, Hunar Roop Kahlon"),
            LibraryItem("rand",
                "Copyright © The Rand Project Developers + The Rust Project Developers"),
            LibraryItem("hex",
                "Copyright © The rust-hex Authors"),
        ),
    ),
)
