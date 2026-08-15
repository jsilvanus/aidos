package fi.italeino.aidos.engine

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import fi.italeino.aidos.engine.navigation.EngineNavHost
import fi.italeino.aidos.engine.theme.AidosEngineTheme

/**
 * Main activity for Aidos Engine (RFC-0103).
 *
 * Hosts the Compose UI: Home (status + cookbook), model detail/acquire, storage, connected apps,
 * and settings. Model loading and inference run in EngineService's foreground service, not here —
 * this activity only manages the UI, the same separation Aidos Agent's MainActivity keeps between
 * presentation and the runtime it's a client of.
 *
 * TODO(RFC-0103 Phase E): Bind Engine Core's in-process state (resident models, download progress,
 * connected-app registry) once Engine Core exposes it — screens currently show placeholder text.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Ensure edge-to-edge (RFC-0103)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Start the Engine foreground service
        val engineIntent = Intent(this, EngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(engineIntent)
        } else {
            startService(engineIntent)
        }

        setContent {
            AidosEngineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()
                    EngineNavHost(navController = navController)
                }
            }
        }
    }
}

