package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme
import fi.italeino.aidos.assistant.AidosVoiceInteractionSession
import fi.italeino.aidos.service.AndroidNativeInferenceConnector

/**
 * Main activity for Aidos Adaptive UI (RFC-0050, RFC-0111, RFC-0112).
 *
 * Hosts the Universal Adaptive Compose UI backed by persistent SQLite storage
 * via [AndroidRuntimeClientFactory] and observes `:native_inference` health via
 * [AndroidNativeInferenceConnector].
 */
class MainActivity : ComponentActivity() {

    private lateinit var nativeConnector: AndroidNativeInferenceConnector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)
        nativeConnector = AndroidNativeInferenceConnector(this).also { it.start() }

        val initialProjectId = intent.data?.lastPathSegment
            ?: intent.getStringExtra("projectId")
            ?: "default-workspace"
        val openAssistant = intent.getBooleanExtra(AidosVoiceInteractionSession.EXTRA_OPEN_ASSISTANT, false)

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "inbox",
                    initialProjectId = initialProjectId,
                    initialAssistantOpen = openAssistant,
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
