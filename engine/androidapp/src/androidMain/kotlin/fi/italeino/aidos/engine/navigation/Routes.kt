package fi.italeino.aidos.engine.navigation

/**
 * Navigation routes for Aidos Engine screens (RFC-0103, Phase D).
 *
 * Same gesture grammar Aidos Agent uses (fi.italeino.aidos.navigation.AidosRoute) — inherited,
 * not reinvented: horizontal swipe between peers (Home's Status/Cookbook/Providers panes),
 * vertical scroll through a list, tap goes deeper.
 *
 * [ModelDetail] must be reachable both from in-app navigation and as a deep link — RFC-0103 says
 * client apps "deep-link into Aidos Engine's own screens to acquire" a model rather than
 * rendering their own download UI, so this route is a real external contract, not just an
 * internal destination.
 */
sealed class EngineRoute(val route: String) {
    data object Home : EngineRoute("home")
    data class ModelDetail(val modelId: String) : EngineRoute("model_detail?id={id}") {
        fun createRoute(modelId: String) = "model_detail?id=${java.net.URLEncoder.encode(modelId, "UTF-8")}"
    }
    data class TestChat(val modelId: String, val modelName: String) : EngineRoute("test_chat?id={id}&name={name}") {
        fun createRoute(modelId: String, modelName: String) = 
            "test_chat?id=${java.net.URLEncoder.encode(modelId, "UTF-8")}&name=${java.net.URLEncoder.encode(modelName, "UTF-8")}"
    }
    data class ProviderDetail(val providerId: String) : EngineRoute("provider_detail?id={id}") {
        fun createRoute(providerId: String) = "provider_detail?id=${java.net.URLEncoder.encode(providerId, "UTF-8")}"
    }
    data object Models : EngineRoute("models")
    data class ModelConfig(val modelId: String) : EngineRoute("model_config?id={id}") {
        fun createRoute(modelId: String) = "model_config?id=${java.net.URLEncoder.encode(modelId, "UTF-8")}"
    }
    data object ConnectedApps : EngineRoute("connected_apps")
    data object Settings : EngineRoute("settings")
}

