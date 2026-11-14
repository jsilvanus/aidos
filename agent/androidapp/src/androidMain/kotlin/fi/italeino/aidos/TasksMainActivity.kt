package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Launcher 1 (RFC-0111): "Aidos Tasks"
 * Directly boots into the user's personal / air-gapped LIFE workspace.
 */
class TasksMainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "inbox",
                    initialLifeFilterOnly = true,
                )
            }
        }
    }
}
