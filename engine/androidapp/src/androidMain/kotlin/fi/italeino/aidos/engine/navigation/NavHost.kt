package fi.italeino.aidos.engine.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import fi.italeino.aidos.engine.ui.ConnectedAppsScreen
import fi.italeino.aidos.engine.ui.HomeScreen
import fi.italeino.aidos.engine.ui.ModelDetailScreen
import fi.italeino.aidos.engine.ui.ProviderDetailScreen
import fi.italeino.aidos.engine.ui.SettingsScreen
import fi.italeino.aidos.engine.ui.StorageScreen

/**
 * Navigation graph for Aidos Engine (RFC-0103, Phase D).
 *
 * [EngineRoute.ModelDetail] is reachable both from in-app navigation (Home's Cookbook pane) and
 * as an external deep link from client apps — RFC-0103 requires this, since client apps deep-link
 * into Aidos Engine's own screens to acquire a model rather than rendering their own download UI.
 *
 * [EngineRoute.ProviderDetail] is reachable from the Providers pane for remote model provider
 * configuration (API key management, enable/disable, configured models list).
 */
@Composable
fun EngineNavHost(
    navController: NavHostController,
) {
    NavHost(
        navController = navController,
        startDestination = EngineRoute.Home.route,
    ) {
        composable(EngineRoute.Home.route) {
            HomeScreen(
                onModelSelected = { modelId ->
                    navController.navigate(EngineRoute.ModelDetail(modelId).createRoute(modelId))
                },
                onProviderSelected = { providerId ->
                    navController.navigate(EngineRoute.ProviderDetail(providerId).createRoute(providerId))
                },
            )
        }

        composable(
            EngineRoute.ModelDetail(modelId = "{modelId}").route,
            // External deep link (RFC-0103): client apps navigate straight here to acquire a
            // model rather than rendering their own download UI. AndroidManifest.xml declares
            // the matching <intent-filter> on MainActivity so this resolves from another app's
            // Intent, not just in-app navigation.
            deepLinks = listOf(navDeepLink { uriPattern = "aidosengine://model/{modelId}" }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId") ?: return@composable
            ModelDetailScreen(modelId = modelId)
        }

        composable(
            EngineRoute.ProviderDetail(providerId = "{providerId}").route,
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("providerId") ?: return@composable
            ProviderDetailScreen(providerId = providerId)
        }

        composable(EngineRoute.Storage.route) {
            StorageScreen()
        }

        composable(EngineRoute.ConnectedApps.route) {
            ConnectedAppsScreen()
        }

        composable(EngineRoute.Settings.route) {
            SettingsScreen()
        }
    }
}
