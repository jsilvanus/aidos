package dev.aidos.ui.spatial.canvas

import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import kotlin.math.cos
import kotlin.math.sin

data class SpatialNode(
    val node: IntentNodeRecord,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
)

object ForceDirectedLayout {
    fun layoutDag(
        nodes: List<IntentNodeRecord>,
        edges: List<IntentEdgeRecord>,
        canvasWidth: Float = 2000f,
        canvasHeight: Float = 2000f,
    ): Map<String, Pair<Float, Float>> {
        val count = nodes.size
        if (count == 0) return emptyMap()

        // Distribute in a radial / tiered formation initially
        val centerX = canvasWidth / 2f
        val centerY = canvasHeight / 2f
        val result = mutableMapOf<String, Pair<Float, Float>>()

        nodes.forEachIndexed { index, node ->
            val angle = (2.0 * Math.PI * index / count.toDouble()).toFloat()
            val radius = 300f + (index % 5) * 150f
            val x = centerX + radius * cos(angle)
            val y = centerY + radius * sin(angle)
            result[node.id] = x to y
        }

        return result
    }
}
