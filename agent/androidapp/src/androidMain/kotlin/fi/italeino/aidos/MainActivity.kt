package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme

/**
 * Main activity for Aidos Adaptive UI (RFC-0050, RFC-0111, RFC-0112).
 *
 * Hosts the Universal Adaptive Compose UI backed by persistent SQLite storage
 * via [AndroidRuntimeClientFactory].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)

        val initialProjectId = intent.data?.lastPathSegment
            ?: intent.getStringExtra("projectId")
            ?: "default-workspace"

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "inbox",
                    initialProjectId = initialProjectId,
                    onPinProjectLauncher = { projectId, projectName ->
                        ProjectLauncher.pinProjectShortcut(this, projectId, projectName)
                    },
                )
            }
        }
    }
}
