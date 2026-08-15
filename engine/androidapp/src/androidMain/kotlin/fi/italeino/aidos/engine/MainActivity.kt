package fi.italeino.aidos.engine

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fi.italeino.aidos.engine.navigation.EngineNavHost
import fi.italeino.aidos.engine.navigation.EngineRoute
import fi.italeino.aidos.engine.theme.AidosEngineTheme

import java.io.File

/**
 * Main activity for Aidos Engine (RFC-0103).
 *
 * Hosts the Compose UI: Home (status + cookbook), model detail/acquire, storage, connected apps,
 * and settings. Model loading and inference run in EngineService's foreground service, not here —
 * this activity only manages the UI, the same separation Aidos Agent's MainActivity keeps between
 * presentation and the runtime it's a client of.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Ensure edge-to-edge (RFC-0103)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Set models directory for LlamaCppInferenceBackend
        System.setProperty("aidos.models.dir", File(filesDir, "models").absolutePath)

        // Start the Engine foreground service
        val engineIntent = Intent(this, EngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(engineIntent)
        } else {
            startService(engineIntent)
        }

        setContent {
            AidosEngineTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val topLevelDestinations = listOf(
                    EngineRoute.Home to (Icons.Default.Home to "Home"),
                    EngineRoute.Models to (Icons.Default.Storage to "Models"),
                    EngineRoute.ConnectedApps to (Icons.Default.Share to "Apps"),
                    EngineRoute.Settings to (Icons.Default.Settings to "Settings")
                )

                Scaffold(
                    bottomBar = {
                        // Only show bottom bar on top-level destinations
                        val isTopLevel = topLevelDestinations.any { (route, _) ->
                            currentDestination?.hierarchy?.any { it.route == route.route } == true
                        }

                        if (isTopLevel) {
                            NavigationBar {
                                topLevelDestinations.forEach { (route, pair) ->
                                    val (icon, label) = pair
                                    NavigationBarItem(
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                        selected = currentDestination?.hierarchy?.any { it.route == route.route } == true,
                                        onClick = {
                                            navController.navigate(route.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        EngineNavHost(navController = navController)
                    }
                }
            }
        }
    }
}

