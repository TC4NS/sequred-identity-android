package co.sequred.identity.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import co.sequred.identity.data.VaultSession
import co.sequred.identity.data.VaultUuid
import co.sequred.identity.ui.auth.AuthenticatorEditScreen
import co.sequred.identity.ui.main.MainTabsScreen
import co.sequred.identity.ui.settings.ImportExportScreen
import co.sequred.identity.ui.settings.LicensesScreen
import co.sequred.identity.ui.setup.PinSetupScreen
import co.sequred.identity.ui.unlock.UnlockScreen
import co.sequred.identity.ui.vault.EntryDetailScreen
import co.sequred.identity.ui.vault.EntryEditScreen
import java.util.UUID

/**
 * Top-level navigation host. Gate screens (Setup / Unlock) live at the root;
 * once unlocked the user lands on `MainTabsScreen`, which owns the bottom
 * navigation and the four primary tabs. Modal-style screens (entry detail,
 * editors) are still pushed routes so the system back button restores the
 * tabbed UI underneath.
 */
@Composable
fun AppNav(session: VaultSession) {
    val nav = rememberNavController()
    val state by session.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        val gateRoute = when (state) {
            is VaultSession.State.NeedsSetup -> Routes.Setup
            is VaultSession.State.Locked -> Routes.Unlock
            is VaultSession.State.Unlocked -> Routes.Main
        }
        nav.navigate(gateRoute) {
            popUpTo(nav.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }

    val start = when (state) {
        is VaultSession.State.NeedsSetup -> Routes.Setup
        is VaultSession.State.Locked -> Routes.Unlock
        is VaultSession.State.Unlocked -> Routes.Main
    }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) + fadeIn(tween(220)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) + fadeOut(tween(120)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) + fadeIn(tween(220)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) + fadeOut(tween(120)) },
    ) {
        composable(Routes.Setup) { PinSetupScreen(session) }
        composable(Routes.Unlock) { UnlockScreen(session) }
        composable(Routes.Main) {
            MainTabsScreen(
                session = session,
                onOpenEntry = { id -> nav.navigate(Routes.entryDetail(id)) },
                onAddEntry = { nav.navigate(Routes.entryEdit(null)) },
                onAddAuth = { nav.navigate(Routes.authEdit(null)) },
                onOpenImportExport = { nav.navigate(Routes.ImportExport) },
                onOpenLicenses = { nav.navigate(Routes.Licenses) },
            )
        }
        composable(Routes.ImportExport) {
            ImportExportScreen(session = session, onBack = { nav.popBackStack() })
        }
        composable(Routes.Licenses) {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ENTRY_DETAIL_TEMPLATE) { backStack ->
            val id = backStack.idArg() ?: run { nav.popBackStack(); return@composable }
            EntryDetailScreen(
                session = session,
                entryId = id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.entryEdit(it)) },
            )
        }
        composable(Routes.ENTRY_EDIT_TEMPLATE) { backStack ->
            val id = backStack.optIdArg()
            EntryEditScreen(session = session, entryId = id, onDone = { nav.popBackStack() })
        }
        composable(Routes.AUTH_EDIT_TEMPLATE) { backStack ->
            val id = backStack.optIdArg()
            AuthenticatorEditScreen(session = session, entryId = id, onDone = { nav.popBackStack() })
        }
    }
}

private fun NavBackStackEntry.idArg(): VaultUuid? =
    arguments?.getString(Routes.ARG_ID)?.takeIf { it != Routes.NEW_TOKEN }?.let { VaultUuid(UUID.fromString(it)) }

private fun NavBackStackEntry.optIdArg(): VaultUuid? = idArg()

object Routes {
    const val Setup = "setup"
    const val Unlock = "unlock"
    const val Main = "main"
    const val ImportExport = "import-export"
    const val Licenses = "licenses"
    const val ARG_ID = "id"
    const val NEW_TOKEN = "new"

    const val ENTRY_DETAIL_TEMPLATE = "entry/{$ARG_ID}"
    const val ENTRY_EDIT_TEMPLATE = "entry/edit/{$ARG_ID}"
    const val AUTH_EDIT_TEMPLATE = "auth/edit/{$ARG_ID}"

    fun entryDetail(id: VaultUuid): String = "entry/${id.value}"
    fun entryEdit(id: VaultUuid?): String = "entry/edit/${id?.value ?: NEW_TOKEN}"
    fun authEdit(id: VaultUuid?): String = "auth/edit/${id?.value ?: NEW_TOKEN}"
}
