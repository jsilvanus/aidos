package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.androidapp.voice.HybridSttRouter
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme
import fi.italeino.aidos.service.AndroidNativeInferenceConnector
import fi.italeino.aidos.voice.AidosEngineSttClient
import fi.italeino.aidos.voice.AndroidDeviceSttEngine

/**
 * Launcher 1 (RFC-0111): "Aidos Life"
 * Directly boots into the user's personal / air-gapped LIFE workspace.
 */
class LifeMainActivity : ComponentActivity() {

    private lateinit var nativeConnector: AndroidNativeInferenceConnector
    private lateinit var voiceRouter: HybridSttRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)
        nativeConnector = AndroidNativeInferenceConnector(this).also { it.start() }
        voiceRouter = HybridSttRouter(
            aidosStt = AidosEngineSttClient(this),
            androidDeviceStt = AndroidDeviceSttEngine(this),
        )

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "inbox",
                    initialLifeFilterOnly = true,
                    // RFC-0124 decision U5: null id; initialLifeFilterOnly makes the bootstrap pick the Life project.
                    initialProjectId = null,
                    nativeProcessHealth = nativeConnector.health,
                    voiceCaptureController = voiceRouter,
                    onPinProjectLauncher = { projectId, projectName ->
                        ProjectLauncher.pinProjectShortcut(this, projectId, projectName)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (::voiceRouter.isInitialized) {
            voiceRouter.cancel()
        }
        if (::nativeConnector.isInitialized) {
            nativeConnector.stop()
        }
        super.onDestroy()
    }
}
