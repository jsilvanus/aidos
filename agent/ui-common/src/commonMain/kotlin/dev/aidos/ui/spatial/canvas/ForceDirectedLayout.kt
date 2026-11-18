package dev.aidos.ui.spatial.canvas

import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import kotlin.math.cos
import kotlin.math.sqrt
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

    private const val SEED_RADIUS = 120f
    private const val RELAX_STEPS = 60
    private const val REPULSION = 4000f
    private const val SPRING_LENGTH = 160f
    private const val SPRING_STIFFNESS = 0.05f
    private const val MAX_MOVE = 30f

    /**
     * UI-35: a force layout that keeps positions the canvas already has. With the same node set the
     * previous positions come back unchanged. When nodes come or go, only the new nodes move, so the
     * picture does not jump. New nodes start near their linked neighbours and relax against everything.
     */
    fun layoutSeeded(
        nodes: List<IntentNodeRecord>,
        edges: List<IntentEdgeRecord>,
        previous: Map<String, Pair<Float, Float>>,
        canvasWidth: Float = 2000f,
        canvasHeight: Float = 2000f,
    ): Map<String, Pair<Float, Float>> {
        if (nodes.isEmpty()) return emptyMap()
        val pos = HashMap<String, Pair<Float, Float>>()
        nodes.forEach { node -> previous[node.id]?.let { pos[node.id] = it } }
        val fresh = nodes.filter { it.id !in pos }
        if (fresh.isEmpty()) return nodes.associate { it.id to pos.getValue(it.id) }

        val neighbours = HashMap<String, MutableList<String>>()
        edges.forEach { edge ->
            neighbours.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
            neighbours.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge.fromNodeId)
        }

        val centreX = canvasWidth / 2f
        val centreY = canvasHeight / 2f
        fresh.forEachIndexed { index, node ->
            val known = neighbours[node.id].orEmpty().mapNotNull { pos[it] }
            val anchorX = if (known.isEmpty()) centreX else known.sumOf { it.first.toDouble() }.toFloat() / known.size
            val anchorY = if (known.isEmpty()) centreY else known.sumOf { it.second.toDouble() }.toFloat() / known.size
            val angle = (2.0 * Math.PI * index / fresh.size).toFloat()
            pos[node.id] = (anchorX + SEED_RADIUS * cos(angle)) to (anchorY + SEED_RADIUS * sin(angle))
        }

        val movable = fresh.map { it.id }
        val everyone = pos.keys.toList()
        repeat(RELAX_STEPS) {
            for (id in movable) {
                val (x, y) = pos.getValue(id)
                var fx = 0f
                var fy = 0f
                for (other in everyone) {
                    if (other == id) continue
                    val (ox, oy) = pos.getValue(other)
                    var dx = x - ox
                    var dy = y - oy
                    var d2 = dx * dx + dy * dy
                    if (d2 < 1f) {
                        dx = 1f
                        dy = 0f
                        d2 = 1f
                    }
                    val d = sqrt(d2)
                    val push = REPULSION / d2
                    fx += dx / d * push
                    fy += dy / d * push
                }
                for (other in neighbours[id].orEmpty()) {
                    val (ox, oy) = pos[other] ?: continue
                    val dx = x - ox
                    val dy = y - oy
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < 1f) continue
                    val pull = (d - SPRING_LENGTH) * SPRING_STIFFNESS
                    fx -= dx / d * pull
                    fy -= dy / d * pull
                }
                val mx = fx.coerceIn(-MAX_MOVE, MAX_MOVE)
                val my = fy.coerceIn(-MAX_MOVE, MAX_MOVE)
                pos[id] = (x + mx) to (y + my)
            }
        }
        return nodes.associate { it.id to pos.getValue(it.id) }
    }
}
