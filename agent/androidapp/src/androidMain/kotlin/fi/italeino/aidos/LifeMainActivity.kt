package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme
import fi.italeino.aidos.service.AndroidNativeInferenceConnector

/**
 * Launcher 1 (RFC-0111): "Aidos Life"
 * Directly boots into the user's personal / air-gapped LIFE workspace.
 */
class LifeMainActivity : ComponentActivity() {

    private lateinit var nativeConnector: AndroidNativeInferenceConnector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)
        nativeConnector = AndroidNativeInferenceConnector(this).also { it.start() }

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "inbox",
                    initialLifeFilterOnly = true,
                    initialProjectId = "life",
                    nativeProcessHealth = nativeConnector.health,
                    onPinProjectLauncher = { projectId, projectName ->
                        ProjectLauncher.pinProjectShortcut(this, projectId, projectName)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (::nativeConnector.isInitialized) {
            nativeConnector.stop()
        }
        super.onDestroy()
    }
}
