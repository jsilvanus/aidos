package fi.italeino.aidos.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import dev.aidos.androidapp.ui.diff.CommitPresenter
import fi.italeino.aidos.ui.ApprovalCard
import fi.italeino.aidos.ui.CommitReviewScreen
import fi.italeino.aidos.ui.EditorScreen
import fi.italeino.aidos.ui.HomeScreen
import fi.italeino.aidos.ui.RunDetailScreen
import fi.italeino.aidos.ui.SessionsScreen

/**
 * Navigation graph for Aidos (M28, RFC-0050).
 *
 * Wires all screens together with the gesture grammar:
 * - Home: swipe horizontally between Inbox and Projects
 * - Sessions: tap to enter, swipe between runs
 * - Runs: vertical scroll through timeline, tap to expand
 * - Approval: taps from home or run details
 * - Commit review: tap from approval/run context
 * - Editor: tap to edit a file
 */
@Composable
fun AidosNavHost(
    navController: NavHostController,
    projectsPresenter: ProjectsPresenter,
    sessionListPresenter: SessionListPresenter,
    runListPresenter: RunListPresenter,
    commitPresenter: CommitPresenter,
) {
    NavHost(
        navController = navController,
        startDestination = AidosRoute.Home.route,
    ) {
        composable(AidosRoute.Home.route) {
            HomeScreen(
                projectsPresenter = projectsPresenter,
                onProjectSelected = { projectId ->
                    navController.navigate(
                        AidosRoute.ProjectDetail(projectId).createRoute(projectId)
                    )
                },
            )
        }

        composable(AidosRoute.Projects.route) {
            // Projects screen (same as home's projects pane, for standalone navigation if needed)
            HomeScreen(
                projectsPresenter = projectsPresenter,
                onProjectSelected = { projectId ->
                    navController.navigate(
                        AidosRoute.ProjectDetail(projectId).createRoute(projectId)
                    )
                },
            )
        }

        composable(AidosRoute.ProjectDetail(projectId = "{projectId}").route) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            // Project detail screen would show Git status, availability, recent runs
            // For now, navigate to sessions
            SessionsScreen(
                projectId = projectId,
                sessionListPresenter = sessionListPresenter,
                onRunSelected = { runId ->
                    navController.navigate(
                        AidosRoute.RunDetail(projectId, runId).createRoute(projectId, runId)
                    )
                },
            )
        }

        composable(AidosRoute.Sessions(projectId = "{projectId}").route) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            SessionsScreen(
                projectId = projectId,
                sessionListPresenter = sessionListPresenter,
                onRunSelected = { runId ->
                    navController.navigate(
                        AidosRoute.RunDetail(projectId, runId).createRoute(projectId, runId)
                    )
                },
            )
        }

        composable(AidosRoute.RunDetail(projectId = "{projectId}", sessionId = "{sessionId}").route) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            RunDetailScreen(
                projectId = projectId,
                sessionId = sessionId,
                runListPresenter = runListPresenter,
            )
        }

        composable(AidosRoute.DiffReview.route) {
            ApprovalCard(
                onApprove = { navController.popBackStack() },
                onReject = { navController.popBackStack() },
            )
        }

        composable(AidosRoute.CommitReview.route) {
            CommitReviewScreen(
                commitPresenter = commitPresenter,
                onCommit = { message ->
                    // After commit, navigate back or to home
                    navController.popBackStack()
                },
            )
        }

        composable(AidosRoute.Editor.route) {
            EditorScreen(
                filePath = "file.txt", // Would be passed as parameter
                onSave = { content ->
                    navController.popBackStack()
                },
            )
        }
    }
}
