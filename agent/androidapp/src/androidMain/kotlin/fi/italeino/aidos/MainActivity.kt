package fi.italeino.aidos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.aidos.androidapp.ui.diff.CommitPresenter
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import fi.italeino.aidos.navigation.AidosNavHost
import fi.italeino.aidos.theme.AidosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Main activity for Aidos Android app (M27, M28, RFC-0050).
 *
 * Hosts the Compose UI and wires the runtime presenters. The runtime client is the same
 * process-scoped, persistence-backed instance used by the foreground service.
 */
class MainActivity : ComponentActivity() {

    private val applicationScope = CoroutineScope(SupervisorJob())

    private lateinit var projectsPresenter: ProjectsPresenter
    private lateinit var sessionListPresenter: SessionListPresenter
    private lateinit var runListPresenter: RunListPresenter
    private lateinit var commitPresenter: CommitPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeClient = AndroidRuntimeClientFactory.get(this)

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

        projectsPresenter.loadProjects()
    }
}
