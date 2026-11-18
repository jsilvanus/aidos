package fi.italeino.aidos

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.ui.theme.AidosTheme

/**
 * Dedicated Activity for individual Project / Workspace documents (RFC-0111).
 *
 * Configured with `documentLaunchMode="intoExisting"` so each open project appears
 * as a distinct, persistent card in Android's Recents app switcher.
 */
class ProjectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RFC-0124 decision U5: no magic fallback id. A null id is resolved by WorkspaceBootstrap.
        val projectId = intent.data?.lastPathSegment
            ?: intent.getStringExtra("projectId")

        val projectName = intent.getStringExtra("projectName")
            ?: projectId?.replaceFirstChar { it.uppercase() }
            ?: "Workspace"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setTaskDescription(
                ActivityManager.TaskDescription.Builder()
                    .setLabel("Aidos · $projectName")
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription("Aidos · $projectName"))
        }

        val runtimeClient = AndroidRuntimeClientFactory.createRuntimeClient(this)

        setContent {
            AidosTheme {
                AidosUniversalApp(
                    runtimeClient = runtimeClient,
                    initialDestinationId = "outliner",
                    initialProjectId = projectId,
                    onPinProjectLauncher = { id, name ->
                        ProjectLauncher.pinProjectShortcut(this, id, name)
                    },
                )
            }
        }
    }
}
