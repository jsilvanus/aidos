package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import dev.aidos.api.RealRuntimeClient
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import dev.aidos.androidapp.ui.diff.CommitPresenter
import fi.italeino.aidos.navigation.AidosNavHost
import fi.italeino.aidos.theme.AidosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Main activity for Aidos Android app (M27, M28, RFC-0050).
 *
 * Hosts the Compose UI and wires the runtime presenters.
 * The runtime itself runs in a foreground service (RuntimeServiceHost, M27).
 *
 * This activity only manages the UI — state, navigation, and presenter lifecycle.
 * All business logic remains in presenters (commonMain), which are platform-neutral
 * and testable on the JVM.
 */
class MainActivity : ComponentActivity() {
    
    private val applicationScope = CoroutineScope(SupervisorJob())
    
    // RFC-0050 MVP item 2 puts the runtime in-process in a foreground service; binding this
    // activity to that service (RuntimeServiceHost) and injecting its client is the next step.
    // Until that binding exists, each activity instance owns its own RealRuntimeClient — real
    // command/event semantics, but state doesn't yet survive the activity being destroyed.
    private lateinit var projectsPresenter: ProjectsPresenter
    private lateinit var sessionListPresenter: SessionListPresenter
    private lateinit var runListPresenter: RunListPresenter
    private lateinit var commitPresenter: CommitPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO(RFC-0050 MVP item 2): replace with the RuntimeClient bound from the foreground
        // service (RuntimeServiceHost) once the Service subclass exists.
        val runtimeClient = RealRuntimeClient()
        
        projectsPresenter = ProjectsPresenter(runtimeClient, applicationScope)
        sessionListPresenter = SessionListPresenter(runtimeClient, applicationScope)
        runListPresenter = RunListPresenter(runtimeClient, applicationScope)
        commitPresenter = CommitPresenter(runtimeClient, applicationScope)

        setContent {
            AidosTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()
                    AidosNavHost(
                        navController = navController,
                        projectsPresenter = projectsPresenter,
                        sessionListPresenter = sessionListPresenter,
                        runListPresenter = runListPresenter,
                        commitPresenter = commitPresenter,
                    )
                }
            }
        }

        // Load projects on app start
        projectsPresenter.loadProjects()
    }
}
