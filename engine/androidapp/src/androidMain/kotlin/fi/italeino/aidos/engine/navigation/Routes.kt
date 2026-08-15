package fi.italeino.aidos.engine.navigation

/**
 * Navigation routes for Aidos Engine screens (RFC-0103).
 *
 * Same gesture grammar Aidos Agent uses (fi.italeino.aidos.navigation.AidosRoute) — inherited,
 * not reinvented: horizontal swipe between peers (Home's Status/Cookbook panes), vertical scroll
 * through a list, tap goes deeper.
 *
 * [ModelDetail] must be reachable both from in-app navigation and as a deep link — RFC-0103 says
 * client apps "deep-link into Aidos Engine's own screens to acquire" a model rather than
 * rendering their own download UI, so this route is a real external contract, not just an
 * internal destination.
 */
sealed class EngineRoute(val route: String) {
    data object Home : EngineRoute("home")
    data class ModelDetail(val modelId: String) : EngineRoute("model/{modelId}") {
        fun createRoute(modelId: String) = "model/$modelId"
    }
    data object TestChat : EngineRoute("test_chat")
    data object Storage : EngineRoute("storage")
    data object ConnectedApps : EngineRoute("connected_apps")
    data object Settings : EngineRoute("settings")
}
