package fi.italeino.aidos.navigation

/**
 * Navigation routes for Aidos screens (M28, RFC-0050).
 *
 * Routes follow the gesture grammar:
 * - Horizontal navigation: between peer screens (swipe between inbox and projects)
 * - Vertical navigation: within a list (scroll within sessions)
 * - Tap: go deeper (from projects list to project detail, from runs to run detail)
 */
sealed class AidosRoute(val route: String) {
    data object Home : AidosRoute("home")
    data object Projects : AidosRoute("projects")
    data class ProjectDetail(val projectId: String) : AidosRoute("project/{projectId}") {
        fun createRoute(projectId: String) = "project/$projectId"
    }
    data class Sessions(val projectId: String) : AidosRoute("sessions/{projectId}") {
        fun createRoute(projectId: String) = "sessions/$projectId"
    }
    data class RunDetail(val projectId: String, val sessionId: String) : AidosRoute("run/{projectId}/{sessionId}") {
        fun createRoute(projectId: String, sessionId: String) = "run/$projectId/$sessionId"
    }
    data object DiffReview : AidosRoute("diff_review")
    data object CommitReview : AidosRoute("commit_review")
    data object Editor : AidosRoute("editor")
}
