package fi.italeino.aidos.engine.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.inference.InferenceTester
import fi.italeino.aidos.engine.ui.ConnectedAppsScreen
import fi.italeino.aidos.engine.ui.HomeScreen
import fi.italeino.aidos.engine.ui.ModelConfigScreen
import fi.italeino.aidos.engine.ui.ModelDetailScreen
import fi.italeino.aidos.engine.ui.ModelsScreen
import fi.italeino.aidos.engine.ui.ProviderDetailScreen
import fi.italeino.aidos.engine.ui.SettingsScreen
import fi.italeino.aidos.engine.ui.TestChatScreen

/**
 * Navigation graph for Aidos Engine (RFC-0103, Phase D).
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
            HomeScreen()
        }

        composable(
            route = "model_detail?id={id}",
            deepLinks = listOf(
                navDeepLink { uriPattern = "aidosengine://model?id={id}" },
                navDeepLink { uriPattern = "aidosengine://model/{id}" }
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("id") ?: return@composable
            ModelDetailScreen(
                modelId = modelId,
                onBackClick = { navController.popBackStack() },
                onTestChatClick = { id, name ->
                    navController.navigate(EngineRoute.TestChat(id, name).createRoute(id, name))
                },
                globalModelRuntime = EngineService.instance?.modelRuntime,
            )
        }

        composable(
            route = "test_chat?id={id}&name={name}",
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("id") ?: return@composable
            val modelName = backStackEntry.arguments?.getString("name") ?: return@composable
            val runtime = EngineService.instance?.modelRuntime
            TestChatScreen(
                modelId = modelId,
                modelName = modelName,
                onBackClick = { navController.popBackStack() },
                inferenceTester = runtime?.let(::InferenceTester),
            )
        }

        composable(
            route = "provider_detail?id={id}",
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("id") ?: return@composable
            ProviderDetailScreen(providerId = providerId, onBackClick = { navController.popBackStack() })
        }

        composable(EngineRoute.Models.route) {
            ModelsScreen(
                onModelSelected = { modelId ->
                    navController.navigate(EngineRoute.ModelDetail(modelId).createRoute(modelId))
                },
                onProviderSelected = { providerId ->
                    navController.navigate(EngineRoute.ProviderDetail(providerId).createRoute(providerId))
                },
                onModelConfigClick = { modelId ->
                    navController.navigate(EngineRoute.ModelConfig(modelId).createRoute(modelId))
                },
            )
        }

        composable(
            route = "model_config?id={id}",
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("id") ?: return@composable
            ModelConfigScreen(
                modelId = modelId,
                onBackClick = { navController.popBackStack() },
            )
        }

        composable(EngineRoute.ConnectedApps.route) {
            ConnectedAppsScreen()
        }

        composable(EngineRoute.Settings.route) {
            SettingsScreen()
        }
    }
}
