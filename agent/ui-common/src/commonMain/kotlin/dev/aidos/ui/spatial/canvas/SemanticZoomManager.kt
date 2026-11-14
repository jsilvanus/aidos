package dev.aidos.ui.spatial.canvas

enum class LodLevel {
    MACRO_GALAXY,     // 0.05 <= zoom < 0.35: Cluster hulls, Goal names, aggregate %
    MESO_CLUSTER,     // 0.35 <= zoom < 0.75: Actor-colored capsules, Bezier edges
    MICRO_BLUEPRINT,  // zoom >= 0.75: Full cards, scopes, step fuel meters
}

object SemanticZoomManager {
    fun computeLod(zoom: Float): LodLevel {
        return when {
            zoom < 0.35f -> LodLevel.MACRO_GALAXY
            zoom < 0.75f -> LodLevel.MESO_CLUSTER
            else -> LodLevel.MICRO_BLUEPRINT
        }
    }
}
