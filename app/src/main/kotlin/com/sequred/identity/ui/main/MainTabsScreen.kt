package com.sequred.identity.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sequred.identity.data.VaultSession
import com.sequred.identity.data.VaultUuid
import com.sequred.identity.ui.auth.AuthenticatorListScreen
import com.sequred.identity.ui.generator.GeneratorScreen
import com.sequred.identity.ui.settings.SettingsScreen
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.vault.VaultListScreen

enum class MainTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Vault("Vault", Icons.Outlined.Lock),
    Authenticator("Auth", Icons.Filled.VerifiedUser),
    Generator("Generate", Icons.Filled.Tune),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun MainTabsScreen(
    session: VaultSession,
    onOpenEntry: (VaultUuid) -> Unit,
    onAddEntry: () -> Unit,
    onAddAuth: () -> Unit,
    onOpenImportExport: () -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.Vault) }

    Scaffold(
        containerColor = Color.Transparent,
        // Don't add top/bottom insets here — each tab's inner Scaffold owns
        // its own insets via its TopAppBar / content area. Otherwise the
        // bottom nav consumption stacks with the inner Scaffold's defaults
        // and the whole UI gets pushed down by ~2 × status bar height.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(
                containerColor = Brand.Background,   // solid so the gesture nav area reads cleanly
                tonalElevation = 0.dp,
            ) {
                MainTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = Brand.Capri,
                            unselectedIconColor = Brand.TextSecondary,
                            unselectedTextColor = Brand.TextSecondary,
                            indicatorColor = Brand.Capri,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        AnimatedContent(
            targetState = tab,
            label = "tab-switch",
            transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) },
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)            // reserve bottom nav height
                .consumeWindowInsets(inner), // tell inner Scaffolds the bottom is taken
        ) { t ->
            when (t) {
                MainTab.Vault -> VaultListScreen(
                    session = session,
                    onAddEntry = onAddEntry,
                    onOpenEntry = onOpenEntry,
                )
                MainTab.Authenticator -> AuthenticatorListScreen(
                    session = session,
                    onAdd = onAddAuth,
                )
                MainTab.Generator -> GeneratorScreen()
                MainTab.Settings -> SettingsScreen(session = session, onOpenImportExport = onOpenImportExport)
            }
        }
    }
}

